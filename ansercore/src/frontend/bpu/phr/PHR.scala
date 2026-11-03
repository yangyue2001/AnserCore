package ansercore.frontend.bpu.phr

import chisel3._
import chisel3.util._

class PHR extends PHRModule with PHRHelper {
  class PHRIO extends PHRBundle {
    val s2Fire = Input(Bool())
    val s0FoldedPHR = Output(new PHRAllFoldedHistories)
    val s1FoldedPHR = Output(new PHRAllFoldedHistories)
    val meta = Output(new PHRMeta)
    val s1Update = Flipped(Valid(new PHRUpdate))
    val s2Override = Flipped(Valid(new PHROverride))
    val s3Override = Flipped(Valid(new PHROverride))
    val redirect = Flipped(Valid(new PHRRedirect))
    val trainMeta = Input(new PHRMeta)
    val trainFoldedPHR = Output(new PHRAllFoldedHistories)
  }

  val io = IO(new PHRIO)

  //-----------------------alias------------------------------
  val s1UpdateValid = io.s1Update.valid
  val s1Update = io.s1Update.bits
  val s2OverrideValid = io.s2Override.valid
  val s2Override = io.s2Override.bits
  val s3OverrideValid = io.s3Override.valid
  val s3Override = io.s3Override.bits
  val redirectValid = io.redirect.valid
  val redirect = io.redirect.bits
  val trainMeta = io.trainMeta

  //-----------------------state------------------------------
  val phrPtr = RegInit(0.U.asTypeOf(new PHRPtr(HistoryChunks)))
  val phr = RegInit(0.U.asTypeOf(Vec(HistoryChunks, UInt(Shamt.W))))

//s0

//
  io.s0FoldedPHR := 0.U.asTypeOf(new PHRAllFoldedHistories)
  io.s1FoldedPHR := 0.U.asTypeOf(new PHRAllFoldedHistories)
  io.meta := 0.U.asTypeOf(new PHRMeta)
  io.trainFoldedPHR := 0.U.asTypeOf(new PHRAllFoldedHistories)
}
