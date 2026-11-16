package ansercore.frontend.ifu.icache

import chisel3._
import chisel3.util._
import utils.Mux1H

import utils.ReplacementPolicy
import utils.SinglePortSRAMTemplate

import ansercore.FrontendToMMUIO
import ansercore.MMUToFrontendIO
import ansercore.frontend.ftq.FTQLookup
import ansercore.frontend.FrontendHelper
import ansercore.PrivMode
import ansercore.frontend.ftq.FTQPtr
import utils.LowPriorityOH
import ansercore.frontend.FrontendExcept


class ICache extends ICacheModule with ICacheHelper with FrontendHelper{
  class ICacheIO extends ICacheBundle{
    val flush = Input(Bool())
    val fencei = Input(Bool())
    //lookup
    val bpuOverride = Flipped(Valid(new FTQPtr))
    val lookup = Flipped(Decoupled(new FTQLookup))

    //MMU
    val toMMU = Output(new FrontendToMMUIO)
    val fromMMU = Input(new MMUToFrontendIO)
    //fetch
    val fetch = Flipped(Decoupled(new ICacheFetch)) // fetch s0
    val fetchResp = Decoupled(new ICacheFetchResp) // fetch s2
    val fetchS0Fire = Output(Bool())
    val fetchS1Fire = Output(Bool())
    val memReq = Decoupled(new ICacheMemReq)
    val memResp = Flipped(Decoupled(new ICacheMemResp))
  }
  val io = IO(new ICacheIO)

//------------------alias-------------------------------//
  val flush = io.flush
  val lookupValid = io.lookup.valid
  val lookup = io.lookup.bits
  val fetchValid = io.fetch.valid
  val fetch = io.fetch.bits
  val pmpResp = io.fromMMU.pmpResp
  val pmaResp = io.fromMMU.pmaResp
  val itlbResp = io.fromMMU.itlbResp

//------------------module-------------------------------//
  val wayLookup = Module(new ICacheWayLookup)
  val mshrUnit = Module(new ICacheMissUnit)

  //
  val wayLookupPushReady = wayLookup.io.lookupPush.ready
  val wayLookupPopValid = wayLookup.io.fetchPop.valid
  val wayLookupEntry = wayLookup.io.fetchPop.bits
  val wayLookupExceptInfo = wayLookupEntry.exceptInfo
  val wayLookupInfo = wayLookupEntry.wayLookupInfo
  val wayLookupITLBinfo = wayLookupInfo.itlbInfo
  val wayLookupCacheInfo = wayLookupInfo.cacheInfo
  val wayLookupCacheable = !(wayLookupITLBinfo.isNC || wayLookupITLBinfo.isMMIO)
  val wayLookupIsException = FrontendExcept.isException(wayLookupExceptInfo)




  mshrUnit.io.memReq.ready := io.memReq.ready
  mshrUnit.io.memResp.valid := io.memResp.valid
  mshrUnit.io.memResp.bits := io.memResp.bits

  val icacheTag = Seq.fill(TagNumBanks){
    Module(new SinglePortSRAMTemplate(
      gen = Vec(numWays, new ICacheTagEntry),
      set = numSets / TagNumBanks,
      holdRead = true,
    ))
  }

  // 每 bank 是一条 line 的一段。地址是完整 set，不按 tag bank 切开。
  val icacheData = Seq.fill(DataNumBanks){
    Seq.fill(numWays){
      Module(new SinglePortSRAMTemplate(
        gen = new ICacheDataEntry,
        set = numSets,
        holdRead = true,
    ))
    }
  }

  // plru / lru 每个 set 一份。random 全局一份，所有 set 共用，命中不更新。
  val replacers = if (perSetReplacement) {
    Seq.fill(numSets)(ReplacementPolicy(replacement, numWays))
  } else {
    Seq(ReplacementPolicy(replacement, numWays))
  }

  val replaceWayTable = if (perSetReplacement) Some(VecInit(replacers.map(_.getReplaceWayOH))) else None
  val globalWayOH = if (perSetReplacement) None else Some(replacers.head.getReplaceWayOH)

  def replaceWayOH(setIdx: UInt): UInt = {
    if (perSetReplacement) replaceWayTable.get(setIdx) else globalWayOH.get
  }

//------------------refill-------------------------------//

