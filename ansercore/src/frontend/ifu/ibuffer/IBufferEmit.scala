package ansercore.frontend.ifu.ibuffer

import circt.stage.ChiselStage

object IBufferEmit extends App {
  val chirrtl = ChiselStage.emitCHIRRTL(new IBuffer)
  println(s"IBuffer chirrtl bytes ${chirrtl.length}")
}
