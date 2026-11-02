package ansercore.frontend.ifu
import chisel3._
import chisel3.util._

import ansercore.frontend.ifu.ibuffer.IBufferParameters
import ansercore.frontend.ifu.icache.ICacheParameters
import ansercore.frontend.HasFrontendParameters

case class IFUParameters(
  ibuffer: IBufferParameters = IBufferParameters(),
  icache: ICacheParameters = ICacheParameters(),
) {
}

trait HasIFUParameters extends HasFrontendParameters {
  def ifuParameters: IFUParameters = frontendParameters.ifuParameters

  def InstBitsWidth = FrontendBlockBytes * 8
}