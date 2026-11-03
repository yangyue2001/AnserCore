package ansercore.frontend.ifu.icache

import chisel3._
import chisel3.util._
import utils.Mux1H

// 一次请求固定返回两个 beat。addr 的低位是关键字 beat，不强制为 0。
// 同一个 id 先回关键字 beat，再回另一拍。last=1 表示第二拍。
// id 最高位是 line：slot0 为 0，slot1 为 1。其余位是 MSHR 编号。
// 不同 id 可以乱序结束。
class ICacheMemReq extends ICacheBundle {
  val id = UInt((log2Ceil(NumMshrs) + 1).W)
  val addr = UInt(ICacheToL2BeatAddrWidth.W)
}

class ICacheMemResp extends ICacheBundle {
  val id = UInt((log2Ceil(NumMshrs) + 1).W)
  val data = UInt(ICacheToL2DataWidth.W)
  val last = Bool()
}

class ICacheMissUnit extends ICacheModule with ICacheHelper {
  class ICacheMissUnitIO extends ICacheBundle {
    val flush = Input(Bool())
    val lookupReq = Flipped(Decoupled(new ICacheMissReq))
    val fetchReq = Flipped(Decoupled(new ICacheMissReq))
    val memReq = Decoupled(new ICacheMemReq)
    val memResp = Flipped(Decoupled(new ICacheMemResp))
    val refill = Decoupled(new ICacheRefillReq)
  }

  def virtSetIdx(beatAddr: UInt, vAlias: UInt): UInt = {
    val base = if (HasVaddrAlias) {
      Cat(vAlias, beatAddr(SetIdxWidth - VaddrAliasWidth, ICacheToL2BeatWidth))
    } else {
      beatAddr(SetIdxWidth, ICacheToL2BeatWidth)
    }
    base
  }

  def aliasOf(setIdx: UInt): UInt = {
    if (HasVaddrAlias) setIdx(SetIdxWidth - 1, SetIdxWidth - VaddrAliasWidth)
    else 0.U(VaddrAliasWidth.W)
  }

  val io = IO(new ICacheMissUnitIO)

  //-----------------------alias------------------------------
  val flush = io.flush
  val lookupValid = io.lookupReq.valid
  val lookupReq = io.lookupReq.bits
  val fetchValid = io.fetchReq.valid
  val fetchReq = io.fetchReq.bits

  //-----------------------storage----------------------------
  val mshrs = RegInit(VecInit.fill(NumMshrs)(0.U.asTypeOf(new MshrEntry)))
  // 所有 entry 共用一条 cacheline。里面还有没送走的数据时，不再收新的返回。
  val buf = RegInit(0.U.asTypeOf(new MissDataBuffer))

  //-----------------------allocate---------------------------
  // 顺序：拆 line，fetch 融进 lookup，fetch 匹配旧表，lookup 再看 fetch 本拍的结果。
  // 空 entry。fetch 用 firstFree，同拍 lookup 还要分配时用 secondFree。
  val freeU = VecInit(mshrs.map(!_.valid)).asUInt
  val freeCount = PopCount(freeU)
  val firstOH = PriorityEncoderOH(freeU)
  val firstFree = PriorityEncoder(freeU)
  val secondFree = PriorityEncoder(freeU & (~firstOH))

  // line0 一定有。line1 只有跨 cacheline 才有。
  // 这里的地址是 cacheline 号，不含 beat。关键字 beat 留在原来的 beatPAddr 低位。
  // line1 的 vAlias 是 set 加 1 后的别名，不是 line0 的别名。
  val fetchLine0Valid = fetchValid
  val fetchLine1Valid = fetchValid && fetchReq.crossCacheline
  val fetchLine0Addr = lineAddr(fetchReq.beatPAddr)
  val fetchLine1Addr = (fetchLine0Addr + 1.U)(fetchLine0Addr.getWidth - 1, 0)
  val fetchLine0Set = virtSetIdx(fetchReq.beatPAddr, fetchReq.vAlias)
  val fetchLine1Set = (fetchLine0Set + 1.U)(SetIdxWidth - 1, 0)
  val fetchLine0Alias = fetchReq.vAlias
  val fetchLine1Alias = aliasOf(fetchLine1Set)

