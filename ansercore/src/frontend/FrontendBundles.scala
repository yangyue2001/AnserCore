package ansercore.frontend

import chisel3._
import chisel3.util._

import ansercore.frontend.bpu._
import ansercore.frontend.ifu._
import ansercore.frontend.ftq._
import ansercore.frontend.ifu.predecode.PredecodeInfo
import ansercore.frontend.ifu.ibuffer.InstPacket
import ansercore.BackendRedirect
import ansercore.FrontendToBackendIO

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
  val s3Override = Output(Bool())
  val lookUp = Decoupled(new FTQLookup)
  val fetch = Decoupled(new FTQFetch)
}

class IFUToFTQIO  extends FrontendBundle {
  val ftqPtr = new FTQPtr
  val startPc = UInt(pcWidth.W)
  val preDecodeInfo = new PredecodeInfo
}


class IFUToBackendIO extends FrontendBundle{
  val inst = Vec(DecodeWidth, Decoupled(new InstPacket))
}


class QuerResp extends FrontendBundle {
  val pc     = UInt(pcWidth.W)
  val nextPC = UInt(pcWidth.W)
  val taken  = Bool()
  val target = UInt(pcWidth.W)
}

class BackendToFTQIO extends FrontendBundle {
  val commit    = Valid(new FTQPosition)
  val redirect  = Valid(new BackendRedirect)
  val predQuery = Output(new FTQPosition)
}


class FTQToBackendIO extends FrontendBundle{
  val predQueryResp = Output(new QuerResp)
}


class FetchBlock extends FrontendBundle{
  val startPC = UInt(pcWidth.W)
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
