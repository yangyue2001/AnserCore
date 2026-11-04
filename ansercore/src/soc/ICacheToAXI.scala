/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

package soc

import chisel3._
import chisel3.util._

import ansercore.HasAnserParameters
import ansercore.frontend.ifu.icache.ICacheMemReq
import ansercore.frontend.ifu.icache.ICacheMemResp
import device.axi.AXI4
import device.axi.AXI4MasterIO
import device.axi.AXI4Params

// One ICache refill request is one 64-byte line. The low beat bit selects the
// 32-byte half that must come back first; the other half follows with last=1.
// Beats of different ids are not interleaved, so one request is enough.
class ICacheToAXI(params: AXI4Params) extends Module with HasAnserParameters {
  require(params.dataBits >= 64 && params.dataBits % 64 == 0, "AXI beat must be a multiple of 8 bytes")
  require(CachelineBytes == 64, "ICache bridge expects a 64-byte line")
  require(ICacheToL2DataWidth == 256, "ICache bridge expects 32-byte refill beats")
  require(ICacheToL2BeatsPerLine == 2, "ICache bridge expects two refill beats")
  require(ICacheToL2DataWidth % params.dataBits == 0, "AXI beat must divide a refill beat")
  require(
    Seq(1, 2, 4, 8, 16).contains(CachelineBytes / params.beatBytes),
    "a cacheline must be 1, 2, 4, 8, or 16 AXI beats",
  )

  val io = IO(new Bundle {
    val memReq  = Flipped(Decoupled(new ICacheMemReq))
    val memResp = Decoupled(new ICacheMemResp)
    val axi     = new AXI4MasterIO(params)
  })

  val axiBeats   = CachelineBytes / params.beatBytes
  val halfBeats  = ICacheToL2DataWidth / params.dataBits
  val beatCountW = log2Ceil(axiBeats)

  val sIdle :: sAddr :: sData :: sBeat0 :: sBeat1 :: Nil = Enum(5)
  val state = RegInit(sIdle)

  val reqId     = Reg(UInt(io.memReq.bits.id.getWidth.W))
  val critical  = Reg(Bool())
  val lineAddr  = Reg(UInt(params.addrBits.W))
  val beats     = Reg(Vec(axiBeats, UInt(params.dataBits.W)))
  val beatCount = RegInit(0.U(beatCountW.W))

  def fit(addr: UInt): UInt =
    if (params.addrBits >= addr.getWidth) addr.pad(params.addrBits)
    else addr(params.addrBits - 1, 0)

  io.axi.aw.valid := false.B
  io.axi.aw.bits  := 0.U.asTypeOf(io.axi.aw.bits)
  io.axi.w.valid  := false.B
  io.axi.w.bits   := 0.U.asTypeOf(io.axi.w.bits)
  io.axi.b.ready  := true.B

  io.memReq.ready := state === sIdle
  when(io.memReq.fire) {
    val beatAddr = io.memReq.bits.addr
    reqId    := io.memReq.bits.id
    critical := beatAddr(0)
    lineAddr := fit(Cat(beatAddr(beatAddr.getWidth - 1, 1), 0.U(6.W)))
    state    := sAddr
  }

  io.axi.ar.valid      := state === sAddr
  io.axi.ar.bits.id    := 0.U
  io.axi.ar.bits.addr  := lineAddr
  io.axi.ar.bits.len   := (axiBeats - 1).U
  io.axi.ar.bits.size  := log2Ceil(params.beatBytes).U
  io.axi.ar.bits.burst := AXI4.burstIncr
  when(io.axi.ar.fire) {
    beatCount := 0.U
    state     := sData
  }

  io.axi.r.ready := state === sData
  when(io.axi.r.fire) {
    beats(beatCount) := io.axi.r.bits.data
    when(io.axi.r.bits.last) {
      assert(beatCount === (axiBeats - 1).U, "AXI read ended before a cacheline")
      state := sBeat0
    }.otherwise {
      beatCount := beatCount + 1.U
    }
  }

  def pack(parts: Seq[UInt]): UInt = if (parts.length == 1) parts.head else Cat(parts)

  val low  = pack((0 until halfBeats).reverse.map(i => beats(i)))
  val high = pack((halfBeats until axiBeats).reverse.map(i => beats(i)))
  val firstHalf  = Mux(critical, high, low)
  val secondHalf = Mux(critical, low, high)

  io.memResp.valid     := state === sBeat0 || state === sBeat1
  io.memResp.bits.id   := reqId
  io.memResp.bits.data := Mux(state === sBeat1, secondHalf, firstHalf)
  io.memResp.bits.last := state === sBeat1
  when(io.memResp.fire) {
    state := Mux(state === sBeat0, sBeat1, sIdle)
  }
}
