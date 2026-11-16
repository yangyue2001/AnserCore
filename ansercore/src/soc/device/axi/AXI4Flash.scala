package device.axi

import chisel3._
import chisel3.util._
import difftest.common.DifftestFlash

// The DPI flash returns data one cycle after en, which lines up with AXI4SlaveAdapter
// entering the read-data state.
class AXI4Flash(params: AXI4Params = AXI4Params()) extends Module {
  require(params.addrBits > 16)
  require(params.dataBits == 64, "DifftestFlash returns 64-bit beats")

  val io = IO(new AXI4SlaveIO(params))

  val slave = Module(new AXI4SlaveAdapter(params))
  io <> slave.io.axi

  val beatBits  = log2Ceil(params.beatBytes)
  val flashAddr = Cat(slave.io.raddr(15, beatBits), 0.U(beatBits.W))
  val flash     = DifftestFlash()
  flash.en   := slave.io.arFire
  flash.addr := Cat(0.U(16.W), flashAddr)
  slave.io.rdata := flash.data
}