  // s0 读 tag。s1 读数据回来后优先选空 way，否则用替换算法。同拍把这一 way 写入 tag 和 data。
  val refillS0Valid = mshrUnit.io.refill.valid
  val refillS0Req = mshrUnit.io.refill.bits
  val refillS0Bank = getTagBankIndex(refillS0Req.refillInfo.setIdx)
  val refillS0Idx = getTagSetIndex(refillS0Req.refillInfo.setIdx)

  // refill 优先占 tag 端口。同一 bank 的写还没做完时，不再发起下一拍读。
  // s1 valid 要先于 s0 ready 定义。-Xcheckinit 会拒绝构造函数里的向前引用。
  val refillS1Bank = Wire(UInt(TagSetIdxWidth.W))
  val refillS1Valid = RegNext(refillS0Valid, false.B)
  val refillS0Ready = !refillS1Valid || refillS1Bank =/= refillS0Bank
  val refillS0Fire = refillS0Valid && refillS0Ready
  mshrUnit.io.refill.ready := refillS0Ready

  //s1
  // s1 优先级最高无阻塞。
  val refillS1Req = RegEnable(refillS0Req, refillS0Fire)
  val refillS1Info = refillS1Req.refillInfo
  refillS1Bank := getTagBankIndex(refillS1Info.setIdx)
  val refillS1Idx = getTagSetIndex(refillS1Info.setIdx)

  val refillS1Tags = Mux1H(UIntToOH(refillS1Bank), VecInit(icacheTag.map(_.io.rdata)))
  // lookup 已经选定 way 时必须写这一 way，MSHR 的 set/way 碰撞才和真正被换掉的 way 一致。
  val refillWayOH = refillS1Info.wayMask

  assert(
    !(refillS1Valid && refillWayOH.orR && PopCount(refillWayOH) =/= 1.U),
    "refill way must be one-hot",
  )
  val refillTag = Wire(new ICacheTagEntry)
  refillTag.valid := true.B
  refillTag.tag := refillS1Req.pTag

  val refillToLookup = Wire(new ICacheRefillInfo)
  refillToLookup := refillS1Info
  wayLookup.io.icacheRefill.valid := refillS1Valid
  wayLookup.io.icacheRefill.bits := refillToLookup

//------------------lookup-------------------------------// 和FTQ lookup 同拍
//s0
//lookup s0 阶段的阻塞和访问Tag Sram 有关
  val s1Ready = Wire(Bool())
  val s2Ready = Wire(Bool())
  val lookupPC = lookup.lookupPC
  val lookupPtr = lookup.ftqPtr
  val s0Valid = lookupValid
  val s0Vaddr = getVaddr(lookupPC)
  val s0BeatVaddr = getBeatVaddr(s0Vaddr)
  val s0Set0 = getSetIndex(s0Vaddr)
  val s0Set1 = (s0Set0 + 1.U)(SetIdxWidth - 1, 0)
  val s0Line1 = isCrossCacheline(s0Vaddr) && !isCrossPage(lookupPC)
  val s0Bank0 = getTagBankIndex(s0Set0)
  val s0Bank1 = getTagBankIndex(s0Set1)
  val s0Idx0 = getTagSetIndex(s0Set0)
  val s0Idx1 = getTagSetIndex(s0Set1)

  val refillReadBank0 = refillS0Valid && refillS0Bank === s0Bank0
  val refillReadBank1 = refillS0Valid && refillS0Bank === s0Bank1
  val refillWriteBank0 = refillS1Valid && refillS1Bank === s0Bank0
  val refillWriteBank1 = refillS1Valid && refillS1Bank === s0Bank1

  val s0CanGo = !refillReadBank0 && !refillWriteBank0 &&
    (!s0Line1 || (!refillReadBank1 && !refillWriteBank1))
  val s0ValidOut = s0Valid && s0CanGo
  val s0Ready = s0CanGo && s1Ready
  val s0Fire = s0ValidOut && s1Ready
  io.lookup.ready := s0Ready

