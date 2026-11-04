package ansercore.mmu

import chisel3._
import chisel3.util._

import ansercore.HasAnserParameters
import ansercore.mmu.pmp.PmpParameters
import ansercore.mmu.l1Tlb.L1TlbParameters
import ansercore.mmu.l2Tlb.L2TlbParameters
import ansercore.mmu.ptw.PtwParameters
import ansercore.mmu.MMUConstant._
import ansercore.mmu.SvMode

case class MMUParameters(
  val pmpParameters: PmpParameters = PmpParameters(),
  val l1TlbParameters: L1TlbParameters = L1TlbParameters(),
  val l2TlbParameters: L2TlbParameters = L2TlbParameters(),
  val ptwParameters: PtwParameters = PtwParameters(),
  val svMode: SvMode = SvMode.Sv48,
  val useSoftMmu: Boolean = true,
)

trait HasMMUParameters extends HasAnserParameters {
  def mmuParameters = coreParams.mmuParameters

  def svMode = coreParams.mmuParameters.svMode
  def useSoftMmu = mmuParameters.useSoftMmu
  def compressedPpnWidth = ppnWidth - ColtFactorWidth

  def levels = svMode.Levels
  def levelWidth = log2Ceil(levels)

  require(ppnWidth <= MMUConstant.PpnWidth)
}