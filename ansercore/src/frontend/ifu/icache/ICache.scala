package ansercore.frontend.ifu.icache

import chisel3._
import chisel3.util._

import utils.ReplacementPolicy
import utils.SinglePortSRAMTemplate

import ansercore.FrontendToMMUBundle
import ansercore.MMUToFrontendIO
import ansercore.frontend.ftq.FTQLookup
import ansercore.frontend.ftq.FTQFetch
import ansercore.frontend.FrontendHelper
import ansercore.PrivMode
import ansercore.frontend.ftq.FTQPtr


class ICache extends ICacheModule with ICacheHelper with FrontendHelper{
  class ICacheIO extends ICacheBundle{
    val privMode = Input( PrivMode() )
    val flush = Input(Bool())
    val fencei = Input(Bool())
    //lookup
    val bpuOverride = Flipped(Valid(new FTQPtr))
    val lookup = Flipped(Decoupled(new FTQLookup))

    //MMU
    val toMMU = Output(new FrontendToMMUBundle)
    val fromMMU = Input(new MMUToFrontendIO)
    //fetch
    val fetch = Flipped(Decoupled(new FTQFetch)) // fetch s0
    val fetchResp = Decoupled(new ICacheFetchResp) // fetch s2
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
  mshrUnit.io.flush := flush
  io.memReq.valid := mshrUnit.io.memReq.valid
  io.memReq.bits := mshrUnit.io.memReq.bits
  mshrUnit.io.memReq.ready := io.memReq.ready
  mshrUnit.io.memResp.valid := io.memResp.valid
  mshrUnit.io.memResp.bits := io.memResp.bits
  io.memResp.ready := mshrUnit.io.memResp.ready

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

  def setAlias(setIdx: UInt): UInt = {
    if (HasVaddrAlias) setIdx(SetIdxWidth - 1, SetIdxWidth - VaddrAliasWidth)
    else 0.U(VaddrAliasWidth.W)
  }

//------------------refill-------------------------------//
  // s0 读 tag。s1 读数据回来后优先选空 way，否则用替换算法。同拍把这一 way 写入 tag 和 data。
  val refillValid = mshrUnit.io.refill.valid
  val refillReq = mshrUnit.io.refill.bits
  val refillS0Bank = getTagBankIndex(refillReq.setIdx)
  val refillS0Idx = getTagSetIndex(refillReq.setIdx)

  val refillS1Valid = RegInit(false.B)
  val refillS1 = RegInit(0.U.asTypeOf(new ICacheRefillReq))
  val refillS1Bank = getTagBankIndex(refillS1.setIdx)
  val refillS1Idx = getTagSetIndex(refillS1.setIdx)
  // refill 优先占 tag 端口。同一 bank 的写还没做完时，不再发起下一拍读。
  val refillS0Ready = !refillS1Valid || refillS1Bank =/= refillS0Bank
  val refillS0Fire = refillValid && refillS0Ready
  mshrUnit.io.refill.ready := refillS0Ready
  when (refillS0Fire) {
    refillS1 := refillReq
  }
  refillS1Valid := refillS0Fire

  val refillS1Tags = Mux1H(UIntToOH(refillS1Bank), VecInit(icacheTag.map(_.io.rdata)))
  val refillEmptyOH = VecInit(refillS1Tags.map(entry => !entry.valid)).asUInt
  val refillWayOH = Mux(refillEmptyOH.orR, PriorityEncoderOH(refillEmptyOH), replaceWayOH(refillS1.setIdx))
  val refillTag = Wire(new ICacheTagEntry)
  refillTag.valid := true.B
  refillTag.tag := refillS1.pTag

  val refillToLookup = Wire(new ICacheRefillInfo)
  refillToLookup.setIdx := refillS1.setIdx
  refillToLookup.pTag := refillS1.pTag
  refillToLookup.wayMask := refillWayOH
  wayLookup.io.icacheRefill.valid := refillS1Valid
  wayLookup.io.icacheRefill.bits := refillToLookup

//------------------lookup-------------------------------// 和FTQ lookup 同拍
//s0
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