  assert(!(s0Fire && s0Line1 && s0Bank0 === s0Bank1), "lookup lines hit the same tag bank")
  assert(!(refillS0Fire && refillS1Valid && refillS0Bank === refillS1Bank), "refill reads and writes the same tag bank")
  assert(!(s0Fire && (refillReadBank0 || refillWriteBank0 ||
    (s0Line1 && (refillReadBank1 || refillWriteBank1)))), "lookup and refill use the same tag bank")

//s1
  val s1Valid = RegEnable(s0ValidOut, false.B, s1Ready)
  s1Ready := !s1Valid || flush || s2Ready
  val s1BeatVaddr = RegEnable(s0BeatVaddr, s0Fire)
  val s1Line1 = RegEnable(s0Line1, false.B, s0Fire)

  val s1Vaddr = getVaddr(s1BeatVaddr)
  val s1Set0 = getSetIndex(s1Vaddr)
  val s1Set1 = (s1Set0 + 1.U)(SetIdxWidth - 1, 0)
  val s1Bank0 = getTagBankIndex(s1Set0)
  val s1Bank1 = getTagBankIndex(s1Set1)
  val s1TagRead = VecInit(icacheTag.map(_.io.rdata))
  val s1Tag0Next = Mux1H(UIntToOH(s1Bank0), s1TagRead)
  val s1Tag1Next = Mux1H(UIntToOH(s1Bank1), s1TagRead)
  // s1 可能停住。读回的那一拍把 tag 留下，后面 refill 才能占用这块 SRAM。
  val s1Capture = RegNext(s0Fire, false.B)
  val s1Tag0Hold = RegEnable(s1Tag0Next, s1Capture)
  val s1Tag1Hold = RegEnable(s1Tag1Next, s1Capture)
  val s1Tag0 = Mux(s1Capture, s1Tag0Next, s1Tag0Hold)
  val s1Tag1 = Mux(s1Capture, s1Tag1Next, s1Tag1Hold)
  val s1BeatOff = getBeatOffset(s1Vaddr)
  val s1SetBeat = Cat(s1Set0, s1BeatOff)

  val s1ValidOut = s1Valid && !flush
  val s1Fire =  s1ValidOut && s2Ready
  io.toMMU.itlbReq.valid := s1Fire
  io.toMMU.itlbReq.bits := getVPN(s1Vaddr)

//s2
  val s2Valid = RegEnable(s1ValidOut, false.B, s2Ready)
  // 不带 VPN。虚拟 set 后面只拼 line 内的 beat 位，beat 里的字节偏移不存。
  val s2PushReady = wayLookup.io.lookupPush.ready
  val s2SetBeat = RegEnable(s1SetBeat, s1Fire)
  val s2Line1 = RegEnable(s1Line1, false.B, s1Fire)
  // 暂时先不考虑refill tag 替换的问题
  val s2Tag0 = RegEnable(s1Tag0, s1Fire)
  val s2Tag1 = RegEnable(s1Tag1, s1Fire)
  val s2Tlb = RegEnable(itlbResp, s1Fire)

  val s2Set0 = s2SetBeat(SetIdxWidth + ICacheToL2BeatWidth - 1, ICacheToL2BeatWidth)
  val s2BeatOff = s2SetBeat(ICacheToL2BeatWidth - 1, 0)
  val s2Set1 = (s2Set0 + 1.U)(SetIdxWidth - 1, 0)
  // 暂时先不考虑 就是tag 替换的问题

  val s2Paddr = Cat(s2Tlb.ppn, beatPageOffset(s2Set0, s2BeatOff))
  // 有别名时 tag 是整段 PPN。否则 set 已经落在页内，tag 从 set 上面开始。
  val s2PTag = if (HasVaddrAlias) {
    s2Tlb.ppn
  } else {
    s2Paddr(paddrWidth - 1, SetIdxWidth + OffsetWidth)
  }
  val s2Way0 = VecInit(s2Tag0.map(entry => entry.valid && entry.tag === s2PTag)).asUInt // 命中tag
  val s2Way1 = VecInit(s2Tag1.map(entry => entry.valid && entry.tag === s2PTag)).asUInt // 命中tag
  // s2 停住时 tag 还是进来那拍的。refill 正在写这个 set，就用新 tag 改写比较结果。// 这里需要思考一下
  def victimWay(setIdx: UInt, valids: UInt): UInt = {
    val empty = (~valids)(numWays - 1, 0)
    Mux(empty.orR, LowPriorityOH(empty), replaceWayOH(setIdx))
  }

