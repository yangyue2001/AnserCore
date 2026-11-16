package ansercore.frontend.bpu.ftb

import chisel3._
import chisel3.util._
import ansercore.frontend.bpu.HasBPUParameters
import utils.ReplacementPolicy

case class FTBParameters(
  numWays: Int = 4,
  ftbSize: Int = 2048,
  numBanks: Int = 4,
  tagBits: Int = 20,
  replacement: String = "plru",
) {
  require(numWays > 0)
  require(ftbSize > 0)
  require(numBanks > 0)
  require(ftbSize % numBanks == 0)
  require(tagBits > 0)
}

trait HasFTBParameters extends HasBPUParameters {
  def ftbParameters = bpuParameters.ftbParameters

  def NumWays = ftbParameters.numWays
  def NumBanks = ftbParameters.numBanks
  def TagWidth = ftbParameters.tagBits
  def WayIdxWidth = log2Ceil(NumWays)
  def BankIdxWidth = log2Ceil(NumBanks)
  def FTBSize = ftbParameters.ftbSize
  def NumSets = FTBSize/ NumWays / NumBanks
  def SetIdxWidth = log2Ceil(NumSets)
  def Replacement = ReplacementPolicy(ftbParameters.replacement, NumWays)
}
