package ansercore.frontend.bpu.ras

import chisel3._
import chisel3.util._

class RAS extends RASModule {
  class RASIO extends RASBundle{
    val prediction = Output(UInt(pcWidth.W))
    val specUpate = Flipped(Valid(new RASUpdateReq))
    val meta = Output(new RASMeta)
    val recover = Flipped(Valid(new RASRecoverReq))
  }
  val io = IO(new RASIO)
  //------------------------alias--------------------------

  val stack = Reg(Vec(NumEntrys, UInt(pcWidth.W)))
  val sp = Reg(new RASPtr(NumEntrys))

/*
  when(recover.valid) {
    sp := recover.bits.sp
  }.elsewhen(specUpdate.valid){
    when(specUpdate.bits.isPush){
      sp := sp +1.U
    }.otherwise{
      sp := sp -1.U
    }
  }

  when(recover.valid) {
    stack(recover.bits.sp) := recover.bits.topAddr
  }.elsewhen(specUpdate.valid){
    when(specUpdate.bits.isPush){
      stack(sp) := specUpdate.bits.pushAddr
    }
  }

  //connect
  val meta       = Wire(new RASMeta(p))
  meta.sp := sp
  meta.topAddr := stack(sp)
  io.pred := stack(sp)
  */
}