  val s2Victim0 = victimWay(s2Set0, VecInit(s2Tag0.map(_.valid)).asUInt)
  val s2Victim1 = victimWay(s2Set1, VecInit(s2Tag1.map(_.valid)).asUInt)

  io.toMMU.pmpReq := s2Paddr
  io.toMMU.pmaReq := s2Paddr
  val s2AccessFault = s2Tlb.accessFault || !pmpResp.allowFetch || !pmaResp.instr
  val s2Fault = s2Tlb.pageFault || s2AccessFault

  mshrUnit.io.hazardSet(0) := s2Set0
  mshrUnit.io.hazardSet(1) := s2Set1
  mshrUnit.io.hazardWay(0) := s2Way0
  mshrUnit.io.hazardWay(1) :=  s2Way1
  // 命中的 way 已经挂在忙的 MSHR 上，这条 line 马上被换掉，不能当 hit。
  // todo
  val s2HitCollide0 = mshrUnit.io.hazardCollide(0)
  val s2HitCollide1 = mshrUnit.io.hazardCollide(1)
  val s2Miss0 = (!s2Way0.orR  || s2HitCollide0)
  val s2Miss1 = s2Line1 && (!s2Way1.orR || s2HitCollide1)
  val s2Miss = !s2Fault && pmaResp.cacheable && (s2Miss0 || s2Miss1)


  val mshrCanGo = !s2Miss || mshrUnit.io.lookupReq.ready
  val wayLookupCanGo = s2PushReady
  val tlbCanGo = s2Tlb.hit

  val s2Go = tlbCanGo && wayLookupCanGo && mshrCanGo
  s2Ready := !s2Valid || s2Go || flush

  val s2Fire = s2Valid && !flush && s2Go


  val s2MultiHit = PopCount(s2Way0) > 1.U || (s2Line1 && PopCount(s2Way1) > 1.U)
  assert(!(s2Valid && !flush && s2Tlb.hit && s2MultiHit), "lookup line hits more than one way")

  val mshrReqValid = s2Valid && tlbCanGo && wayLookupCanGo && s2Miss && !flush
  mshrUnit.io.lookupReq.valid := mshrReqValid
  mshrUnit.io.lookupReq.bits.setIdx := Mux(s2Miss0, s2Set0, s2Set1)
  mshrUnit.io.lookupReq.bits.wayMask(0) := Mux(s2Miss0,s2Victim0, s2Victim1)
  mshrUnit.io.lookupReq.bits.wayMask(1) := s2Victim1
  mshrUnit.io.lookupReq.bits.pTag := s2PTag // 不支持跨页 所以Ptag 应该相同
  mshrUnit.io.lookupReq.bits.crossCacheline := s2Miss0 && s2Miss1

  val s2WayLookup = Wire(new WayLookupEntry)
  s2WayLookup.itlbInfo.isNC := !pmaResp.cacheable
  s2WayLookup.itlbInfo.isMMIO := !pmaResp.memory
  s2WayLookup.cacheInfo.vSetIdx := s2Set0
  s2WayLookup.cacheInfo.crossCacheline := s2Line1
  s2WayLookup.cacheInfo.inFlying(0) := s2Miss0
  s2WayLookup.cacheInfo.inFlying(1) := s2Miss1
  s2WayLookup.cacheInfo.wayMask(0) := Mux(s2Miss0, s2Victim0, s2Way0)
  s2WayLookup.cacheInfo.wayMask(1) := Mux(s2Miss1, s2Victim1, s2Way1)
  wayLookup.io.flush := flush
  val wayLookupPushValid = s2Valid && tlbCanGo && mshrCanGo && !flush
  wayLookup.io.lookupPush.valid := wayLookupPushValid
  wayLookup.io.lookupPush.bits.wayLookupInfo := s2WayLookup
  wayLookup.io.lookupPush.bits.exceptInfo := Mux(s2Tlb.pageFault, FrontendExcept.pageFault, Mux(s2AccessFault, FrontendExcept.accessFault, FrontendExcept.none))

//------------------sram---------------------------------//
  icacheTag.zipWithIndex.foreach { case (bank, bankIdx) =>
    val writeThis = refillS1Valid && refillS1Bank === bankIdx.U
    val readRefill = refillS0Fire && refillS0Bank === bankIdx.U
    val readLookup0 = s0Fire && s0Bank0 === bankIdx.U
    val readLookup1 = s0Fire && s0Line1 && s0Bank1 === bankIdx.U
    val wdata = VecInit((0 until numWays).map { wayIdx =>
      Mux(refillWayOH(wayIdx), refillTag, bank.io.rdata(wayIdx))
    })
    bank.io.en := writeThis || readRefill || readLookup0 || readLookup1
    bank.io.wen := writeThis
    bank.io.addr := Mux1H(Seq(
      writeThis -> refillS1Idx,
      readRefill -> refillS0Idx,
      readLookup0 -> s0Idx0,
      readLookup1 -> s0Idx1,
    ))
    bank.io.wdata := wdata

    assert(
      PopCount(Seq(writeThis, readRefill, readLookup0, readLookup1)) <= 1.U,
      s"Bank $bankIdx conflict: writeThis, readRefill, readLookup0, readLookup1 must be one-hot"
    )
  }

