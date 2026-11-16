package ansercore.frontend.bpu.ittage

import chisel3._
import chisel3.util._
import chisel3.util.log2Ceil

import ansercore.frontend.bpu.HasBPUParameters
import ansercore.frontend.bpu.tage.TAGETableInfo

case class ITTAGEParameters(
  numBanks: Int = 2,
  numWays: Int = 2,
  ctrWidth: Int = 2,
  usefulCtrWidth: Int = 1,
  useAltOnNaWidth: Int = 4,
  numUseAltOnNa: Int = 32,
  usefulResetCtrWidth: Int = 8,
  offsetWidth: Int = 20,
  numRegionWays: Int = 16,
  regionReplacement: String = "plru",
) {
  require(numRegionWays >= 2, "region table needs at least 2 entries")
  require(
    Seq("random", "lru", "plru").contains(regionReplacement),
    "regionReplacement must be random, lru, or plru",
  )
  val tableInfos: Seq[TAGETableInfo] = Seq(
    // TAGETableInfo(Size, NumWays, HistoryLength, tagBits, numBanks)
    new TAGETableInfo(256, numWays, 4, 9, numBanks),
    new TAGETableInfo(256, numWays, 8, 9, numBanks) ,
    new TAGETableInfo(512, numWays, 13, 9, numBanks),
    new TAGETableInfo(512, numWays, 16, 9, numBanks),
    new TAGETableInfo(512, numWays, 32, 9, numBanks),
  )
}


trait HasITTAGEParameters extends HasBPUParameters {
  def ittageParameters: ITTAGEParameters = bpuParameters.ittageParameters

  def TableInfos: Seq[TAGETableInfo] = ittageParameters.tableInfos
  def NumTables:  Int                  = TableInfos.length
  def TableIdxWidth: Int = log2Ceil(NumTables)

  def NumBanks:     Int = ittageParameters.numBanks
  def NumWays: Int = ittageParameters.numWays
  def BankIdxWidth: Int = log2Ceil(NumBanks)
  def WayIdxWidth: Int = log2Ceil(NumWays)

  def HasMultiWay: Boolean = NumWays > 1

  def ConfidenceCtrWidth: Int = ittageParameters.ctrWidth
  def UsefulCtrWidth:     Int = ittageParameters.usefulCtrWidth
  def UseAltOnNaWidth:    Int = ittageParameters.useAltOnNaWidth
  def TargetOffsetWidth:  Int = ittageParameters.offsetWidth
  def UsefulResetCtrWidth:Int = ittageParameters.usefulResetCtrWidth
  def NumUseAltOnNa: Int = ittageParameters.numUseAltOnNa
  def UseAltOnNaIdxWidth: Int = log2Ceil(NumUseAltOnNa)
  /* *** Region *** */
  def RegionNums:     Int     = ittageParameters.numRegionWays
  def RegionIdxWidth: Int = log2Ceil(RegionNums)
  def RegionReplacement: String = ittageParameters.regionReplacement
  def RegionWidth:     Int    = pcWidth - TargetOffsetWidth

  //per table parameters
  def NumSets(implicit info: TAGETableInfo): Int = info.numSets
  def SetIdxWidth(implicit info: TAGETableInfo): Int = log2Ceil(NumSets)
  def TagWidth(implicit info: TAGETableInfo): Int = info.tagBits


  //requre
  require(RegionWidth > 0, "region width must be positive")
  require(pcWidth == RegionWidth + TargetOffsetWidth)
}