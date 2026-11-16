package ansercore.frontend.bpu.uftb

import chisel3._
import chisel3.util._
import ansercore.frontend.bpu.HasBPUParameters

case class UFTBParameters(
  numWays: Int = 32,
  tagWidth: Int = 16,
  ctrWidth: Int = 2,
) {
}

trait HasUFTBParameters extends HasBPUParameters{
  def uftbParameters= bpuParameters.uftbParameters

  def NumWays = uftbParameters.numWays
  def TagWidth = uftbParameters.tagWidth
  def CtrWidth = uftbParameters.ctrWidth
}