  val lookupLine0Valid = lookupValid
  val lookupLine1Valid = lookupValid && lookupReq.crossCacheline
  val lookupLine0Addr = lineAddr(lookupReq.beatPAddr)
  val lookupLine1Addr = (lookupLine0Addr + 1.U)(lookupLine0Addr.getWidth - 1, 0)
  val lookupLine0Set = virtSetIdx(lookupReq.beatPAddr, lookupReq.vAlias)
  val lookupLine1Set = (lookupLine0Set + 1.U)(SetIdxWidth - 1, 0)
  val lookupLine0Alias = lookupReq.vAlias
  val lookupLine1Alias = aliasOf(lookupLine1Set)

  def sameLine(validA: Bool, addrA: UInt, aliasA: UInt, validB: Bool, addrB: UInt, aliasB: UInt): Bool = {
    validA && validB && addrA === addrB && aliasA === aliasB
  }
  // 名字里的两个数字分别是 fetch 的 line 和 lookup 的 line。
  val fetch0HitLookup0 = sameLine(
    fetchLine0Valid, fetchLine0Addr, fetchLine0Alias, lookupLine0Valid, lookupLine0Addr, lookupLine0Alias
  )
  val fetch1HitLookup0 = sameLine(
    fetchLine1Valid, fetchLine1Addr, fetchLine1Alias, lookupLine0Valid, lookupLine0Addr, lookupLine0Alias
  )
  val fetch0HitLookup1 = sameLine(
    fetchLine0Valid, fetchLine0Addr, fetchLine0Alias, lookupLine1Valid, lookupLine1Addr, lookupLine1Alias
  )
  val fetch1HitLookup1 = sameLine(
    fetchLine1Valid, fetchLine1Addr, fetchLine1Alias, lookupLine1Valid, lookupLine1Addr, lookupLine1Alias
  )
  // lookup 的这一条被 fetch 融进来。两边都 fire 后，这个槽标成 fetch。
  val fetchHitLookup0 = fetch0HitLookup0 || fetch1HitLookup0
  val fetchHitLookup1 = fetch0HitLookup1 || fetch1HitLookup1
  // fetch 的这一条已经融进 lookup，不再自己去匹配 MSHR。
  val fetch0HitLookup = fetch0HitLookup0 || fetch0HitLookup1
  val fetch1HitLookup = fetch1HitLookup0 || fetch1HitLookup1

  class LineMatch extends Bundle {
    val cover = Bool()
    val fill = Bool()
    val hitOH = UInt(NumMshrs.W)
    val slot1 = Bool()
  }

  // 只看已经写进寄存器的 entry。忙槽优先于空槽。
  // cover：有 entry 能接住，不用新开。fill：接住的是空槽。
  // hitOH：选中的 entry。slot1：空槽是 slot1，忙槽命中时不用。
  def matchLine(reqLineValid: Bool, reqLineAddr: UInt, alias: UInt): LineMatch = {
    val busyHits = Wire(Vec(NumMshrs, Bool()))
    val empty0 = Wire(Vec(NumMshrs, Bool()))
    val empty1 = Wire(Vec(NumMshrs, Bool()))
    mshrs.zipWithIndex.foreach { case (entry, i) =>
      val base = lineAddr(entry.beatPAddr)
      val line1 = (base + 1.U)(base.getWidth - 1, 0)
      val alias1 = aliasOf((virtSetIdx(entry.beatPAddr, entry.vAlias) + 1.U)(SetIdxWidth - 1, 0))
      val alive = reqLineValid && entry.valid && !entry.flush
      val at0 = alive && base === reqLineAddr && entry.vAlias === alias
      val at1 = alive && line1 === reqLineAddr && alias1 === alias
      busyHits(i) := (at0 && entry.slotBusy(0)) || (at1 && entry.slotBusy(1))
      empty0(i) := at0 && !entry.slotBusy(0)
      empty1(i) := at1 && !entry.slotBusy(1)
    }
    val busyU = busyHits.asUInt
    val emptyU = empty0.asUInt | empty1.asUInt
    val busy = busyU.orR
    val fillOH = PriorityEncoderOH(emptyU)
    val out = Wire(new LineMatch)
    out.cover := busy || emptyU.orR
    out.fill := emptyU.orR && !busy
    out.hitOH := Mux(busy, busyU, fillOH)
    out.slot1 := (fillOH & empty1.asUInt).orR
    out
  }