  //更新规则可能要考虑一下
  if (perSetReplacement) {
    val hit0En = s2Fire && !s2Fault && pmaResp.cacheable && !s2Miss0
    val hit1En = s2Fire && !s2Fault && pmaResp.cacheable && !s2Miss0 && s2Line1
    replacers.zipWithIndex.foreach { case (repl, idx) =>
      val hit0 = hit0En && s2Set0 === idx.U(SetIdxWidth.W)
      val hit1 = hit1En && s2Set1 === idx.U(SetIdxWidth.W)
      val fill = refillS1Valid && refillS1Info.setIdx === idx.U(SetIdxWidth.W)
      // 命中先碰，refill 后碰，新填上的 line 是最近使用的。
      when (hit0 && fill) {
        repl.updateMany(Seq(s2Way0, refillWayOH))
      }.elsewhen (hit1 && fill) {
        repl.updateMany(Seq(s2Way1, refillWayOH))
      }.elsewhen (hit0) {
        repl.update(s2Way0)
      }.elsewhen (hit1) {
        repl.update(s2Way1)
      }.elsewhen (fill) {
        repl.update(refillWayOH)
      }
    }
  }

//------------------Fetch-------------------------------//
  // 串行：s0 pop wayLookup，只读命中 way。s1 选 bank，s2 选 way，再拼 24B。
  // 窗口卷过本条 line 就是下一条 cacheline，用 set+1 和另一路 way。非 cacheable 先停在 s0。
  require(DataBankBytes == 8 && FetchBankCountMax == 4)
  require(DataWidth * FetchBankCountMax >= InstBitsWidth + (DataBankBytes - MinInstBytes) * 8)

  val fetchS1Ready = Wire(Bool())
  val fetchS2Ready = Wire(Bool())

//s0
  val fetchS0Vaddr = getVaddr(fetch.fetchPC)
  val fetchS0LineOffset = fetchS0Vaddr(OffsetWidth - 1, 0)
  val fetchS0CrossPage = isCrossPage(fetch.fetchPC) // 保留
  val fetchS0Set0 = wayLookupCacheInfo.vSetIdx
  val fetchS0Set1 = (fetchS0Set0 + 1.U)(SetIdxWidth - 1, 0)
  val fetchS0CrossLine = wayLookupCacheInfo.crossCacheline && !fetchS0CrossPage
  val fetchS0Inflight = wayLookupCacheInfo.inFlying(0) ||
    (fetchS0CrossLine && wayLookupCacheInfo.inFlying(1))
  val fetchS0Read = wayLookupPopValid && wayLookupCacheable && !wayLookupIsException && !fetchS0Inflight

