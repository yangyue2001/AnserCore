package device.axi

import chisel3._
import chisel3.util._

// Register offsets, in the low 4 bits of the AXI address: 0 RX, 4 TX, 8 stat, 0xc ctrl.
// TX observes the byte in data lane 4. The regmap mask is still shifted by waddr(2,0).
class AXI4Uart(params: AXI4Params = AXI4Params()) extends Module {
  require(params.dataBits == 64)

  val io = IO(new Bundle {
    val axi  = new AXI4SlaveIO(params)
    val uart = new UartIO
  })

  val slave = Module(new AXI4SlaveAdapter(params))
  io.axi <> slave.io.axi

  val txfifo = Reg(UInt(32.W))
  val stat   = RegInit(0.U(32.W))
  val ctrl   = RegInit(0.U(32.W))

  val txDataPos = 32
  io.uart.valid_o := slave.io.waddr(3, 0) === 4.U && slave.io.wFire
  io.uart.ch_o    := slave.io.wData(7 + txDataPos, txDataPos)
  io.uart.valid_in  := slave.io.raddr(3, 0) === 0.U && slave.io.rFire

  val wmask = AXI4.maskExpand(slave.io.wStrb >> slave.io.waddr(2, 0))
  val regs = Seq(
    0x0 -> (io.uart.ch_in.pad(params.dataBits), None),
    0x4 -> (txfifo.pad(params.dataBits), Some(txfifo)),
    0x8 -> (stat.pad(params.dataBits), Some(stat)),
    0xc -> (ctrl.pad(params.dataBits), Some(ctrl)),
  )

  slave.io.rdata := MuxLookup(slave.io.raddr(3, 0), 0.U(params.dataBits.W))(regs.map { case (off, (value, _)) =>
    off.U -> value
  })

  regs.foreach { case (off, (_, reg)) =>
    reg.foreach { target =>
      when(slave.io.wFire && slave.io.waddr(3, 0) === off.U) {
        val updated = AXI4.maskData(target, slave.io.wData, wmask)
        target := updated(target.getWidth - 1, 0)
      }
    }
  }
}