  val fetchSend0 = fetchLine0Valid && !fetch0HitLookup
  val fetchSend1 = fetchLine1Valid && !fetch1HitLookup
  val fetchMatch0 = matchLine(fetchSend0, fetchLine0Addr, fetchLine0Alias)
  val fetchMatch1 = matchLine(fetchSend1, fetchLine1Addr, fetchLine1Alias)
  // 旧表接不住，这条 line 要进新 entry。
  val fetchMiss0 = fetchSend0 && !fetchMatch0.cover
  val fetchMiss1 = fetchSend1 && !fetchMatch1.cover
  val fetchNeedEntry = fetchMiss0 || fetchMiss1
  // 只看 fetch 自己还有没有空 entry，不看 lookup。
  val fetchOwnReady = !fetchNeedEntry || freeCount =/= 0.U
  // 按 fetch 自己能提交来占槽。不能再看 lookupReady，否则两边的 ready 接成环。
  val fetchPlan = fetchValid && fetchOwnReady
  // 位 i 为 1：这拍 fetch 要填 entry i 的这个槽，lookup 不能再填。
  val fetchPend0 =
    Mux(fetchPlan && fetchMatch0.fill && !fetchMatch0.slot1, fetchMatch0.hitOH, 0.U(NumMshrs.W)) |
    Mux(fetchPlan && fetchMatch1.fill && !fetchMatch1.slot1, fetchMatch1.hitOH, 0.U(NumMshrs.W))
  val fetchPend1 =
    Mux(fetchPlan && fetchMatch0.fill && fetchMatch0.slot1, fetchMatch0.hitOH, 0.U(NumMshrs.W)) |
    Mux(fetchPlan && fetchMatch1.fill && fetchMatch1.slot1, fetchMatch1.hitOH, 0.U(NumMshrs.W))

  val fetchBeat = fetchReq.beatPAddr(ICacheToL2BeatWidth - 1, 0)
  // 新 entry 的 slot0 地址，低位仍是关键字 beat。
  // line0 还 miss 时用请求原来的 beat 地址。只有 line1 miss 时，slot0 改记 line1。
  val fetchAllocBeat = Mux(fetchMiss0, fetchReq.beatPAddr, Cat(fetchLine1Addr, fetchBeat))
  val fetchAllocAlias = Mux(fetchMiss0, fetchLine0Alias, fetchLine1Alias)
  val fetchAllocTwo = fetchMiss0 && fetchMiss1
  val fetchWillAlloc = fetchPlan && fetchNeedEntry
  // 新 entry 还没写进寄存器。slot0 是上面这条地址，slot1 是它的下一条 line。
  val fetchNewBase = lineAddr(fetchAllocBeat)
  val fetchNewLine1 = (fetchNewBase + 1.U)(fetchNewBase.getWidth - 1, 0)
  val fetchNewSet = virtSetIdx(fetchAllocBeat, fetchAllocAlias)
  val fetchNewAlias1 = aliasOf((fetchNewSet + 1.U)(SetIdxWidth - 1, 0))

