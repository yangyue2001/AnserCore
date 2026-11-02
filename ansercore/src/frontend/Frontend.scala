package ansercore.frontend

import chisel3._
import chisel3.util._

class Frontend extends FrontendModule{
  class FrontendIO extends FrontendBundle {
    val resetVector = Input(UInt(pcWidth.W))
    //val fromTop = Flipped(new )
  }
}