package ansercore.frontend.bpu.tage

import chisel3._
import chisel3.util._
import scala.math.min
import ansercore.frontend.bpu.HasBPUParameters
import ansercore.frontend.bpu.phr.FoldedHistoryInfo

class TAGETableInfo(
    val size:          Int,
    val numWays:       Int,
    val historyLength: Int,
    val tagBits:       Int,
    val numBanks:      Int,
) {
  require(size > 0, "Size must be > 0")
  require(numWays > 0, "NumWays must be > 0")
  require(historyLength > 0, "HistoryLength must be > 0")
  require(tagBits > 0, "TagBits must be > 0")
  require(numBanks > 0, "NumBanks must be > 0")
  require(size % (numWays * numBanks) == 0, "size must divide by numWays * numBanks")


  def numSets: Int = size / numWays / numBanks

  def getFoldedHistoryInfoSet: Set[FoldedHistoryInfo] = {
    Set( // FoldedHistoryInfo(unfolded history length, folded history length)
      new FoldedHistoryInfo(historyLength, min(historyLength, log2Ceil(numSets))),
      new FoldedHistoryInfo(historyLength, min(historyLength, tagBits)),
      new FoldedHistoryInfo(historyLength, min(historyLength, tagBits - 1))
    )
  }

  def getTAGEFoldedHistoryInfo: List[FoldedHistoryInfo] = {
      List( // FoldedHistoryInfo(unfolded history length, folded history length)
        new FoldedHistoryInfo(historyLength, min(historyLength, log2Ceil(numSets))),
        new FoldedHistoryInfo(historyLength, min(historyLength, tagBits)),
        new FoldedHistoryInfo(historyLength, min(historyLength, tagBits - 1))
      )
  }
}


case class TAGEParameters(
  numBanks: Int = 4,
  numWays: Int = 2,
  takenCtrWidth: Int = 3,
  usefulCtrWidth: Int = 1,
  usefulResetCtrWidth: Int = 8,
  useAltOnNaWidth: Int = 7,
  numUseAltOnNa: Int = 128, // perSlot
  baseTakenCtrWidth: Int = 2,
  baseTableSize: Int = 4096,

) {
  val tableInfos: Seq[TAGETableInfo] = Seq(
    // TAGETableInfo(Size, NumWays, HistoryLength, tagBits, numBanks)
    new TAGETableInfo(1024, numWays, 6, 13, numBanks),
    new TAGETableInfo(1024, numWays, 9, 13, numBanks) ,
    new TAGETableInfo(1024, numWays, 17, 13, numBanks),
    new TAGETableInfo(1024, numWays, 31, 13, numBanks),
  )

  require(numBanks > 0)
  require(numWays > 0)

}

trait HasTAGEParameters extends HasBPUParameters {
  def tageParameters= bpuParameters.tageParameters

  def NumBanks = tageParameters.numBanks
  def NumWays = tageParameters.numWays
  def BankIdxWidth = log2Ceil(NumBanks)
  // 1 way 不产生路号。这个宽度只给多 way 的 meta 用。
  def WayIdxWidth = log2Ceil(NumWays)
  // 1 bank 不占 PC 低位。set 和 tag 从 bit 0 取。
  def BankPCLow = if (NumBanks == 1) 0 else BankIdxWidth

  //table
  def TableInfos: Seq[TAGETableInfo] = tageParameters.tableInfos
  def NumTables:     Int = TableInfos.length
  def TableIdxWidth: Int = log2Ceil(NumTables)

  def TakenCtrWidth = tageParameters.takenCtrWidth
  def UsefulCtrWidth = tageParameters.usefulCtrWidth
  def UsefulResetCtrWidth: Int = tageParameters.usefulResetCtrWidth
  def UseAltOnNaWidth:     Int = tageParameters.useAltOnNaWidth
  def NumUseAltOnNa = tageParameters.numUseAltOnNa
  def UseAltOnNaIdxWidth = log2Ceil(NumUseAltOnNa)

  //base
  def BaseTakenCtrWidth: Int = tageParameters.baseTakenCtrWidth
  def BaseTableSize: Int = tageParameters.baseTableSize
  def BaseTableSets: Int = BaseTableSize/ NumBanks / NumBr
  def BaseTableSetIdxWidth :Int = log2Ceil(BaseTableSets)
  require(BaseTableSets * NumBanks * NumBr == BaseTableSize, "base table size must divide by banks and branches")

  // per table parameters
  def NumSets(implicit info:     TAGETableInfo): Int = info.numSets
  def SetIdxWidth(implicit info: TAGETableInfo): Int = log2Ceil(NumSets)
  def TagWidth(implicit info: TAGETableInfo): Int = info.tagBits

}