  // 64B line 切成 8 个 8B bank。起始 bank 决定从哪一块开始读，bank 内偏移决定要不要多读一块。
  val fetchS0StartBank = fetchS0LineOffset(OffsetWidth - 1, DataBankOffWidth)
  val fetchS0ByteInBank = fetchS0LineOffset(DataBankOffWidth - 1, 0)
  val fetchBankCountWidth = log2Ceil(DataNumBanks + 1)
  val fetchS0BanksToLineEnd = DataNumBanks.U(fetchBankCountWidth.W) - fetchS0StartBank.pad(fetchBankCountWidth)
  val fetchS0NeedBankCount = Mux(
    fetchS0ByteInBank === 0.U,
    FetchBankCount.U(fetchBankCountWidth.W),
    FetchBankCountMax.U(fetchBankCountWidth.W),
  )
  val fetchS0ReadBankCount = Mux(fetchS0CrossPage, fetchS0BanksToLineEnd, fetchS0NeedBankCount)

  val fetchS0SlotEn = Wire(Vec(FetchBankCountMax, Bool()))
  val fetchS0SlotBank = Wire(Vec(FetchBankCountMax, UInt(DataBankIdxWidth.W)))
  val fetchS0SlotWay = Wire(Vec(FetchBankCountMax, UInt(numWays.W)))
  val fetchS0SlotAddr = Wire(Vec(FetchBankCountMax, UInt(SetIdxWidth.W)))

  for (slotIdx <- 0 until FetchBankCountMax) {
    val startBankPlusSlot = Cat(0.U(1.W), fetchS0StartBank) + slotIdx.U((DataBankIdxWidth + 1).W)
    val bank = startBankPlusSlot(DataBankIdxWidth - 1, 0)
    val en = fetchS0Read && slotIdx.U(fetchBankCountWidth.W) < fetchS0ReadBankCount
    val onNextLine = en && bank < fetchS0StartBank
    fetchS0SlotEn(slotIdx) := en
    fetchS0SlotBank(slotIdx) := bank
    fetchS0SlotWay(slotIdx) := Mux(onNextLine, wayLookupCacheInfo.wayMask(1), wayLookupCacheInfo.wayMask(0))
    fetchS0SlotAddr(slotIdx) := Mux(onNextLine, fetchS0Set1, fetchS0Set0)
  }

  val fetchS0BankEn = Wire(Vec(DataNumBanks, Bool()))
  val fetchS0BankAddr = Wire(Vec(DataNumBanks, UInt(SetIdxWidth.W)))
  val fetchS0BankWay = Wire(Vec(DataNumBanks, UInt(numWays.W)))

  for (bankIdx <- 0 until DataNumBanks) {
    val hit = (0 until FetchBankCountMax).map { slotIdx =>
      fetchS0SlotEn(slotIdx) && fetchS0SlotBank(slotIdx) === bankIdx.U
    }
    fetchS0BankEn(bankIdx) := hit.reduce(_ || _)
    fetchS0BankAddr(bankIdx) := Mux1H(hit, (0 until FetchBankCountMax).map(fetchS0SlotAddr(_)))
    fetchS0BankWay(bankIdx) := Mux1H(hit, (0 until FetchBankCountMax).map(fetchS0SlotWay(_)))
    assert(PopCount(hit) <= 1.U, "a fetch window hits one data bank twice")
  }



  val fetchS0Conflict = refillS1Valid && VecInit((0 until DataNumBanks).map { bankIdx =>
    fetchS0BankEn(bankIdx) && (fetchS0BankWay(bankIdx) & refillWayOH).orR
  }).asUInt.orR
  val fetchS0CanGo = !fetch.parallel && wayLookupPopValid && (
    wayLookupIsException || (wayLookupCacheable && !fetchS0Inflight && !fetchS0Conflict)
  )
  val fetchS0Fire = io.fetch.fire
  io.fetch.ready := fetchS0CanGo && fetchS1Ready

