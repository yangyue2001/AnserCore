package ansercore.mmu.l2Tlb


import chisel3._
import chisel3.util._

import ansercore.mmu.HasMmuParameters
import ansercore.mmu.MmuConstant.ColtFactorWidth

case class PteCacheParams(
  val size: Int,
  val numWays: Int,
  val replacer: String,
){
  def numSets: Int = size/ numWays

  require(numWays > 1) //Don't suppot direct mapping.
}

case class L2TlbParameters(
  pteCacheParams: Seq[PteCacheParams] = Seq(
    PteCacheParams(2048, 4, "plru"), //leaf 0
    PteCacheParams(512, 4, "plru"), // leaf 1
  )
) {
}


trait HasL2TlbParameters extends HasMmuParameters {
  def l2TlbParameters = mmuParameters.l2TlbParameters


  //per pte parameters
  def numSets(implicit info: PteCacheParams): Int = info.numSets
  def setIdxWidth(implicit info: PteCacheParams) = log2Ceil(numSets)
  def vTagWidth(implicit info: PteCacheParams) = vpnWidth - setIdxWidth - ColtFactorWidth
}