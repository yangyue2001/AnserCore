package ansercore.frontend.bpu


import chisel3._
import chisel3.util._
import ansercore.frontend.bpu.ftb.FTBParameters
import ansercore.frontend.bpu.ittage.ITTAGEParameters
import ansercore.frontend.bpu.phr.PHRParameters
import ansercore.frontend.bpu.ras.RASParameters
import ansercore.frontend.bpu.uftb.UFTBParameters
import ansercore.frontend.HasFrontendParameters
import ansercore.frontend.bpu.tage.TAGEParameters
import ansercore.frontend.bpu.phr.FoldedHistoryInfo

case class BPUParameters(
  rasParameters: RASParameters = RASParameters(),
  uftbParameters: UFTBParameters = UFTBParameters(),
  ftbParameters: FTBParameters = FTBParameters(),
  tageParameters: TAGEParameters = TAGEParameters(),
  ittageParameters: ITTAGEParameters = ITTAGEParameters(),
  phrParameters: PHRParameters = PHRParameters(),
) {
}

trait HasBPUParameters extends HasFrontendParameters{
  def bpuParameters: BPUParameters = frontendParameters.bpuParameters

  def NumBr = 2

  def AllFoldedHistoryInfo: Set[FoldedHistoryInfo] =
    bpuParameters.tageParameters.tableInfos.map {
      _.getFoldedHistoryInfoSet
    }.reduce(_ ++ _) ++
    bpuParameters.ittageParameters.tableInfos.map {
      _.getFoldedHistoryInfoSet
    }.reduce(_ ++ _)

  def MaxTableHistoryLength: Int = (
    bpuParameters.tageParameters.tableInfos.map(_.historyLength) ++
    bpuParameters.ittageParameters.tableInfos.map(_.historyLength)
  ).max

}