  val lookupMatch0 = matchLine(lookupLine0Valid, lookupLine0Addr, lookupLine0Alias)
  val lookupMatch1 = matchLine(lookupLine1Valid, lookupLine1Addr, lookupLine1Alias)
  // lookup 看中的空槽，已经被 fetch 预定。
  val lookupTaken0 = lookupMatch0.fill &&
    (Mux(lookupMatch0.slot1, fetchPend1, fetchPend0) & lookupMatch0.hitOH).orR
  val lookupTaken1 = lookupMatch1.fill &&
    (Mux(lookupMatch1.slot1, fetchPend1, fetchPend0) & lookupMatch1.hitOH).orR
  val lookupOld0 = lookupMatch0.cover || lookupTaken0
  val lookupOld1 = lookupMatch1.cover || lookupTaken1
  // 和 fetch 不是同一条 line，所以前面没有融合；但等于 fetch 新 entry 的某个槽。
  // New0 是 slot0，New1 是 slot1。例如 fetch 只申请 0x10，lookup 的 0x11 命中 New1。
  val lookup0New0 = fetchWillAlloc && lookupLine0Valid && !lookupOld0 &&
    lookupLine0Addr === fetchNewBase && lookupLine0Alias === fetchAllocAlias
  val lookup0New1 = fetchWillAlloc && lookupLine0Valid && !lookupOld0 &&
    lookupLine0Addr === fetchNewLine1 && lookupLine0Alias === fetchNewAlias1
  val lookup1New0 = fetchWillAlloc && lookupLine1Valid && !lookupOld1 &&
    lookupLine1Addr === fetchNewBase && lookupLine1Alias === fetchAllocAlias
  val lookup1New1 = fetchWillAlloc && lookupLine1Valid && !lookupOld1 &&
    lookupLine1Addr === fetchNewLine1 && lookupLine1Alias === fetchNewAlias1
  val lookup0HitNew = lookup0New0 || lookup0New1
  val lookup1HitNew = lookup1New0 || lookup1New1
  val lookupCover0 = lookupOld0 || lookup0HitNew
  val lookupCover1 = lookupOld1 || lookup1HitNew
  val lookupFill0 = lookupMatch0.fill && !lookupTaken0 && !lookup0HitNew
  val lookupFill1 = lookupMatch1.fill && !lookupTaken1 && !lookup1HitNew
  val lookupMiss0 = lookupLine0Valid && !lookupCover0
  val lookupMiss1 = lookupLine1Valid && !lookupCover1
  val lookupCovered = !lookupMiss0 && !lookupMiss1
  // fetch 本拍也要新 entry 时，lookup 再分配就得有第二个空 entry。
  val lookupFreeOk = Mux(fetchWillAlloc, freeCount >= 2.U, freeCount =/= 0.U)
  val lookupReady = !flush && (lookupCovered || lookupFreeOk)
  val lookupFire = lookupValid && lookupReady
  val lookupAlloc = lookupFire && (lookupMiss0 || lookupMiss1)
  val anyFused = (fetchLine0Valid && fetch0HitLookup) || (fetchLine1Valid && fetch1HitLookup)
  // 没有融合时 fetch 单独接收。有融合时必须等 lookup，否则融掉的 line 没人写。
  val fetchReady = fetchOwnReady && (!anyFused || lookupReady)
  val fetchFire = fetchValid && fetchReady
  val fetchAlloc = fetchFire && fetchNeedEntry
  val fetchAllocIdx = firstFree
  val lookupAllocIdx = Mux(fetchWillAlloc, secondFree, firstFree)
  val lookupBeat = lookupReq.beatPAddr(ICacheToL2BeatWidth - 1, 0)
  val lookupAllocBeat = Mux(lookupMiss0, lookupReq.beatPAddr, Cat(lookupLine1Addr, lookupBeat))
  val lookupAllocAlias = Mux(lookupMiss0, lookupLine0Alias, lookupLine1Alias)
  val lookupAllocTwo = lookupMiss0 && lookupMiss1
  val lookup0IsFetch = fetchFire && fetchHitLookup0
  val lookup1IsFetch = fetchFire && fetchHitLookup1
  // 新 entry 只装 line1 时，slot0 用 line1 的 fetch 标记。
  val lookupAllocFetch0 = Mux(!lookupMiss0 && lookupMiss1, lookup1IsFetch, lookup0IsFetch)
  val lookupAllocFetch1 = lookup1IsFetch
  // lookup 写进 fetch 新 entry 的 slot1 时，这条 lookup line 自己是不是 fetch。
  val lookupNew1Fetch = Mux(lookup0New1, lookup0IsFetch, lookup1IsFetch)

