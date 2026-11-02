package ansercore.mmu.l1Tlb

import chisel3._
import chisel3.util._
import ansercore.mmu.HasMmuParameters

class PteCacheParams(
  val size: Int,
  val numWays: Int,
  val replacer: String,
)

case class L1TlbParameters(
  val itlbSize: Int = 48,
  val dtlbSize: Int = 48,
  val dtlbOutstanding: Int = 8,
) {
}


trait HasL1TlbParameters extends HasMmuParameters {
  def l1TlbParameters = mmuParameters.l1TlbParameters

  def dtlbMissIdWidth = log2Ceil(l1TlbParameters.dtlbOutstanding)
  def l1MissIdWidth = dtlbMissIdWidth + 1
}