  val refillReadBank0 = refillS0Fire && refillS0Bank === s0Bank0
  val refillReadBank1 = refillS0Fire && refillS0Bank === s0Bank1
  val refillWriteBank0 = refillS1Valid && refillS1Bank === s0Bank0
  val refillWriteBank1 = refillS1Valid && refillS1Bank === s0Bank1
  val s0CanGo = !refillReadBank0 && !refillWriteBank0 &&
    (!s0Line1 || (!refillReadBank1 && !refillWriteBank1))
  val s0ValidOut = s0Valid && s0CanGo
  val s0Ready = s0CanGo && s1Ready
  val s0Fire = s0ValidOut && s1Ready
  io.lookup.ready := s0Ready

  assert(!(s0Fire && s0Line1 && s0Bank0 === s0Bank1), "lookup 的两条 line 打到同一个 tag bank")
  assert(!(refillS0Fire && refillS1Valid && refillS0Bank === refillS1Bank), "refill 同时读写同一个 tag bank")
  assert(!(s0Fire && (refillReadBank0 || refillWriteBank0 ||
    (s0Line1 && (refillReadBank1 || refillWriteBank1)))), "lookup 和 refill 抢同一个 tag bank")

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
  io.toMMU.itlbReq.valid := s1Valid
  io.toMMU.itlbReq.bits.vpn := getVPN(s1Vaddr)

  val s1ValidOut = s1Valid && !flush
  val s1Fire =  s1ValidOut && s2Ready
//s2
  val s2Valid = RegEnable(s1ValidOut, false.B, s2Ready)
  // 不带 VPN。虚拟 set 后面只拼 line 内的 beat 位，beat 里的字节偏移不存。
  val s2SetBeat = RegEnable(s1SetBeat, s1Fire)
  val s2Line1 = RegEnable(s1Line1, false.B, s1Fire)
  val s2Tag0 = RegEnable(s1Tag0, s1Fire)
  val s2Tag1 = RegEnable(s1Tag1, s1Fire)
  val s2Tlb = RegEnable(itlbResp, s1Fire)

  val s2Set0 = s2SetBeat(SetIdxWidth + ICacheToL2BeatWidth - 1, ICacheToL2BeatWidth)
  val s2BeatOff = s2SetBeat(ICacheToL2BeatWidth - 1, 0)
  val s2Set1 = (s2Set0 + 1.U)(SetIdxWidth - 1, 0)
  val s2Paddr = Cat(s2Tlb.ppn, beatPageOffset(s2Set0, s2BeatOff))
  val s2PTag = s2Paddr(paddrWidth - 1, SetIdxWidth + OffsetWidth)
  val s2Way0 = VecInit(s2Tag0.map(entry => entry.valid && entry.tag === s2PTag)).asUInt
  val s2Way1 = VecInit(s2Tag1.map(entry => entry.valid && entry.tag === s2PTag)).asUInt
  io.toMMU.pmpReq.pAddr := s2Paddr
  io.toMMU.pmpReq.privMode := io.privMode
  io.toMMU.pmaReq.addr := s2Paddr
  val s2AccessFault = s2Tlb.accessFault || !pmpResp.allowFetch || !pmaResp.instr
  val s2Fault = s2Tlb.pageFault || s2AccessFault
  val s2Miss0 = s2Tlb.hit && !s2Fault && !s2Way0.orR && pmaResp.cacheable
  val s2Miss1 = s2Line1 && s2Tlb.hit && !s2Fault && !s2Way1.orR && pmaResp.cacheable
  val s2NeedMiss = s2Valid && (s2Miss0 || s2Miss1)
  val s2Line0Beat = s2Paddr(paddrWidth - 1, ICacheToL2AddrOffsetWidth)
  val s2Line1Beat = Cat(
    (s2Paddr(paddrWidth - 1, OffsetWidth) + 1.U)(paddrWidth - OffsetWidth - 1, 0),
    0.U(ICacheToL2BeatWidth.W)
  )
  val s2Go = s2Tlb.hit && (!s2NeedMiss || mshrUnit.io.lookupReq.ready)
  s2Ready := !s2Valid || s2Go || flush