  //-----------------------issue------------------------------
  // fetch 的槽先发。一条 entry 同一拍只发一个槽。
  val fetchCand = VecInit(mshrs.map { entry =>
    entry.valid && !entry.flush && (
      (entry.fetch(0) && entry.waitIssue(0)) || (entry.fetch(1) && entry.waitIssue(1))
    )
  })
  val lookupCand = VecInit(mshrs.map { entry =>
    entry.valid && !entry.flush && (
      (!entry.fetch(0) && entry.waitIssue(0)) || (!entry.fetch(1) && entry.waitIssue(1))
    )
  })
  val fetchCandU = fetchCand.asUInt
  val lookupCandU = lookupCand.asUInt
  val fetchIssue = fetchCandU.orR
  val lookupIssue = lookupCandU.orR
  val selOH = Mux(fetchIssue, PriorityEncoderOH(fetchCandU), PriorityEncoderOH(lookupCandU))
  val hasIssue = fetchIssue || lookupIssue
  val issueIdx = OHToUInt(selOH)
  val issueEntry = Mux1H(selOH, mshrs)
  val issueLine = Mux(fetchIssue,
    Mux(issueEntry.fetch(0) && issueEntry.waitIssue(0), 0.U, 1.U),
    Mux(issueEntry.waitIssue(0) && !issueEntry.fetch(0), 0.U, 1.U))
  val issueLine0 = lineAddr(issueEntry.beatPAddr)
  val issueBeat = issueEntry.beatPAddr(ICacheToL2BeatWidth - 1, 0)
  val issueValid = hasIssue && !flush
  val issueId = Cat(issueLine, issueIdx)
  // slot0 用记下的 beat 地址。slot1 是下一条 line，低位沿用同一个关键字 beat。
  val issueAddr = Mux(issueLine.asBool,
    Cat((issueLine0 + 1.U)(issueLine0.getWidth - 1, 0), issueBeat),
    issueEntry.beatPAddr)

  val respIdx = io.memResp.bits.id(MshrIdxWidth - 1, 0)
  val respLine = io.memResp.bits.id(MshrIdxWidth)
  // 第一拍已经收下、第二拍还没来。
  val secondBeat = buf.busy && !buf.hold

