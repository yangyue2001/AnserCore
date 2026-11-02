package ansercore.frontend

import chisel3._
import chisel3.util._

import ansercore.frontend.bpu._
import ansercore.frontend.ifu._
import ansercore.frontend.ftq._
import ansercore.frontend.ifu.predecode.PredecodeInfo
import ansercore.BackendRedirect

class BPUToFTQIO extends FrontendBundle{
  val s1Fetch = Decoupled(UInt(pcWidth.W))
  val s3Result = Valid(new BPUS3Result)
}

class FTQToBPUIO extends FrontendBundle{
  val redirect    = Valid(new BPURedirect)
  val train       = Decoupled(new BPUTrain)
  val bpuPtr      = Output(new FTQPtr)
}

class FTQToIFUIO extends FrontendBundle{
  val s3Override = Bool()
  val lookUp = Decoupled(new FTQLookup)
  val fetch = Decoupled(new FTQFetch)
}

class FTQToBackendIO extends FrontendBundle{
}

class BackendToFTQIO extends FrontendBundle{
  val commit = Flipped(Valid(new FTQPosition))
  val redirect = Flipped(Valid(new BackendRedirect))
}

class IFUToFTQIO  extends FrontendBundle {
  val ftqPtr = Output(new FTQPtr)
  val preDecodeInfo = Valid(new PredecodeInfo)
}

class IFUToBackendIO extends FrontendBundle{
}


class FetchBlock extends FrontendBundle{
  val startPc = UInt(pcWidth.W)
  val endOffset = UInt(FetchBlockOffsetWidth.W)
  val target = UInt(pcWidth.W)
}

/* *** public const & type *** */
class CFIAttribute extends Bundle {
  val cfiType = CFITypte()
  val rasAction = RASAction()

  def isNone:        Bool = cfiType === CFITypte.none
  def isConditional: Bool = cfiType === CFITypte.conditional
  def isDirect:      Bool = cfiType === CFITypte.direct
  def isIndirect:    Bool = cfiType === CFITypte.indirect
  // hasPop = isPop || isPushAndPop, hasPush = isPush || isPushAndPop
  def hasPop:  Bool = (rasAction === RASAction.pop) || (rasAction === RASAction.pushAndPop)
  def hasPush: Bool = (rasAction === RASAction.push) || (rasAction === RASAction.pushAndPop)

  def needITTAGE: Bool = isIndirect && !hasPop
  def isUnconditional : Bool =  isDirect || isIndirect
}

object CFIAttribute {
}

object RASAction extends ChiselEnum{
  val none, pop, push, pushAndPop = Value
}

object CFITypte extends ChiselEnum{
  val none, conditional, direct, indirect = Value
}

object FrontendExcept extends ChiselEnum{
  val none, pageFault, accessFault = Value
}