  icacheData.zipWithIndex.foreach { case (bank, bankIdx) =>
    val refillData = refillS1Req.data((bankIdx + 1) * DataWidth - 1, bankIdx * DataWidth)
    val readEn = fetchS0Fire && fetchS0BankEn(bankIdx)
    val readWay = fetchS0BankWay(bankIdx)
    val readAddr = fetchS0BankAddr(bankIdx)
    bank.zipWithIndex.foreach { case (way, wayIdx) =>
      val write = refillS1Valid && refillWayOH(wayIdx)
      val read = readEn && readWay(wayIdx)
      way.io.en := write || read
      way.io.wen := write
      way.io.addr := Mux(write, refillS1Info.setIdx, readAddr)
      way.io.wdata.data := refillData
      assert(!(write && read), "fetch read and refill write hit the same data SRAM")
    }
  }

//s1  bank
  val fetchS1Valid = RegEnable(fetchS0Fire, false.B, fetchS1Ready)
  fetchS1Ready := !fetchS1Valid || flush || fetchS2Ready
  val fetchS1Ftq = RegEnable(fetch.ftqPtr, fetchS0Fire)
  val fetchS1Except = RegEnable(wayLookupExceptInfo, fetchS0Fire)
  val fetchS1ByteInBank = RegEnable(fetchS0ByteInBank, fetchS0Fire)
  val fetchS1SlotEn = RegEnable(fetchS0SlotEn, fetchS0Fire)
  val fetchS1SlotBank = RegEnable(fetchS0SlotBank, fetchS0Fire)
  val fetchS1SlotWay = RegEnable(fetchS0SlotWay, fetchS0Fire)
  val fetchS1Rdata = VecInit(icacheData.map { bank =>
    VecInit(bank.map(_.io.rdata.data))
  })
  val fetchS1Piece = Wire(Vec(FetchBankCountMax, Vec(numWays, UInt(DataWidth.W))))
  for (slotIdx <- 0 until FetchBankCountMax) {
    val bankData = Mux1H(UIntToOH(fetchS1SlotBank(slotIdx)), fetchS1Rdata)
    for (wayIdx <- 0 until numWays) {
      fetchS1Piece(slotIdx)(wayIdx) := Mux(fetchS1SlotEn(slotIdx), bankData(wayIdx), 0.U(DataWidth.W))
    }
  }
  val fetchS1Fire = fetchS1Valid && fetchS2Ready && !flush

//s2  way
  val fetchS2Valid = RegEnable(fetchS1Fire, false.B, fetchS2Ready)
  val fetchS2Ftq = RegEnable(fetchS1Ftq, fetchS1Fire)
  val fetchS2Except = RegEnable(fetchS1Except, fetchS1Fire)
  val fetchS2ByteInBank = RegEnable(fetchS1ByteInBank, fetchS1Fire)
  val fetchS2SlotWay = RegEnable(fetchS1SlotWay, fetchS1Fire)
  val fetchS2Piece = RegEnable(fetchS1Piece, fetchS1Fire)
  fetchS2Ready := !fetchS2Valid || flush || io.fetchResp.ready

  val fetchS2Data = VecInit((0 until FetchBankCountMax).map { slotIdx =>
    Mux1H(fetchS2SlotWay(slotIdx), (0 until numWays).map(fetchS2Piece(slotIdx)(_)))
  })
  val fetchS2Raw = fetchS2Data.reverse.reduce((hi, lo) => Cat(hi, lo))
  val fetchS2Window = MuxLookup(fetchS2ByteInBank(2, 1), 0.U(InstBitsWidth.W))(Seq(
    0.U(2.W) -> fetchS2Raw(InstBitsWidth - 1, 0),
    1.U(2.W) -> fetchS2Raw(16 + InstBitsWidth - 1, 16),
    2.U(2.W) -> fetchS2Raw(32 + InstBitsWidth - 1, 32),
    3.U(2.W) -> fetchS2Raw(48 + InstBitsWidth - 1, 48),
  ))
  val fetchS2Inst = Wire(Vec(FetchBlockNumSlots, UInt((MinInstBytes * 8).W)))
  for (slotIdx <- 0 until FetchBlockNumSlots) {
    fetchS2Inst(slotIdx) := fetchS2Window(slotIdx * 16 + 15, slotIdx * 16)
  }

//output
  wayLookup.io.fetchPop.ready := fetchS0Fire
  io.fetchS0Fire := fetchS0Fire
  io.fetchS1Fire := fetchS1Fire
  io.fetchResp.valid := fetchS2Valid && !flush
  io.fetchResp.bits.ftqPtr := fetchS2Ftq
  io.fetchResp.bits.inst := fetchS2Inst
  io.fetchResp.bits.except := fetchS2Except

  io.memReq.valid := mshrUnit.io.memReq.valid
  io.memReq.bits := mshrUnit.io.memReq.bits
  io.memResp.ready := mshrUnit.io.memResp.ready

}
