package ansercore.frontend.bpu.ittage

import chisel3._
import chisel3.util._

import utils.SaturateCounter
import ansercore.frontend.bpu.tage.TAGETableInfo
import ansercore.frontend.CFIAttribute

class ITTAGEEntry(implicit info: TAGETableInfo) extends ITTAGEBundle {
  val ctr    = new SaturateCounter(ConfidenceCtrWidth)
  val tag = UInt(TagWidth.W)
  val pointer = UInt(RegionIdxWidth.W)
  val useful = new SaturateCounter(UsefulCtrWidth)
  val offset = UInt(TargetOffsetWidth.W)
  val usePCRegion = Bool()
}

class ITTAGETrain extends  ITTAGEBundle{
  val trainPC = UInt(pcWidth.W)
  val trainPHR = new ITTAGEAllFoldedHistories
  val meta = new ITTAGEMeta
  val endCFIAttribute = new CFIAttribute
  val endOffset = UInt(FetchBlockOffsetWidth.W)
  val target = UInt(pcWidth.W)
  val mispredict = Bool()
}

class RegionEntry extends ITTAGEBundle {
  val valid  = Bool()
  val region = UInt(RegionWidth.W)
}

class ITTAGETableReadReq(implicit info: TAGETableInfo) extends ITTAGEBundle{
  val bankMask = UInt(NumBanks.W)
  val setIdx  = UInt(SetIdxWidth.W)
}

// 1 way 没有路选择。写的是整条 entry，字段没变的值保持读出的旧值。
class ITTAGETableWriteReq(implicit info: TAGETableInfo) extends ITTAGEBundle{
  val bankMask = UInt(NumBanks.W)
  val setIdx = UInt(SetIdxWidth.W)
  val wayOH = if (NumWays == 1) None else Some(UInt(NumWays.W))
  val entry = new ITTAGEEntry
}

// 1 way 只有 entry。多 way 才保留 entries，避免长度为 1 的路向量。
class ITTAGETableResp(implicit info: TAGETableInfo) extends ITTAGEBundle {
  val entry = if (NumWays == 1) Some(new ITTAGEEntry) else None
  val entries = if (NumWays > 1) Some(Vec(NumWays, new ITTAGEEntry)) else None
}

class ITTAGEPredReq  extends ITTAGEBundle{
  val pc = UInt(pcWidth.W)
  val predPHR = new ITTAGEAllFoldedHistories
}


class ITTAGEPrediction  extends ITTAGEBundle {
  val taken = Bool()
  val target = UInt(pcWidth.W)
}

class ITTAGEMeta extends ITTAGEBundle {
  val needUpdate = Bool()
}




class ITTAGEFoldedHist(implicit info: TAGETableInfo) extends ITTAGEBundle {
  val forIdx: UInt      = UInt(SetIdxWidth.W)
  val forTag: Vec[UInt] = Vec(2, UInt(TagWidth.W))
}


class ITTAGEAllFoldedHistories extends ITTAGEBundle {
  val hist = MixedVec(TableInfos.map { implicit tableInfo =>
    new ITTAGEFoldedHist
  })
}