  //-----------------------entry------------------------------
  mshrs.zipWithIndex.foreach { case (entry, i) =>
    val idx = i.U(MshrIdxWidth.W)
    val allocFromFetch = fetchAlloc && fetchAllocIdx === idx
    val allocThis = allocFromFetch || (lookupAlloc && lookupAllocIdx === idx)
    val allocBeatAddr = Mux(allocFromFetch, fetchAllocBeat, lookupAllocBeat)
    val allocAlias = Mux(allocFromFetch, fetchAllocAlias, lookupAllocAlias)
    val allocTwo = Mux(allocFromFetch, fetchAllocTwo, lookupAllocTwo)
    val allocFetch0 = Mux(allocFromFetch, true.B, lookupAllocFetch0)
    val allocFetch1 = Mux(allocFromFetch, fetchAllocTwo, lookupAllocFetch1)
    val issueThis = io.memReq.fire && issueIdx === idx
    val respSecond = io.memResp.fire && secondBeat && buf.idx === idx
    val refillThis = io.refill.fire && buf.idx === idx

    val waitIssueNext = Wire(Vec(2, Bool()))
    val waitRespNext = Wire(Vec(2, Bool()))
    val refillNext = Wire(Vec(2, Bool()))
    val fetchNext = Wire(Vec(2, Bool()))
    waitIssueNext := entry.waitIssue
    waitRespNext := entry.waitResp
    refillNext := entry.refill
    fetchNext := entry.fetch

    when (flush) {
      waitIssueNext := 0.U.asTypeOf(waitIssueNext)
      refillNext := 0.U.asTypeOf(refillNext)
    }
    when (issueThis) {
      waitIssueNext(issueLine) := false.B
      waitRespNext(issueLine) := true.B
    }
    def takeSlot(en: Bool, oh: UInt, slotIs1: Bool, isFetch: Bool): Unit = {
      when (en && oh(idx)) {
        when (slotIs1) {
          waitIssueNext(1) := true.B
          fetchNext(1) := isFetch
        }.otherwise {
          waitIssueNext(0) := true.B
          fetchNext(0) := isFetch
        }
      }
    }
    takeSlot(fetchFire && fetchMatch0.fill, fetchMatch0.hitOH, fetchMatch0.slot1, true.B)
    takeSlot(fetchFire && fetchMatch1.fill, fetchMatch1.hitOH, fetchMatch1.slot1, true.B)
    takeSlot(lookupFire && lookupFill0, lookupMatch0.hitOH, lookupMatch0.slot1, lookup0IsFetch)
    takeSlot(lookupFire && lookupFill1, lookupMatch1.hitOH, lookupMatch1.slot1, lookup1IsFetch)
    val line1Addr = (lineAddr(entry.beatPAddr) + 1.U)(lineAddr(entry.beatPAddr).getWidth - 1, 0)
    val line1Alias = aliasOf((virtSetIdx(entry.beatPAddr, entry.vAlias) + 1.U)(SetIdxWidth - 1, 0))
    def busyFetch(use: Bool, addr: UInt, alias: UInt, slot: Int): Bool = {
      val hit = Mux(slot.U === 0.U,
        lineAddr(entry.beatPAddr) === addr && entry.vAlias === alias,
        line1Addr === addr && line1Alias === alias)
      use && entry.slotBusy(slot) && hit
    }
    when (busyFetch(fetchFire && fetchSend0, fetchLine0Addr, fetchLine0Alias, 0) ||
      busyFetch(fetchFire && fetchSend1, fetchLine1Addr, fetchLine1Alias, 0) ||
      busyFetch(lookupFire && lookup0IsFetch, lookupLine0Addr, lookupLine0Alias, 0) ||
      busyFetch(lookupFire && lookup1IsFetch, lookupLine1Addr, lookupLine1Alias, 0)) {
      fetchNext(0) := true.B
    }
    when (busyFetch(fetchFire && fetchSend0, fetchLine0Addr, fetchLine0Alias, 1) ||
      busyFetch(fetchFire && fetchSend1, fetchLine1Addr, fetchLine1Alias, 1) ||
      busyFetch(lookupFire && lookup0IsFetch, lookupLine0Addr, lookupLine0Alias, 1) ||
      busyFetch(lookupFire && lookup1IsFetch, lookupLine1Addr, lookupLine1Alias, 1)) {
      fetchNext(1) := true.B
    }
    when (respSecond) {
      waitRespNext(buf.line) := false.B
      // fetch 也要写回 cache。取指看到 miss 后只等 way mask，不直接吃 MSHR 数据。
      when (!flush && !entry.flush) {
        refillNext(buf.line) := true.B
      }
    }
    when (refillThis) {
      refillNext(buf.line) := false.B
    }

    val respLeft = waitRespNext(0) || waitRespNext(1)
    val issueLeft = waitIssueNext(0) || waitIssueNext(1)
    val refillLeft = refillNext(0) || refillNext(1)
    val marked = entry.flush || (flush && entry.waitResp.asUInt.orR)
    val dropNow = flush && !entry.waitResp.asUInt.orR
    val kill = dropNow || (marked && !respLeft)
    val done = !marked && !dropNow && !respLeft && !issueLeft && !refillLeft
    // 空槽不再表示请求。line 完成后可以再融进新的 fetch 或 lookup。
    val fetchKeep = Wire(Vec(2, Bool()))
    fetchKeep := Mux(kill || done, 0.U.asTypeOf(fetchNext), fetchNext)
    when (!waitIssueNext(0) && !waitRespNext(0) && !refillNext(0)) {
      fetchKeep(0) := false.B
    }
    when (!waitIssueNext(1) && !waitRespNext(1) && !refillNext(1)) {
      fetchKeep(1) := false.B
    }

    // fetch 新建 entry 时，slot1 还能在同拍融进 lookup 的下一条 line。
    val lookupUseNew1 = allocFromFetch && !allocTwo && lookupFire && (lookup0New1 || lookup1New1)
    when (allocThis) {
      entry.valid := true.B
      entry.flush := false.B
      entry.beatPAddr := allocBeatAddr
      entry.vAlias := allocAlias
      entry.waitIssue(0) := true.B
      entry.waitIssue(1) := allocTwo || lookupUseNew1
      entry.waitResp := 0.U.asTypeOf(entry.waitResp)
      entry.refill := 0.U.asTypeOf(entry.refill)
      entry.fetch(0) := allocFetch0
      entry.fetch(1) := Mux(lookupUseNew1, lookupNew1Fetch, allocTwo && allocFetch1)
    }.otherwise {
      entry.valid := entry.valid && !kill && !done
      entry.flush := !kill && !done && marked
      entry.fetch := fetchKeep
      entry.waitIssue := Mux(kill || done, 0.U.asTypeOf(entry.waitIssue), waitIssueNext)
      entry.waitResp := Mux(kill || done, 0.U.asTypeOf(entry.waitResp), waitRespNext)
      entry.refill := Mux(kill || done, 0.U.asTypeOf(entry.refill), refillNext)
    }
  }

