package ansercore.frontend.bpu.phr

import chisel3._
import chisel3.util._
import ansercore.frontend.bpu.HasBPUParameters
import ansercore.frontend.bpu.NamedTuple

class FoldedHistoryInfo(
    val HistoryLength: Int,
    val FoldedLength:  Int
) extends NamedTuple[(Int, Int)] {
  require(HistoryLength > 0, "HistoryLength must be > 0")
  require(FoldedLength > 0, "FoldedLength must be > 0")

  def asTuple: (Int, Int) =
    (HistoryLength, FoldedLength)
}

case class PHRParameters(
  shamt: Int = 2,
  pathHashWidth: Int = 14,
  ftqFullFix: Int =  2,
){
  require(pathHashWidth % shamt == 0)
}


trait HasPHRParameters extends HasBPUParameters {
  def phrParameters = bpuParameters.phrParameters

  def Shamt = phrParameters.shamt
  def HashWidth = phrParameters.pathHashWidth
  def FTQFullFix = phrParameters.ftqFullFix

  def HashChunks = HashWidth / Shamt
  def RemainChunks = HashChunks - 1

  def MaxHistLens = MaxTableHistoryLength
  def MaxHistChunks = (MaxHistLens + Shamt - 1) / Shamt

  // 每条 taken 插入 Shamt。FTQ 中每个块最多记一次，再留 FTQFullFix(BPU 内部流水线) 个Shamt。
  def HistoryLengthRaw = MaxHistLens + (FTQSize + FTQFullFix) * Shamt
  def HistoryLength = (HistoryLengthRaw + Shamt - 1) / Shamt * Shamt
  def HistoryChunks = HistoryLength / Shamt
  def PtrWidth = log2Up(HistoryChunks)

 //per FoldedHistoryInfo
  def FoldHistoryLength(implicit info: FoldedHistoryInfo): Int = info.HistoryLength
  def FoldedLength(implicit info: FoldedHistoryInfo): Int = info.FoldedLength
  require(Shamt > 0, "Shamt must be > 0")
  require(HashWidth > 0, "HashWidth must be > 0")
  require(HashWidth % Shamt == 0, "HashWidth must divide by Shamt")
  require(FTQFullFix >= 0, "FTQFullFix must be >= 0")
  require(MaxHistLens > 0, "MaxHistLens must be > 0")
  require(HistoryChunks > MaxHistChunks, "PHR storage must be longer than one logical history")
}