  val s2Fire = s2Valid && !flush && s2Go
  val s2MultiHit = PopCount(s2Way0) > 1.U || (s2Line1 && PopCount(s2Way1) > 1.U)
  assert(!(s2Valid && !flush && s2Tlb.hit && s2MultiHit), "lookup 同一条 line 命中多个 way")

  mshrUnit.io.lookupReq.valid := s2NeedMiss && !flush
  mshrUnit.io.lookupReq.bits.beatPAddr := Mux(s2Miss0, s2Line0Beat, s2Line1Beat)
  mshrUnit.io.lookupReq.bits.vAlias := Mux(s2Miss0, setAlias(s2Set0), setAlias(s2Set1))
  mshrUnit.io.lookupReq.bits.crossCacheline := s2Miss0 && s2Miss1

  val s2WayLookup = Wire(new WayLookupEntry)
  s2WayLookup.itlbInfo.pTag := s2PTag
  s2WayLookup.itlbInfo.isNC := !pmaResp.cacheable
  s2WayLookup.itlbInfo.isMMIO := !pmaResp.memory
  s2WayLookup.cacheInfo.vSetIdx := s2Set0
  s2WayLookup.cacheInfo.crossCacheline := s2Line1
  s2WayLookup.cacheInfo.wayMask(0) := s2Way0
  s2WayLookup.cacheInfo.wayMask(1) := s2Way1
  wayLookup.io.flush := flush
  wayLookup.io.lookupPush.valid := s2Fire
  wayLookup.io.lookupPush.bits.wayLookupInfo := s2WayLookup
  wayLookup.io.lookupPush.bits.exceptInfo.pageFault := s2Tlb.pageFault
  wayLookup.io.lookupPush.bits.exceptInfo.accessFault := s2AccessFault

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
      s"Bank $bankIdx 读写冲突：writeThis, readRefill, readLookup0, readLookup1 最多只能有一个有效！"
    )
  }


  if (perSetReplacement) {
    val hit0En = s2Fire && PopCount(s2Way0) === 1.U
    val hit1En = s2Fire && s2Line1 && PopCount(s2Way1) === 1.U
    replacers.zipWithIndex.foreach { case (repl, idx) =>
      val hit0 = hit0En && s2Set0 === idx.U(SetIdxWidth.W)
      val hit1 = hit1En && s2Set1 === idx.U(SetIdxWidth.W)
      val fill = refillS1Valid && refillS1.setIdx === idx.U(SetIdxWidth.W)
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

//------------------Fetch-------------------------------// 和FTQ fetch 同拍
  // 并行时 s0 读所需 bank 的全部 way，只有 refill 写 data 撞到这些 bank 才停。
  // s1 跟 lookup 的 s1 走。s2 才 pop wayLookup，按 way 选出指令。
  // 串行时 s0 就 pop wayLookup，只读命中的 way。同拍 s2 也 pop 的话，s2 拿队头。

  def wayLine(way: Int): UInt = {
    Cat((DataNumBanks - 1 to 0 by -1).map(bank => icacheData(bank)(way).io.rdata.data))
  }

  def takeWindow(line: UInt, byteOff: UInt): UInt = {
    (line >> (byteOff << 3))(InstBitsWidth - 1, 0)
  }

  def stitch(lo: UInt, hi: UInt, loBytes: UInt): UInt = {
    lo | (hi << (loBytes << 3))
  }

  def missReq(line0Miss: Bool, line1Miss: Bool, pTag: UInt, setIdx: UInt, lineOff: UInt) = {
    val req = Wire(new ICacheMissReq)
    val paddr = Cat(pTag, setIdx, lineOff)
    val line0Beat = paddr(paddrWidth - 1, ICacheToL2AddrOffsetWidth)
    val line1Beat = Cat(
      (paddr(paddrWidth - 1, OffsetWidth) + 1.U)(paddrWidth - OffsetWidth - 1, 0),
      0.U(ICacheToL2BeatWidth.W),
    )
    val line1Set = (setIdx + 1.U)(SetIdxWidth - 1, 0)
    req.beatPAddr := Mux(line0Miss, line0Beat, line1Beat)
    req.vAlias := setAlias(Mux(line0Miss, setIdx, line1Set))
    req.crossCacheline := line0Miss && line1Miss
    req
  }

//s0
  val f1Ready = Wire(Bool())
  val f2Fire = Wire(Bool())
  val doRead = Wire(Bool())
  val f1OlderParallel = Wire(Bool())
  val f2OlderParallel = Wire(Bool())
  val f2Pop = Wire(Bool())
  val sendF0 = Wire(Bool())
  // s2 优先 pop 队头。先连上，同拍 s0 看到的才是下一条。
  wayLookup.io.s2Pop := f2Pop

  val f0Vaddr = getVaddr(fetch.fetchPC)
  val f0Span = fetchWindow(f0Vaddr, isCrossPage(fetch.fetchPC))
  val f0Packet = wayLookup.io.s0Entry
  val f0Info = f0Packet.bits.wayLookupInfo
  val f0OlderHolds = f1OlderParallel || (f2OlderParallel && !f2Fire)
  val f0Except = f0Packet.bits.exceptInfo.pageFault || f0Packet.bits.exceptInfo.accessFault
  val f0Special = !fetch.parallel && f0Packet.valid && !f0OlderHolds &&
    (f0Except || f0Info.itlbInfo.isNC || f0Info.itlbInfo.isMMIO)
  val f0Trust1 = f0Span.crossCacheline && f0Info.cacheInfo.crossCacheline
  val serialWay0 = Mux(f0Packet.valid, f0Info.cacheInfo.wayMask(0), 0.U)
  val serialWay1 = Mux(f0Packet.valid && f0Trust1, f0Info.cacheInfo.wayMask(1), 0.U)
  val allWay = Fill(numWays, true.B).asUInt
  val f0ReadWay0 = Mux(fetch.parallel, allWay, Mux(f0Special, 0.U, serialWay0))
  val f0ReadWay1 = Mux(fetch.parallel, Mux(f0Span.crossCacheline, allWay, 0.U), Mux(f0Special, 0.U, serialWay1))
  val f0Need0 = f0Span.bankMask(0).orR
  val f0Need1 = f0Span.bankMask(1).orR
  val f0Miss = !fetch.parallel && f0Packet.valid && !f0OlderHolds && !f0Special &&
    ((f0Need0 && !serialWay0.orR) || (f0Need1 && !serialWay1.orR))
  val writeWay = Mux(refillS1Valid, refillWayOH, 0.U(numWays.W))
  // refill 会写满选中 way 的每个 bank。并行要读全部 way，所以这时整拍都让开。
  val f0BankConflict =
    (f0Need0 && (f0ReadWay0 & writeWay).orR) || (f0Need1 && (f0ReadWay1 & writeWay).orR)
  val f0SerialOK = f0Packet.valid && !f0OlderHolds && !f0Miss
  // 并行这拍 lookup 还没进 s0 的话不能先读，否则 way 还没产生 fetch 已经跑到前面。
  val f0Align = !fetch.parallel || s0Fire
  val f0Ready = !flush && !f0BankConflict && !doRead && f0Align && (fetch.parallel || f0SerialOK) && f1Ready
  val f0Fire = fetchValid && f0Ready
  io.fetch.ready := f0Ready
  wayLookup.io.s0Pop := f0Fire && !fetch.parallel

  val f0Meta = Wire(new ICacheFetchMeta)
  f0Meta.ftqPtr := fetch.ftqPtr
  f0Meta.endOffset := f0Span.endOffset
  f0Meta.parallel := fetch.parallel
  f0Meta.lineOff := f0Span.lineOff
  f0Meta.lineBytes := f0Span.lineBytes
  f0Meta.crossCacheline := f0Span.crossCacheline
  f0Meta.bankMask := f0Span.bankMask
  f0Meta.setIdx := getSetIndex(f0Vaddr)
  f0Meta.wayMask(0) := serialWay0
  f0Meta.wayMask(1) := serialWay1
  f0Meta.pTag := f0Info.itlbInfo.pTag
  f0Meta.except := f0Packet.bits.exceptInfo
  f0Meta.isNC := f0Info.itlbInfo.isNC
  f0Meta.isMMIO := f0Info.itlbInfo.isMMIO

  val f0Miss0 = f0Need0 && !serialWay0.orR
  val f0Miss1 = f0Need1 && !serialWay1.orR
  val f0MissBits = missReq(f0Miss0, f0Miss1, f0Info.itlbInfo.pTag, f0Meta.setIdx, f0Meta.lineOff)
  val f0MissSent = RegInit(false.B)
  when (flush || !f0Miss) {
    f0MissSent := false.B
  }.elsewhen (sendF0 && mshrUnit.io.fetchReq.fire) {
    f0MissSent := true.B
  }

  when (f0Fire && !fetch.parallel && !f0Special) {
    when (f0Need0) { assert(PopCount(serialWay0) === 1.U, "serial fetch line0 应该只命中一个 way") }
    when (f0Need1) { assert(PopCount(serialWay1) === 1.U, "serial fetch line1 应该只命中一个 way") }
  }

//s1
  // s1 和 lookup s1 对齐。lookup 先走掉的话，下一拍 way 已经进队列，不必再等。
  val f2Ready = Wire(Bool())
  val f1Valid = RegEnable(f0Fire, false.B, f1Ready)
  val f1Meta = RegEnable(f0Meta, f0Fire)
  val f1MissedSync = RegInit(false.B)
  f1OlderParallel := f1Valid && f1Meta.parallel
  val f1Sync = !f1Meta.parallel || s1Fire || f1MissedSync
  val f1CanGo = f1Sync && f2Ready
  f1Ready := !f1Valid || flush || f1CanGo
  val f1Fire = f1Valid && f1CanGo && !flush

  val f1Capture = RegNext(f0Fire, false.B)
  val f1Raw = Wire(Vec(2, Vec(numWays, UInt(InstBitsWidth.W))))
  for (way <- 0 until numWays) {
    val line = wayLine(way)
    f1Raw(0)(way) := takeWindow(line, f1Meta.lineOff)
    f1Raw(1)(way) := takeWindow(line, 0.U)
  }
  val f1Hold = RegEnable(f1Raw, f1Capture)
  val f1Line = Mux(f1Capture, f1Raw, f1Hold)

  when (flush || f0Fire) {
    f1MissedSync := false.B
  }.elsewhen (f1Valid && f1Meta.parallel && s1Fire && !f1Fire) {
    f1MissedSync := true.B
  }

//s2
  val missOff = 0.U(2.W)
  val missWait = 1.U(2.W)
  val missRead = 2.U(2.W)
  val missUse = 3.U(2.W)
  val missState = RegInit(missOff)
  val f2Valid = RegEnable(f1Fire, false.B, f2Ready)
  val f2Meta = RegEnable(f1Meta, f1Fire)
  val f2Line = RegEnable(f1Line, f1Fire)
  val f2MissSent = RegInit(false.B)
  f2OlderParallel := f2Valid && f2Meta.parallel

  val f2Packet = wayLookup.io.s2Entry
  val f2Info = f2Packet.bits.wayLookupInfo
  val f2WayKnown = !f2Meta.parallel || f2Packet.valid
  val f2Except = Mux(
    f2Meta.parallel,
    f2Packet.bits.exceptInfo.pageFault || f2Packet.bits.exceptInfo.accessFault,
    f2Meta.except.pageFault || f2Meta.except.accessFault,
  )
  val f2NC = Mux(f2Meta.parallel, f2Info.itlbInfo.isNC, f2Meta.isNC)
  val f2MMIO = Mux(f2Meta.parallel, f2Info.itlbInfo.isMMIO, f2Meta.isMMIO)
  val f2Special = f2WayKnown && (f2Except || f2NC || f2MMIO)
  // 重新算过的跨 line 才信 wayMask(1)。lookup 没读过的第二条不能用。
  val f2Trust1 = f2Meta.crossCacheline && f2Info.cacheInfo.crossCacheline
  val pipeWay0 = Mux(f2Meta.parallel, Mux(f2WayKnown, f2Info.cacheInfo.wayMask(0), 0.U), f2Meta.wayMask(0))
  val pipeWay1 = Mux(
    f2Meta.parallel,
    Mux(f2WayKnown && f2Trust1, f2Info.cacheInfo.wayMask(1), 0.U),
    f2Meta.wayMask(1),
  )
  val f2Need0 = f2Meta.bankMask(0).orR
  val f2Need1 = f2Meta.bankMask(1).orR
  val f2CacheMiss = f2WayKnown && !f2Special && ((f2Need0 && !pipeWay0.orR) || (f2Need1 && !pipeWay1.orR))
  val rereadConflict = VecInit((0 until DataNumBanks).flatMap { bank =>
    (0 until numWays).map { way =>
      val want = Mux(f2Meta.bankMask(0)(bank), pipeWay0(way), false.B) ||
        Mux(f2Meta.bankMask(1)(bank), pipeWay1(way), false.B)
      want && refillS1Valid && refillWayOH(way)
    }
  }).asUInt.orR
  doRead := f2Valid && !flush && missState === missRead && !rereadConflict
  val f2FillWay0 = RegEnable(pipeWay0, doRead)
  val f2FillWay1 = RegEnable(pipeWay1, doRead)
  val rereadCapture = RegNext(doRead, false.B)
  val rereadRaw = Wire(Vec(2, Vec(numWays, UInt(InstBitsWidth.W))))
  for (way <- 0 until numWays) {
    val line = wayLine(way)
    rereadRaw(0)(way) := takeWindow(line, f2Meta.lineOff)
    rereadRaw(1)(way) := takeWindow(line, 0.U)
  }
  val rereadHold = RegEnable(rereadRaw, rereadCapture)
  val rereadLine = Mux(rereadCapture, rereadRaw, rereadHold)
  val useReread = missState === missUse
  val useWay0 = Mux(useReread, f2FillWay0, pipeWay0)
  val useWay1 = Mux(useReread, f2FillWay1, pipeWay1)
  val useLine = Mux(useReread, rereadLine, f2Line)
  val instBits = Mux(
    f2Special && !useReread,
    0.U(InstBitsWidth.W),
    stitch(Mux1H(useWay0, useLine(0)), Mux1H(useWay1, useLine(1)), f2Meta.lineBytes(0)),
  )
  val inst = Wire(Vec(FetchBlockNumSlots, UInt((MinInstBytes * 8).W)))
  for (slot <- 0 until FetchBlockNumSlots) {
    val lo = slot * MinInstBytes * 8
    val inWindow = slot.U <= f2Meta.endOffset
    inst(slot) := Mux(inWindow, instBits(lo + MinInstBytes * 8 - 1, lo), 0.U)
  }
  val f2HasData = missState === missUse || (missState === missOff && f2WayKnown && !f2CacheMiss)
  val f2CanResp = f2HasData && io.fetchResp.ready
  f2Ready := !f2Valid || flush || f2CanResp
  f2Fire := f2Valid && f2CanResp && !flush
  f2Pop := f2Fire && f2Meta.parallel

  when (flush) {
    missState := missOff
  }.elsewhen (f2Fire || (f2Valid && missState === missOff && f2CacheMiss)) {
    missState := Mux(f2Fire, missOff, missWait)
  }.elsewhen (missState === missWait && !f2CacheMiss && !rereadConflict) {
    missState := missRead
  }.elsewhen (missState === missRead && !rereadConflict) {
    missState := missUse
  }

  val f2WantMiss = f2Valid && !flush && f2CacheMiss && (missState === missOff || missState === missWait)
  val f2Tag = Mux(f2Meta.parallel, f2Info.itlbInfo.pTag, f2Meta.pTag)
  val f2Miss0 = f2Need0 && !pipeWay0.orR
  val f2Miss1 = f2Need1 && !pipeWay1.orR
  val f2MissBits = missReq(f2Miss0, f2Miss1, f2Tag, f2Meta.setIdx, f2Meta.lineOff)
  val sendF2 = f2WantMiss && !f2MissSent
  sendF0 := f0Miss && !f0MissSent && !sendF2
  mshrUnit.io.fetchReq.valid := sendF2 || sendF0
  mshrUnit.io.fetchReq.bits := Mux(sendF2, f2MissBits, f0MissBits)
  when (flush || !f2WantMiss) {
    f2MissSent := false.B
  }.elsewhen (sendF2 && mshrUnit.io.fetchReq.fire) {
    f2MissSent := true.B
  }

  io.fetchResp.valid := f2Valid && f2HasData && !flush
  io.fetchResp.bits.ftqPtr := f2Meta.ftqPtr
  io.fetchResp.bits.endOffset := f2Meta.endOffset
  io.fetchResp.bits.inst := inst

  when (f2Valid && f2HasData && !f2Special && !flush) {
    when (f2Need0) { assert(PopCount(useWay0) === 1.U, "fetch line0 命中多个 way") }
    when (f2Need1) { assert(PopCount(useWay1) === 1.U, "fetch line1 命中多个 way") }
  }
  assert(!(f2Valid && !flush && !f2Meta.parallel && f2CacheMiss), "serial fetch 进管道后不应该再 miss")

  icacheData.zipWithIndex.foreach { case (banks, bankIdx) =>
    val low = bankIdx * DataWidth
    val slice = refillS1.data(low + DataWidth - 1, low)
    val f0Set1 = (f0Meta.setIdx + 1.U)(SetIdxWidth - 1, 0)
    val f2Set1 = (f2Meta.setIdx + 1.U)(SetIdxWidth - 1, 0)
    val f0Addr = Mux(f0Span.bankMask(0)(bankIdx), f0Meta.setIdx, f0Set1)
    val f2Addr = Mux(f2Meta.bankMask(0)(bankIdx), f2Meta.setIdx, f2Set1)
    banks.zipWithIndex.foreach { case (sram, wayIdx) =>
      val writeThis = refillS1Valid && refillWayOH(wayIdx)
      val f0Want = Mux(f0Span.bankMask(0)(bankIdx), f0ReadWay0(wayIdx), false.B) ||
        Mux(f0Span.bankMask(1)(bankIdx), f0ReadWay1(wayIdx), false.B)
      val f2Want = Mux(f2Meta.bankMask(0)(bankIdx), pipeWay0(wayIdx), false.B) ||
        Mux(f2Meta.bankMask(1)(bankIdx), pipeWay1(wayIdx), false.B)
      val readF0 = f0Fire && f0Want
      val readF2 = doRead && f2Want
      sram.io.en := writeThis || readF0 || readF2
      sram.io.wen := writeThis
      sram.io.addr := Mux1H(Seq(
        writeThis -> refillS1.setIdx,
        readF0 -> f0Addr,
        readF2 -> f2Addr,
      ))
      sram.io.wdata.data := slice
      assert(
        PopCount(Seq(writeThis, readF0, readF2)) <= 1.U,
        s"data bank $bankIdx way $wayIdx 读写冲突",
      )
    }
  }

}