  //-----------------------buffer-----------------------------
  // data(0) 是低 32B，data(1) 是高 32B。按关键字 beat 放入，不按到达顺序。
  val respBeat = mshrs(respIdx).beatPAddr(ICacheToL2BeatWidth - 1, 0)
  val heldBeat = mshrs(buf.idx).beatPAddr(ICacheToL2BeatWidth - 1, 0)
  val otherBeat = (~heldBeat)(ICacheToL2BeatWidth - 1, 0)
  val bufNext = Wire(new MissDataBuffer)
  bufNext := buf
  when (io.refill.fire || (flush && buf.hold)) {
    bufNext.busy := false.B
    bufNext.hold := false.B
  }.elsewhen (io.memResp.fire && !buf.busy) {
    bufNext.busy := true.B
    bufNext.hold := false.B
    bufNext.idx := respIdx
    bufNext.line := respLine
    bufNext.data(0) := Mux(respBeat === 0.U, io.memResp.bits.data, buf.data(0))
    bufNext.data(1) := Mux(respBeat === 1.U, io.memResp.bits.data, buf.data(1))
  }.elsewhen (io.memResp.fire && secondBeat) {
    val dropData = flush || mshrs(buf.idx).flush
    bufNext.busy := !dropData
    bufNext.hold := !dropData
    bufNext.data(0) := Mux(otherBeat === 0.U, io.memResp.bits.data, buf.data(0))
    bufNext.data(1) := Mux(otherBeat === 1.U, io.memResp.bits.data, buf.data(1))
  }
  buf := bufNext

  val refillEntry = mshrs(buf.idx)
  val anyWaitResp = mshrs.map(entry => entry.valid && entry.waitResp.asUInt.orR).reduce(_ || _)

  //-----------------------output-----------------------------
  io.lookupReq.ready := lookupReady
  io.fetchReq.ready := fetchReady

  io.memReq.valid := issueValid
  io.memReq.bits.id := issueId
  io.memReq.bits.addr := issueAddr
  io.memResp.ready := !buf.hold && (buf.busy || anyWaitResp)

  val refillLine0 = lineAddr(refillEntry.beatPAddr)
  val refillLine1 = (refillLine0 + 1.U)(refillLine0.getWidth - 1, 0)
  val refillSet = virtSetIdx(refillEntry.beatPAddr, refillEntry.vAlias)
  // refill 位一旦置上就写完。之后再把这个槽标成 fetch，也不能把 buffer 卡住。
  io.refill.valid := buf.hold && !flush && !refillEntry.flush && refillEntry.refill(buf.line)
  io.refill.bits.setIdx := Mux(buf.line.asBool, (refillSet + 1.U)(SetIdxWidth - 1, 0), refillSet)
  io.refill.bits.pTag := Mux(buf.line.asBool,
    refillLine1(refillLine1.getWidth - 1, SetIdxWidth),
    refillLine0(refillLine0.getWidth - 1, SetIdxWidth))
  io.refill.bits.data := Cat(buf.data(1), buf.data(0))

  when (io.memResp.fire) {
    val waiting = mshrs(respIdx).valid && mshrs(respIdx).waitResp(respLine)
    assert(Mux(secondBeat,
      io.memResp.bits.last && io.memResp.bits.id === Cat(buf.line, buf.idx),
      !io.memResp.bits.last && waiting
    ), "memory beat does not match the current buffer")
  }
}
