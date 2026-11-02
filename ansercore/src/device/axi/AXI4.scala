package device.axi

import chisel3._
import chisel3.util._

// Diplomacy-free AXI4 used by MMIO devices. Only the fields those devices consume are kept.
case class AXI4Params(
  addrBits: Int = 32,
  dataBits: Int = 64,
  idBits:   Int = 14,
) {
  require(addrBits > 0, "AXI4 address width must be positive")
  require(dataBits > 0 && dataBits % 8 == 0, "AXI4 data width must be a positive multiple of 8")
  require(idBits > 0, "AXI4 id width must be positive")

  def beatBytes: Int = dataBits / 8
}

class AXI4BundleA(params: AXI4Params) extends Bundle {
  val id    = UInt(params.idBits.W)
  val addr  = UInt(params.addrBits.W)
  val len   = UInt(8.W)
  val size  = UInt(3.W)
  val burst = UInt(2.W)
}

class AXI4BundleW(params: AXI4Params) extends Bundle {
  val data = UInt(params.dataBits.W)
  val strb = UInt(params.beatBytes.W)
  val last = Bool()
}

class AXI4BundleB(params: AXI4Params) extends Bundle {
  val id   = UInt(params.idBits.W)
  val resp = UInt(2.W)
}

class AXI4BundleR(params: AXI4Params) extends Bundle {
  val id   = UInt(params.idBits.W)
  val data = UInt(params.dataBits.W)
  val resp = UInt(2.W)
  val last = Bool()
}

class AXI4SlaveIO(params: AXI4Params) extends Bundle {
  val aw = Flipped(Decoupled(new AXI4BundleA(params)))
  val w  = Flipped(Decoupled(new AXI4BundleW(params)))
  val b  = Decoupled(new AXI4BundleB(params))
  val ar = Flipped(Decoupled(new AXI4BundleA(params)))
  val r  = Decoupled(new AXI4BundleR(params))
}

// Master drives AR/AW/W and receives R/B. Direction is the flip of AXI4SlaveIO, so the two connect with <>.
class AXI4MasterIO(params: AXI4Params) extends Bundle {
  val aw = Decoupled(new AXI4BundleA(params))
  val w  = Decoupled(new AXI4BundleW(params))
  val b  = Flipped(Decoupled(new AXI4BundleB(params)))
  val ar = Decoupled(new AXI4BundleA(params))
  val r  = Flipped(Decoupled(new AXI4BundleR(params)))
}

object AXI4 {
  val respOkay   = 0.U(2.W)
  val respDecerr = 3.U(2.W)
  val burstIncr  = 1.U(2.W)

  def maskExpand(strb: UInt): UInt =
    Cat(strb.asBools.map(bit => Fill(8, bit)).reverse)

  def maskData(oldData: UInt, newData: UInt, fullMask: UInt): UInt = {
    val width   = fullMask.getWidth
    val oldWide = oldData.pad(width)
    val newWide = newData.pad(width)
    (newWide & fullMask) | (oldWide & (~fullMask).asUInt)
  }

  // Byte lane mask. mask(0) is the byte selected by addr(log2Ceil(beatBytes)-1, 0).
  def maskGen(addrLo: UInt, lgSize: UInt, beatBytes: Int): UInt = {
    require(beatBytes >= 1 && isPow2(beatBytes))
    val lgBytes = log2Ceil(beatBytes)
    if (beatBytes == 1) {
      1.U
    } else {
      val sizeOH = UIntToOH(lgSize | 0.U(lgBytes.W), lgBytes) | 1.U

      def helper(i: Int): Seq[(Bool, Bool)] = {
        if (i == 0) {
          Seq((lgSize >= lgBytes.U, true.B))
        } else {
          val sub  = helper(i - 1)
          val size = sizeOH(lgBytes - i)
          val bit  = addrLo(lgBytes - i)
          Seq.tabulate(1 << i) { j =>
            val (subAcc, subEq) = sub(j / 2)
            val eq               = subEq && (if (j % 2 == 1) bit else !bit)
            val acc              = subAcc || (size && eq)
            (acc, eq)
          }
        }
      }

      Cat(helper(lgBytes).map(_._1).reverse)
    }
  }
}

