package ansercore.mmu

import chisel3._
import chisel3.util._
import ansercore.FrontendToMMUIO
import ansercore.MMUToFrontendIO
import ansercore.mmu.softMMU.SoftMMU

class MMU extends MMUModule {
  class MMUIO extends MMUBundle {
    val mmuCtr = Input(new MMUCtr)
    val fromFrontend = Flipped(new FrontendToMMUIO)
    val toFrontend = new MMUToFrontendIO
  }
  val io = IO(new MMUIO)

  // 外面只认这组口。软 MMU 还是硬件 MMU 由 useSoftMmu 决定。
  if (useSoftMmu) {
    val softMmu = Module(new SoftMMU)
    softMmu.io.mmuCtr := io.mmuCtr
    softMmu.io.fromFrontend := io.fromFrontend
    io.toFrontend := softMmu.io.toFrontend
  } else {
    require(false, "hardware MMU is not implemented")
  }
}
