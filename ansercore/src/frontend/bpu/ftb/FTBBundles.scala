package ansercore.frontend.bpu.ftb

import chisel3._
import chisel3.util._
import ansercore.frontend.CFIAttribute

class BranchSlot extends FTBBundle {
  val valid   = Bool()
  val offset  = UInt(FetchBlockOffsetWidth.W)
  val lower   = UInt(12.W)
  val tarStat = UInt(2.W)
}

class TailSlot extends FTBBundle {
  val cfiAttribute = new CFIAttribute
  val offset  = UInt(FetchBlockOffsetWidth.W)
  val lower   = UInt(20.W)
  val tarStat = UInt(2.W)
}

class FTBEntry extends FTBBundle {
  val tag = UInt(TagWidth.W)
  val brSlot = new BranchSlot
  val tailSlot = new TailSlot
}

class FTBPredReq extends FTBBundle {
  val pc = UInt(pcWidth.W)
}

class FTBPrediction extends FTBBundle {
  val branchHit         = Bool()
  val tailCFIAttribute  = new CFIAttribute
  val fallThroughTarget = UInt(pcWidth.W)
  val branchTarget      = UInt(pcWidth.W)
  val tailTarget        = UInt(pcWidth.W)
  val fallThroughOffset = UInt(FetchBlockOffsetWidth.W)
  val branchOffset      = UInt(FetchBlockOffsetWidth.W)
  val tailOffset        = UInt(FetchBlockOffsetWidth.W)
}

class FTBMeta extends FTBBundle {
  val brHit = Bool()
  val brOffset = UInt(FetchBlockOffsetWidth.W)
  val tailHit = Bool()
  val tailOffset = UInt(FetchBlockOffsetWidth.W)
}

class FTBTrain extends FTBBundle{
  val cfiAttribute = new CFIAttribute
  val endOffset = UInt(FetchBlockOffsetWidth.W)
  val meta = new FTBMeta
}

class FTBReadReq extends FTBBundle {
  val bankMask = UInt(NumBanks.W)
  val setIdx = UInt(SetIdxWidth.W)
}

class FTBWriteReq extends FTBBundle {
  val bankMask = UInt(NumBanks.W)
  val setIdx = UInt(SetIdxWidth.W)
  val wayOH = if (NumWays == 1) None else Some(UInt(NumWays.W))
  val entry = new FTBEntry
}

// 1 way 时不保留长度为 1 的路向量。
class FTBStorageResp extends FTBBundle {
  val entry = if (NumWays == 1) Some(new FTBEntry) else None
  val entries = if (NumWays > 1) Some(Vec(NumWays, new FTBEntry)) else None
}