// A slave whose only port is AXI4. Devices with extra IO compose AXI4SlaveIO instead.
abstract class AXI4Slave(val params: AXI4Params) extends Module {
  val io = IO(new AXI4SlaveIO(params))
}

// Adapts an AXI4 slave port to a held address, write data, and read response.
// One outstanding transaction. Read has priority over write.
class AXI4SlaveAdapter(val params: AXI4Params) extends Module {
  val io = IO(new Bundle {
    val axi    = new AXI4SlaveIO(params)
    val raddr  = Output(UInt(params.addrBits.W))
    val waddr  = Output(UInt(params.addrBits.W))
    val rdata  = Input(UInt(params.dataBits.W))
    val arFire = Output(Bool())
    val rFire  = Output(Bool())
    val wFire  = Output(Bool())
    val wData  = Output(UInt(params.dataBits.W))
    val wStrb  = Output(UInt(params.beatBytes.W))
  })

  val axi   = io.axi
  val sIdle :: sRdata :: sWdata :: sWresp :: Nil = Enum(4)
  val state = RegInit(sIdle)

  switch(state) {
    is(sIdle) {
      when(axi.ar.fire) { state := sRdata }
      when(axi.aw.fire) { state := sWdata }
    }
    is(sRdata) {
      when(axi.r.fire && axi.r.bits.last) { state := sIdle }
    }
    is(sWdata) {
      when(axi.w.fire && axi.w.bits.last) { state := sWresp }
    }
    is(sWresp) {
      when(axi.b.fire) { state := sIdle }
    }
  }

  val readBeat = RegInit(0.U(8.W))
  val readLen  = RegEnable(axi.ar.bits.len, 0.U, axi.ar.fire)
  val readAddr = RegEnable(axi.ar.bits.addr, 0.U, axi.ar.fire)
  io.raddr       := Mux(axi.ar.fire, axi.ar.bits.addr, readAddr)
  axi.r.bits.last := readBeat === Mux(axi.ar.fire, axi.ar.bits.len, readLen)
  when(axi.r.fire) {
    readBeat := Mux(axi.r.bits.last, 0.U, readBeat + 1.U)
  }
  when(axi.ar.fire) {
    assert(axi.ar.bits.burst === AXI4.burstIncr, "only support burst incr")
    assert(
      axi.ar.bits.len === 0.U || axi.ar.bits.len === 1.U || axi.ar.bits.len === 3.U ||
        axi.ar.bits.len === 7.U || axi.ar.bits.len === 15.U
    )
  }
  when(axi.aw.fire) {
    assert(axi.aw.bits.burst === AXI4.burstIncr, "only support burst incr")
  }

  val writeBeat = RegInit(0.U(8.W))
  val writeAddr = RegEnable(axi.aw.bits.addr, 0.U, axi.aw.fire)
  io.waddr := Mux(axi.aw.fire, axi.aw.bits.addr, writeAddr)
  when(axi.w.fire) {
    writeBeat := Mux(axi.w.bits.last, 0.U, writeBeat + 1.U)
  }

  axi.ar.ready    := state === sIdle
  axi.r.valid     := state === sRdata
  axi.r.bits.resp := AXI4.respOkay
  axi.r.bits.data := io.rdata
  axi.r.bits.id   := RegEnable(axi.ar.bits.id, 0.U, axi.ar.fire)

  axi.aw.ready    := state === sIdle && !axi.ar.valid
  axi.w.ready     := state === sWdata
  axi.b.valid     := state === sWresp
  axi.b.bits.resp := AXI4.respOkay
  axi.b.bits.id   := RegEnable(axi.aw.bits.id, 0.U, axi.aw.fire)

  io.arFire := axi.ar.fire
  io.rFire  := axi.r.fire
  io.wFire  := axi.w.fire
  io.wData  := axi.w.bits.data
  io.wStrb  := axi.w.bits.strb
}
