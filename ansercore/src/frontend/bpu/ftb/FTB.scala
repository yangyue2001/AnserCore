package ansercore.frontend.bpu.ftb

import chisel3._
import chisel3.util._
import utils.Mux1H
import ansercore.frontend.FrontendHelper

class FTB extends FTBModule with FTBHelper with FrontendHelper {
  class FTBIO extends FTBBundle {
    val s1Fire = Input(Bool())
    val s2PC = Input(UInt(pcWidth.W))
    val predictReq = Flipped(Decoupled(new FTBPredReq))
    val prediction = Output(new FTBPrediction)
    val meta = Output(new FTBMeta)
    val resetDown = Output(Bool())
    val ftbTrain = Flipped(Decoupled(new FTBTrain))
  }

  val io = IO(new FTBIO)

  val s1Fire = io.s1Fire
  val s2PC = io.s2PC
  val predictReqValid = io.predictReq.valid
  val predictReq = io.predictReq.bits

  val storage = Module(new FTBStorage)

//----------------------predict s0------------------------------------//
  val readBankMask = getBankMask(predictReq.pc)
  storage.io.readReq.valid := predictReqValid
  storage.io.readReq.bits.bankMask := readBankMask
  storage.io.readReq.bits.setIdx := getSetIndex(predictReq.pc)

//----------------------predict s1------------------------------------//
  // s1 是 SRAM 读出。s1Fire 才把这拍读数打进 s2。
  val s1Entries = if (NumWays == 1) {
    Seq(storage.io.readResp.entry.get)
  } else {
    storage.io.readResp.entries.get
  }

//----------------------predict s2------------------------------------//
  val s2Entries = s1Entries.map(entry => RegEnable(entry, s1Fire))
  val pcTag = getTag(s2PC)
  require(pcTag.getWidth == TagWidth)

  // 1 way 只比这一条 tag。多 way 取编号最低的路。没有命中时 entry 为 0。
  val s2Entry = if (NumWays == 1) {
    val entry = s2Entries.head
    Mux(entry.tag === pcTag, entry, 0.U.asTypeOf(new FTBEntry))
  } else {
    val wayHit = s2Entries.map(entry => entry.tag === pcTag)
    Mux1H(PriorityEncoderOH(wayHit), s2Entries)
  }

  val branchSlot = s2Entry.brSlot
  val tailSlot = s2Entry.tailSlot
  val tailCondTarget = getTarget(s2PC, tailSlot.tarStat, tailSlot.lower(11, 0))
  val tailDirectTarget = getTarget(s2PC, tailSlot.tarStat, tailSlot.lower)

  val prediction = Wire(new FTBPrediction)
  prediction.branchHit := branchSlot.valid
  prediction.tailCFIAttribute := tailSlot.cfiAttribute
  prediction.branchOffset := branchSlot.offset
  prediction.tailOffset := tailSlot.offset
  prediction.branchTarget := getTarget(s2PC, branchSlot.tarStat, branchSlot.lower)
  prediction.tailTarget := Mux(tailSlot.cfiAttribute.isConditional, tailCondTarget, tailDirectTarget)
  val tailHit = !tailSlot.cfiAttribute.isNone
  val (sequentialTarget, sequentialOffset) = sequentialFetch(s2PC)

  prediction.fallThroughTarget := Mux(
    tailHit,
    (s2PC + tailSlot.offset + 1.U)(pcWidth - 1, 0),
    sequentialTarget,
  )
  prediction.fallThroughOffset := Mux(tailHit, tailSlot.offset, sequentialOffset)

  io.prediction := prediction
  val meta = Wire(new FTBMeta)
  meta.brHit := prediction.branchHit
  meta.brOffset := prediction.branchOffset
  meta.tailHit := tailHit
  meta.tailOffset := prediction.tailOffset
  io.meta := meta
  io.resetDown := storage.io.resetDown
  io.predictReq.ready := true.B
  // 训练这次不接，端口保持空闲。
  io.ftbTrain.ready := true.B
  storage.io.trainReadReq.valid := false.B
  storage.io.trainReadReq.bits := 0.U.asTypeOf(storage.io.trainReadReq.bits)
  storage.io.writeReq.valid := false.B
  storage.io.writeReq.bits := 0.U.asTypeOf(storage.io.writeReq.bits)
}
