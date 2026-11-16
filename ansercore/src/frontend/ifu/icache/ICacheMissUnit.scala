package ansercore.frontend.ifu.icache

import chisel3._
import chisel3.util._
import utils.Mux1H
import utils.LowPriorityOH
import chisel3.VecIntf

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
    val lookupReq = Flipped(Decoupled(new ICacheMissReq))
    val memReq = Decoupled(new ICacheMemReq)
    val memResp = Flipped(Decoupled(new ICacheMemResp))
    val refill = Decoupled(new ICacheRefillReq)
    // 两行分开查。wayMask 是一位热，为 0 不会撞上。
    // collide 表示某个忙槽的 set 相同且 way 有重叠。
    val hazardSet = Input(Vec(2, UInt(SetIdxWidth.W)))
    val hazardWay = Input(Vec(2, UInt(numWays.W)))
    val hazardCollide = Output(Vec(2, Bool()))
  }

  // 物理 beat 地址。跨行不会跨页，line1 的 pTag 与 line0 相同，只把 vSetIdx 加 1。
  def beatAddr(blockAddr: UInt): UInt = {
    require(blockAddr.getWidth == LineAddrWidth)
    Cat(blockAddr, 0.U(ICacheToL2BeatWidth.W))
  }

  def blockAddr(pTag: UInt, vSetIdx: UInt): UInt ={
    val setInPage = if (HasVaddrAlias) {
      vSetIdx(PageSetIdxWidth - 1, 0)
    } else {
      vSetIdx
    }
    Cat(pTag, setInPage)
  }

  def pTag(blockAddr: UInt): UInt ={
    require(blockAddr.getWidth == LineAddrWidth)
    blockAddr(LineAddrWidth-1, PageSetIdxWidth)
  }

  def nextSet(vSetIdx: UInt): UInt = (vSetIdx + 1.U)(SetIdxWidth - 1, 0)

  val io = IO(new ICacheMissUnitIO)

  //-----------------------alias------------------------------
  val lookupValid = io.lookupReq.valid
  val lookupReq = io.lookupReq.bits
  val memResp = io.memResp.bits
  val memRespFire = io.memResp.fire
  val refillFire = io.refill.fire

  //-----------------------storage----------------------------
  val mshrs = RegInit(VecInit.fill(NumMshrs)(0.U.asTypeOf(new MshrEntry)))
  // 所有 entry 共用一条 cacheline。refill 还没送走时，不再收新的返回。
  val buf = RegInit(0.U.asTypeOf(new MissDataBuffer))

  //-----------------------allocate---------------------------
  // 一条 lookup 拆成 line0，跨行才有 line1。
  // 已有 entry 能接住就是融合：忙槽已经在取，空槽补上等待。接不住才分配。
  // 两条都接不住时共用一个新 entry，slot1 是下一条 line。
  val freeU = VecInit(mshrs.map(!_.valid)).asUInt
  val canAllocate = freeU.orR
  val firstFree = PriorityEncoder(freeU)

  val lookupTag = lookupReq.pTag
  val lookupWay = lookupReq.wayMask
  val lookupSet0 = lookupReq.setIdx
  val lookupSet1 = nextSet(lookupSet0)
  val lookupLine0Valid = lookupValid
  val lookupLine1Valid = lookupValid && lookupReq.crossCacheline

  class LineMatch extends Bundle {
    val merge = Bool()
    val fill = Bool()
    val hitOH = UInt(NumMshrs.W)
    val slot = Vec(2, Bool()) // 只表示fill 不u表示merge
  }

  val entryLine0Addr = VecInit(mshrs.map{entry => blockAddr(entry.pTag, entry.vSetIdx)})
  val entryLine1Addr = VecInit(entryLine0Addr.map{_ + 1.U})
  val entryTag0 = VecInit(mshrs.map{_.pTag})
  val entryTag1 = VecInit(entryLine1Addr.map{pTag(_)})
  // 只看已经写进寄存器的 entry。忙槽优先于空槽。
  // merge：忙槽接住，不改状态。fill：空槽接住，要把该槽改成 issue。
  // hitOH 和 slot 只描述当前生效的那一种，而且 hitOH 只有一位。
  def matchLine(reqSet: UInt, reqTag: UInt): LineMatch = {
    val hitSlotValid0 = Wire(Vec(NumMshrs, Bool()))
    val hitSlotValid1 = Wire(Vec(NumMshrs, Bool()))
    val hitSlotEmpty0 = Wire(Vec(NumMshrs, Bool()))
    val hitslotEmpty1 = Wire(Vec(NumMshrs, Bool()))
    mshrs.zipWithIndex.foreach { case (entry, i) =>
      val alive = entry.valid
      val line0 = entryLine0Addr(i)
      val line1 = entryLine1Addr(i)
      val pTag0 = entryTag0(i)
      val pTag1 = entryTag1(i)
      val at0 = alive && entry.vSetIdx === reqSet && pTag0 === reqTag
      val at1 = alive && nextSet(entry.vSetIdx) === reqSet && pTag1 === reqTag
      hitSlotValid0(i) := at0 && entry.slotValid(0)
      hitSlotValid1(i) := at1 && entry.slotValid(1)
      hitSlotEmpty0(i) := at0 && entry.slotIdle(0)
      hitslotEmpty1(i) := at1 && entry.slotIdle(1)
    }

    val hitSlotValid = hitSlotValid0.asUInt | hitSlotValid1.asUInt
    val hitSlotEmpty = hitSlotEmpty0.asUInt | hitslotEmpty1.asUInt
    val hitValid = hitSlotValid.orR
    val hitEmpty = hitSlotEmpty.orR
    val hitOH = Mux(hitValid, LowPriorityOH(hitSlotValid), LowPriorityOH(hitSlotEmpty))
    val out = Wire(new LineMatch)
    out.merge := hitValid
    out.fill := hitEmpty && !hitValid
    out.hitOH := hitOH
    out.slot(0) := (hitOH & hitSlotEmpty0.asUInt).orR
    out.slot(1) := (hitOH & hitslotEmpty1.asUInt).orR
    out
  }

  val lookupMatch0 = matchLine(lookupSet0, lookupTag)
  val lookupMatch1 = matchLine(lookupSet1, lookupTag)
  val lookupMiss0 = lookupLine0Valid && !lookupMatch0.merge && !lookupMatch0.fill
  val lookupMiss1 = lookupLine1Valid && !lookupMatch1.merge && !lookupMatch1.fill
  val lookupFill0 = lookupLine0Valid && lookupMatch0.fill
  val lookupFill1 = lookupLine1Valid && lookupMatch1.fill
  val lookupNeedEntry = lookupMiss0 || lookupMiss1
  // 只比 set 和 way。忙槽已经占了这对 set/way 时，这次 lookup 不能再进来。
  def waySetCollide(setIdx: UInt, wayMask: UInt): Bool = {
    mshrs.map { entry =>
      val slot0 = entry.slotValid(0) && entry.vSetIdx === setIdx &&
        (entry.refillWay(0) & wayMask).orR
      val slot1 = entry.slotValid(1) && nextSet(entry.vSetIdx) === setIdx &&
        (entry.refillWay(1) & wayMask).orR
      slot0 || slot1
    }.reduce(_ || _)
  }
  val lookupCollide =
    (lookupLine0Valid && waySetCollide(lookupSet0, lookupWay(0))) ||
      (lookupLine1Valid && waySetCollide(lookupSet1, lookupWay(1)))

  val lookupReady = (!lookupNeedEntry || canAllocate) && !lookupCollide // 哪怕是可以merge 也不可以。
  val lookupFire = lookupValid && lookupReady
  val lookupAlloc = lookupFire && lookupNeedEntry
  val lookupAllocIdx = firstFree
  // line0 还 miss 时，entry 记下 line0。只有 line1 miss 时，slot0 改记 line1。
  val allocSet = Mux(lookupMiss0, lookupSet0, lookupSet1)
  val allocTag = lookupTag
  val lookupAllocTwo = lookupMiss0 && lookupMiss1

  //-----------------------issue------------------------------
  // 一条 entry 同一拍只发一个槽。两个槽都在等时先发 slot0。
  val issueCand = VecInit(mshrs.map { entry =>
    entry.valid && (entry.slotIssue(0) || entry.slotIssue(1))
  })
  val issueCandU = issueCand.asUInt
  val selOH = LowPriorityOH(issueCandU)
  val hasIssue = issueCandU.orR
  val issueIdx = OHToUInt(selOH)
  val issueEntry = Mux1H(selOH, mshrs)
  val issueLine = Mux(issueEntry.slotIssue(0), 0.U, 1.U)
  val issueLine0Addr = entryLine0Addr(issueIdx)
  val issueLine1Addr = entryLine1Addr(issueIdx)
  val issueValid = hasIssue
  val issueId = Cat(issueLine, issueIdx)
  val issueAddr = beatAddr(Mux(issueLine.asBool, issueLine1Addr, issueLine0Addr))

  val respIdx = memResp.id(MshrIdxWidth - 1, 0)
  val respLine = memResp.id(MshrIdxWidth)
  // beats 为 0 还没收到数据。为 1 时第一拍已经在缓冲里，下一拍是这一条 line 的最后一拍。
  val respLast = memRespFire && buf.beats === 1.U

  //-----------------------entry------------------------------
  mshrs.zipWithIndex.foreach { case (entry, i) =>
    val idx = i.U(MshrIdxWidth.W)
    val allocThis = lookupAlloc && lookupAllocIdx === idx
    val issueThis = io.memReq.fire && issueIdx === idx
    val refillThis = refillFire && buf.idx === idx

    val fillEN = Wire(Vec(2, Bool()))
    val issueEN = Wire(Vec(2, Bool()))
    val responseEN = Wire(Vec(2, Bool()))
    val refillEN = Wire(Vec(2, Bool()))
    val hit0 = lookupMatch0.hitOH(idx)
    val hit1 = lookupMatch1.hitOH(idx)
    val lookupFill0This = lookupFill0 && hit0
    val lookupFill1This = lookupFill1 && hit1

    fillEN(0) := lookupFire && (
      (lookupFill0This && lookupMatch0.slot(0)) ||
      (lookupFill1This && lookupMatch1.slot(0))
    )
    fillEN(1) := lookupFire && (
      (lookupFill0This && lookupMatch0.slot(1)) ||
      (lookupFill1This && lookupMatch1.slot(1))
    )
    issueEN(0) := issueThis && issueLine === 0.U
    issueEN(1) := issueThis && issueLine === 1.U
    responseEN(0) := respLast && respIdx === idx && !respLine
    responseEN(1) := respLast && respIdx === idx && respLine
    refillEN(0) := refillThis && !buf.line
    refillEN(1) := refillThis && buf.line

    val states = entry.states
    val nextState = Wire(Vec(2, MSHRState()))
    nextState := states

    when(allocThis){
      entry.vSetIdx := allocSet
      entry.pTag := allocTag
    }

    when(allocThis){
      entry.refillWay(0) := Mux(lookupMiss0 ,lookupWay(0), lookupWay(1))
    }.elsewhen(fillEN(0)){
      entry.refillWay(0) := Mux(lookupFill0This, lookupWay(0), lookupWay(1))
    }

    when(allocThis){
      entry.refillWay(1) := lookupWay(1)
    }.elsewhen(fillEN(1)){
      entry.refillWay(1) := Mux(lookupFill0This, lookupWay(0), lookupWay(1))
    }

    switch(states(0)){
      is(MSHRState.idle){
        when(allocThis || fillEN(0)){
          nextState(0) := MSHRState.issue
        }
      }
      is(MSHRState.issue){
        when(issueEN(0)){
          nextState(0) := MSHRState.response
        }
      }
      is(MSHRState.response){
        when(responseEN(0)){
          nextState(0) := MSHRState.refill
        }
      }
      is(MSHRState.refill){
        when(refillEN(0)){
          nextState(0) := MSHRState.idle
        }
      }
    }

    switch(states(1)){
      is(MSHRState.idle){
        when(allocThis && lookupAllocTwo || fillEN(1)){
          nextState(1) := MSHRState.issue
        }
      }
      is(MSHRState.issue){
        when(issueEN(1)){
          nextState(1) := MSHRState.response
        }
      }
      is(MSHRState.response){
        when(responseEN(1)){
          nextState(1) := MSHRState.refill
        }
      }
      is(MSHRState.refill){
        when(refillEN(1)){
          nextState(1) := MSHRState.idle
        }
      }
    }

    states := nextState
  }

  //-----------------------buffer-----------------------------
  val bufNext = Wire(new MissDataBuffer)
  bufNext := buf
  when (refillFire) {
    bufNext.refill := false.B
    bufNext.beats := 0.U
  }.elsewhen (memRespFire && buf.beats === 0.U) {
    bufNext.idx := respIdx
    bufNext.line := respLine
    bufNext.beats := 1.U
    bufNext.data(0) := memResp.data
  }.elsewhen (memRespFire) {
    bufNext.refill := true.B
    bufNext.data(1) := memResp.data
  }
  buf := bufNext

  val refillEntry = mshrs(buf.idx)
  val refillSet = Mux(buf.line, nextSet(refillEntry.vSetIdx), refillEntry.vSetIdx)
  val refillWay = Mux(buf.line, refillEntry.refillWay(1), refillEntry.refillWay(0))
  val refillTag = Mux(buf.line, entryTag1(buf.idx), entryTag0(buf.idx))
  //-----------------------output-----------------------------
  io.hazardCollide := VecInit((io.hazardSet zip io.hazardWay).map { case (setIdx, wayMask) =>
    waySetCollide(setIdx, wayMask)
  })

  io.lookupReq.ready := lookupReady
  io.memReq.valid := issueValid
  io.memReq.bits.id := issueId
  io.memReq.bits.addr := issueAddr
  val secondBeat = buf.beats === 1.U && !buf.refill
  io.memResp.ready := !buf.refill && (!secondBeat || memResp.id === Cat(buf.line, buf.idx))

  io.refill.valid := buf.refill
  io.refill.bits.refillInfo.setIdx := refillSet
  io.refill.bits.refillInfo.wayMask := refillWay
  io.refill.bits.pTag := refillTag
  io.refill.bits.data := buf.data.asUInt

  when (lookupFire && lookupReq.crossCacheline) {
    val setInPage = if (HasVaddrAlias) {
      lookupSet0(SetIdxWidth - VaddrAliasWidth - 1, 0)
    } else {
      lookupSet0
    }
    assert(!setInPage.andR, "cross-cacheline miss must stay in one page")
  }
  when (memRespFire) {
    when (buf.beats === 0.U) {
      assert(!memResp.last, "the first beat of a line must not be last")
    }.otherwise {
      assert(memResp.last && memResp.id === Cat(buf.line, buf.idx), "the second beat does not match the buffer")
    }
  }
}
