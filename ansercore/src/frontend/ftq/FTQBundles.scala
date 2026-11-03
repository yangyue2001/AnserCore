package ansercore.frontend.ftq

import chisel3._
import chisel3.util._

import utils.CircularQueuePtr
import ansercore.frontend.FrontendParameters
import ansercore.frontend.bpu.ftb.FTBMeta
import ansercore.frontend.bpu.ras.RASMeta
import ansercore.frontend.bpu.phr.PHRMeta
import ansercore.frontend.bpu.tage.TAGEMeta
import ansercore.frontend.bpu.ittage.ITTAGEMeta
import ansercore.Parameters
import ansercore.frontend.CFIAttribute
import ansercore.frontend.bpu.HasBPUParameters


class FTQPtr(implicit p: Parameters)  extends CircularQueuePtr[FTQPtr](p.frontendParameters.ftqParameters.ftqSize)

class FTQFetchBlcokEntry extends FTQBundle {
  val startAddr       = UInt(pcWidth.W)
  val target      = UInt(pcWidth.W)
  val endOffset   = UInt(FetchBlockOffsetWidth.W)
  val taken       = Bool()
  val dirMispredict = Bool()
  val targetMispredict = Bool()
  val cfiAttribute = new CFIAttribute
}


class FTQSpecEntry extends FTQBundle {
  val rasMeta = new RASMeta
  val phrMeta = new PHRMeta
}


class FTQMetaEntry extends FTQBundle with HasBPUParameters {
  val ftbMeta = new FTBMeta
  val tageMeta = Vec(NumBr, new TAGEMeta)
  val ittageMeta = new ITTAGEMeta
}


class FTQEntry extends FTQBundle {
  val state = FTQState() // 状态  invaild prefetch fetch  commit
  val spec = new FTQSpecEntry  // for redirect 的时候 恢复用的
  val fetchBlock = new FTQFetchBlcokEntry
  val meta = new FTQMetaEntry
  val isRVC = Vec(FetchBlockNumSlots, Bool())

  def isLookup = state === FTQState.lookup
  def isFetch = state === FTQState.fetch
  def isWaitCommit = state === FTQState.waitCommit
  def isCommit = state === FTQState.commit
}

object FTQState extends ChiselEnum{
  val lookup, fetch, waitCommit, commit = Value
}

class FTQBackendReadResp extends FTQBundle{
  val fetchPC = UInt(pcWidth.W)
  val predTarget = UInt(pcWidth.W)
  val predTaken = Bool()
}


class FTQLookup extends FTQBundle {
  val lookupPC = UInt(pcWidth.W)
  val ftqPtr = new FTQPtr
}

class FTQFetch extends FTQBundle {
  val fetchPC = UInt(pcWidth.W)
  val ftqPtr = new FTQPtr
  val endOffset = UInt(FetchBlockOffsetWidth.W)
  val parallel = Bool()
  val taken = Bool()
  val target = UInt(pcWidth.W)
  val rasTopAddr = UInt(pcWidth.W)
}

class FTQPosition extends FTQBundle{
  val ptr = new FTQPtr
  val offset = UInt(FetchBlockOffsetWidth.W)
}