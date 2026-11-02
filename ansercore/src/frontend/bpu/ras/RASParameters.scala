package ansercore.frontend.bpu.ras

import chisel3._
import chisel3.util._
import ansercore.frontend.bpu.HasBPUParameters

case class RASParameters(
    numEntrys: Int = 16,
) {
}

trait HasRASParameters extends HasBPUParameters {
  def rasParameters = bpuParameters.rasParameters

  def NumEntrys = rasParameters.numEntrys
  def NumEntrysWidth = log2Ceil(NumEntrys)
}