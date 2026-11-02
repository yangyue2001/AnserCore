package device.axi

import chisel3._
import chisel3.util._

// Returns DECERR for every request that reaches it.
class AXI4Error(
  params: AXI4Params = AXI4Params(),
  buffer: Boolean = true,
) extends AXI4Slave(params) {
  val ar = if (buffer) Queue(io.ar, 1) else io.ar
  val aw = if (buffer) Queue(io.aw, 1) else io.aw
  val w  = if (buffer) Queue(io.w, 1) else io.w

  val r = Wire(chiselTypeOf(io.r))
  ar.ready   := r.ready
  r.valid    := ar.valid
  r.bits.id   := ar.bits.id
  r.bits.data := 0.U
  r.bits.resp := AXI4.respDecerr
  r.bits.last := true.B
  io.r <> r

  val b      = Wire(chiselTypeOf(io.b))
  val idleAw = RegInit(true.B)
  val idleW  = RegInit(true.B)

  when(b.fire) {
    idleAw := true.B
  }.elsewhen(aw.valid) {
    idleAw := false.B
  }
  when(b.fire) {
    idleW := true.B
  }.elsewhen(w.valid && w.bits.last) {
    idleW := false.B
  }

  aw.ready    := b.fire
  w.ready     := b.fire || !w.bits.last
  b.valid     := !idleAw && !idleW
  b.bits.id   := aw.bits.id
  b.bits.resp := AXI4.respDecerr
  io.b <> b
}
