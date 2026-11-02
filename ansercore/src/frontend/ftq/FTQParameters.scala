package ansercore.frontend.ftq

import chisel3._
import chisel3.util._
import ansercore.frontend.HasFrontendParameters

case class FTQParameters(
  ftqSize: Int = 32
) {
}

trait HasFTQParameters extends HasFrontendParameters{
}