package ansercore.frontend.bpu.tage

import chisel3._
import chisel3.util._

import utils.SaturateCounter


class TAGETableEntry(implicit info: TAGETableInfo) extends TAGEBundle {
  val ctr   = new SaturateCounter(TakenCtrWidth)
  val tag = UInt(TagWidth.W)
  val useful = new SaturateCounter(UsefulCtrWidth)
}

class TAGETableReadReq(implicit info: TAGETableInfo) extends TAGEBundle {
  val bankMask = UInt(NumBanks.W)
  val setIdx  = UInt(SetIdxWidth.W)
}


class BaseTableReadReq extends TAGEBundle {
  val bankMask = UInt(NumBanks.W)
  val setIdx  = UInt(BaseTableSetIdxWidth.W)
}


class BaseTableWriteReq extends TAGEBundle{
  val bankMask = UInt(NumBanks.W)
  val setIdx = UInt(BaseTableSetIdxWidth.W)
  val brMask = UInt(NumBr.W)
  val entry = Vec(NumBr, new SaturateCounter(BaseTakenCtrWidth))
}


class BaseTableResp extends TAGEBundle {
  val entries = Vec(NumBr, new SaturateCounter(BaseTakenCtrWidth))
}


class TAGETableWriteReq(implicit info: TAGETableInfo) extends TAGEBundle{
  val bankMask = UInt(NumBanks.W)
  val setIdx = UInt(SetIdxWidth.W)
  val wayOH = if (NumWays == 1) None else Some(UInt(NumWays.W))
  val entry = new TAGETableEntry
}

// 旧表仍用上面的 entries。新预测表在 1 way 时不保留长度为 1 的路向量。
class TAGETableResp(implicit info: TAGETableInfo) extends TAGEBundle {
  val entry = if (NumWays == 1) Some(new TAGETableEntry) else None
  val entries = if (NumWays > 1) Some(Vec(NumWays, new TAGETableEntry)) else None
}


class TAGEMeta extends TAGEBundle{
  val providerValid = Bool()
  val providerTableIdx = UInt(TableIdxWidth.W)
  // 1 way 没有路可区分，meta 不带这个字段。
  val providerTableWayIdx = if (NumWays > 1) Some(UInt(WayIdxWidth.W)) else None
  val providerCtr = new SaturateCounter(TakenCtrWidth)
  val providerUseful = new SaturateCounter(UsefulCtrWidth)
  val diff = Bool() // 表示provider 和alt 不同， 如果没有alt 那么就是bim
  val baseCtr = new SaturateCounter(BaseTakenCtrWidth)
}

class TAGEPredReq  extends TAGEBundle{
  val pc = UInt(pcWidth.W)
  val predPHR = new TAGEAllFoldedHistories
}

class TAGEFoldedHist(implicit info: TAGETableInfo) extends TAGEBundle {
  val forIdx: UInt      = UInt(SetIdxWidth.W)
  val forTag: Vec[UInt] = Vec(2, UInt(TagWidth.W))
}

class TAGEAllFoldedHistories extends TAGEBundle {
  val hist = MixedVec(TableInfos.map { implicit tableInfo =>
    new TAGEFoldedHist
  })
}

class TAGETrain extends TAGEBundle{
  val trainPC = UInt(pcWidth.W)
  val trainPHR = new TAGEAllFoldedHistories
  val meta = Vec(2, new TAGEMeta)
  val brValid = Vec(2, Bool())
  val brOffset = Vec(2, UInt(FetchBlockOffsetWidth.W))
  val endOffset = UInt(FetchBlockOffsetWidth.W)
  val taken = Bool()
}


