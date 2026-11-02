package ansercore.frontend.bpu.uftb

import chisel3._
import chisel3.util._

import ansercore.frontend.bpu.ftb.BranchSlot
import ansercore.frontend.bpu.ftb.TailSlot
import ansercore.frontend.bpu.ftb.FtbEntry
import ansercore.frontend.CFIAttribute


class UFTBPrediction extends UFTBBundle {
  val taken       = Bool()
  val hit         = Bool()
  val cfiOffset   = UInt(FetchBlockOffsetWidth.W)
  val cfiAttribute = new CFIAttribute
  val target      = UInt(pcWidth.W)
}


class UFTBMeta extends UFTBBundle {
  val isJalr = Bool()
  val fallThroughCarry = Bool()
  val fallThroughLower = UInt(FetchBlockOffsetWidth.W)
  val brSlot = new BranchSlot
  val tailSlot = new TailSlot

  def isEqual(that: UFTBMeta): Bool = {
    (this.isJalr === that.isJalr) && (this.fallThroughCarry === that.fallThroughCarry) &&
    (this.fallThroughLower === that.fallThroughLower) && (this.brSlot.asUInt === that.brSlot.asUInt) &&
    (this.tailSlot.valid === that.tailSlot.valid) && (this.tailSlot.offset === that.tailSlot.offset) &&
    (this.tailSlot.tarStat === that.tailSlot.tarStat) &&(this.tailSlot.share === that.tailSlot.share) &&
    (this.isJalr || (this.tailSlot.lower === this.tailSlot.lower))
  }
}


class UFTBTrainReq extends UFTBBundle {
  val s3Override = Bool()
  val trainPC    = UInt(pcWidth.W)
  val finalTaken = Vec(NumBr, Bool())
  val finalTarget = UInt(pcWidth.W)
  val ftbMeta = new UFTBMeta
  val uftbMeta       = new UFTBMeta
}
