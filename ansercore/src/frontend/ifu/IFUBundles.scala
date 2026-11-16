package ansercore.frontend.ifu

import chisel3._
import chisel3.util._
import ansercore.frontend._


class IFUPrediction extends IFUBundle{
  val taken = Bool()
  val target = UInt(pcWidth.W)
  val rasTopAddr = UInt(pcWidth.W)
  val endOffset = UInt(FetchBlockOffsetWidth.W)
}