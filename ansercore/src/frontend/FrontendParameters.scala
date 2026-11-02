package ansercore.frontend

import chisel3._
import chisel3.util._
import ansercore.frontend.bpu.BPUParameters
import ansercore.frontend.ifu.IFUParameters
import ansercore.frontend.ftq.FTQParameters
import ansercore.HasYQHParameters

case class FrontendParameters(
    bpuParameters:            BPUParameters = BPUParameters(),
    ftqParameters:            FTQParameters = FTQParameters(),
    ifuParameters:            IFUParameters = IFUParameters(),
    frontendBlockBytes: Int = 24, //fetch and predirect
) {
}


trait HasFrontendParameters extends HasYQHParameters {
  def frontendParameters = coreParams.frontendParameters

  def FTQSize = frontendParameters.ftqParameters.ftqSize
  def FTQSizeWidth = log2Ceil(FTQSize)
}

