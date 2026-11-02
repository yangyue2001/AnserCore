package device.axi

import chisel3._
import chisel3.util._

// Not an AXI4SlaveAdapter: one beat, read wins, and the regmap accepts AW and W together.
// A 2-entry queue holds the response. Read/write side effects happen on accept, not on
// r.fire/b.fire. The returned read data is the pre-update value captured into that queue.
//
// beatBytes is 8. Registers are the bytes at offsets 0 and 4 of each 8-byte word:
//   0x00/0x04 RBR/THR/DLL, IER/DLM
//   0x08/0x0C IIR/FCR, LCR
//   0x10/0x14 MCR, LSR
//   0x18/0x1C MSR, SCR
// The index is only 2 bits, so addr(4, 3) selects the word. Higher address bits alias.
class AXI4Uart16550(
  params:    AXI4Params = AXI4Params(),
  fifoDepth: Int = 16,
) extends Module {
  require(params.addrBits >= 5)
  require(params.dataBits == 64, "16550 regmap beatBytes is 8")
  require(fifoDepth >= 1)

  val io = IO(new Bundle {
    val axi  = new AXI4SlaveIO(params)
    val uart = new UartIO
    val tx   = Decoupled(UInt(8.W))
    val rx   = Flipped(Decoupled(UInt(8.W)))
    val irq  = Output(Bool())
  })

  val axi = io.axi
  val rxTriggerLut = VecInit(1.U(4.W), 4.U(4.W), 8.U(4.W), 14.U(4.W))

  val rbr = RegInit(0.U(8.W))
  val thr = RegInit(0.U(8.W))
  val thrValid = RegInit(false.B)
  val ier = RegInit(0.U(8.W))
  val lcr = RegInit(0.U(8.W))
  val mcr = RegInit("h08".U(8.W))
  val lsrReg = RegInit("h60".U(8.W))
  val msrReg = RegInit("hB0".U(8.W))
  val scr = RegInit(0.U(8.W))
  val dll = RegInit(0.U(8.W))
  val dlm = RegInit(0.U(8.W))
  val fcrSticky = RegInit(0.U(8.W))

  val thrIPending = RegInit(false.B)
  val timeoutPending = RegInit(false.B)

  val recvFifo = Module(new Queue(UInt(8.W), fifoDepth))
  val xmitFifo = Module(new Queue(UInt(8.W), fifoDepth))

  val rxFlush = RegInit(false.B)
  val txFlush = RegInit(false.B)

  val lsrNext = lsrReg.asBools.map(bit => WireDefault(bit))
  val msrNext = WireDefault("hB0".U(8.W))
  def setLsr(bits: Int): Unit = (0 until 8).foreach { i =>
    if (((bits >> i) & 1) != 0) lsrNext(i) := true.B
  }
  def clrLsr(bits: Int): Unit = (0 until 8).foreach { i =>
    if (((bits >> i) & 1) != 0) lsrNext(i) := false.B
  }

  val recvEnq = WireInit(false.B)
  val recvData = WireDefault(0.U(8.W))
  val recvPop = WireInit(false.B)
  recvFifo.io.enq.valid := recvEnq
  recvFifo.io.enq.bits := recvData
  recvFifo.io.deq.ready := recvPop || rxFlush
  when(rxFlush && recvFifo.io.count === 0.U) { rxFlush := false.B }

  val xmitPop = WireInit(false.B)
  xmitFifo.io.enq.valid := false.B
  xmitFifo.io.enq.bits := 0.U
  xmitFifo.io.deq.ready := xmitPop || txFlush
  when(txFlush && xmitFifo.io.count === 0.U) { txFlush := false.B }

  // Original baud math is unused. charCycles is forced to 1 so simulation does not
  // wait for a real bit period. Firing still takes charCycles + 1 busy cycles.
  val charCycles = 1.U
  val timeoutReload = charCycles << 2
  val timeoutCnt = RegInit(0.U(32.W))

  val txBusy = RegInit(false.B)
  val txCounter = RegInit(0.U(32.W))
  val tsr = Reg(UInt(8.W))

  val fifoEnabled = fcrSticky(0)
  val recvFifoITL = RegInit(1.U(4.W))
  val canReceive = Mux(fifoEnabled, recvFifo.io.count =/= fifoDepth.U, !lsrReg(0))

  def pushRx(data: UInt): Unit = {
    when(fifoEnabled) {
      when(recvFifo.io.count === fifoDepth.U) {
        setLsr(2)
      }.otherwise {
        recvEnq := true.B
        recvData := data
      }
    }.otherwise {
      when(lsrReg(0)) { setLsr(2) }.otherwise { rbr := data }
    }
    setLsr(1)
    timeoutCnt := timeoutReload
    timeoutPending := false.B
  }

  io.rx.ready := canReceive
  when(io.rx.fire && canReceive) {
    pushRx(io.rx.bits)
  }

  def writeTHR(data: UInt): Unit = {
    when(fifoEnabled) {
      when(xmitFifo.io.count === fifoDepth.U) { xmitPop := true.B }
      xmitFifo.io.enq.valid := true.B
      xmitFifo.io.enq.bits := data
    }.otherwise {
      thr := data
      thrValid := true.B
    }
    thrIPending := false.B
    clrLsr(0x60)
  }

  val txHasData = Mux(fifoEnabled, xmitFifo.io.deq.valid, thrValid)
  when(!txBusy && txHasData) {
    tsr := Mux(fifoEnabled, xmitFifo.io.deq.bits, thr)
    txBusy := true.B
    txCounter := Mux(charCycles === 0.U, 1.U, charCycles)
    when(fifoEnabled) { xmitPop := true.B }.otherwise { thrValid := false.B }
    clrLsr(0x60)
  }

  val txFire = txBusy && txCounter === 0.U
  when(txBusy) {
    when(txCounter === 0.U) { txBusy := false.B }.otherwise { txCounter := txCounter - 1.U }
  }

  io.tx.valid := txFire && !mcr(4)
  io.tx.bits := tsr
  io.uart.valid_o := txFire
  io.uart.ch_o := tsr

  when(txFire) {
    when(mcr(4)) { pushRx(tsr) }
    val txEmptyNow = Mux(fifoEnabled, xmitFifo.io.count === 0.U, !thrValid)
    when(txEmptyNow) {
      setLsr(0x20)
      setLsr(0x40)
    }
  }

  val txEmpty = !txBusy && Mux(fifoEnabled, xmitFifo.io.count === 0.U, !thrValid)
  when(!txBusy && txEmpty) { setLsr(0x60) }

  when(!thrIPending && !lsrReg(5) && lsrNext(5)) { thrIPending := true.B }

  when(timeoutCnt =/= 0.U) {
    timeoutCnt := timeoutCnt - 1.U
    when(timeoutCnt === 1.U && recvFifo.io.count =/= 0.U) { timeoutPending := true.B }
  }

  when(mcr(4)) {
    msrNext := Cat(mcr(3), mcr(2), mcr(0), mcr(1), 0.U(4.W))
  }

  val lsrIntAny = lsrNext(4) || lsrNext(3) || lsrNext(2) || lsrNext(1)
  val rdiHit = lsrNext(0) && (!fifoEnabled || (recvFifo.io.count >= recvFifoITL))
  val msiHit = (msrNext & 0x0f.U) =/= 0.U
  val irqSel = WireInit(1.U(8.W))
  when(ier(2) && lsrIntAny) {
    irqSel := 0x06.U
  }.elsewhen(ier(0) && timeoutPending) {
    irqSel := 0x0c.U
  }.elsewhen(ier(0) && rdiHit) {
    irqSel := 0x04.U
  }.elsewhen(ier(1) && thrIPending) {
    irqSel := 0x02.U
  }.elsewhen(ier(3) && msiHit) {
    irqSel := 0x00.U
  }

  val fifoStatus = Mux(fifoEnabled, "hC0".U(8.W), 0.U)
  val iirVal = fifoStatus | irqSel
  io.irq := irqSel =/= 1.U

  def writeFCR(data: UInt): Unit = {
    when((data ^ fcrSticky)(0)) {
      rxFlush := true.B
      txFlush := true.B
    }
    when(data(1)) {
      rxFlush := true.B
      clrLsr(1 | 0x10)
      timeoutPending := false.B
      timeoutCnt := 0.U
    }
    when(data(2)) {
      txFlush := true.B
      setLsr(0x20)
      thrIPending := true.B
    }
    fcrSticky := data & "hC9".U
    recvFifoITL := rxTriggerLut(data(7, 6))
  }

  val beatBytes = params.beatBytes
  val readWord  = axi.ar.bits.addr(4, 3)
  val writeWord = axi.aw.bits.addr(4, 3)
  val readMask  = AXI4.maskGen(axi.ar.bits.addr, axi.ar.bits.size, beatBytes)
  val writeMask = axi.w.bits.strb
  val writeFire = axi.aw.fire && axi.w.fire

  def readEn(word: Int, lane: Int): Bool =
    axi.ar.fire && readWord === word.U(2.W) && readMask(lane)
  def writeEn(word: Int, lane: Int): Bool =
    writeFire && writeWord === word.U(2.W) && writeMask(lane)
  def wrByte(lane: Int): UInt = axi.w.bits.data(8 * lane + 7, 8 * lane)

  val rbrData = Wire(UInt(8.W))
  when(lcr(7)) {
    rbrData := dll
  }.elsewhen(fifoEnabled) {
    rbrData := Mux(recvFifo.io.deq.valid, recvFifo.io.deq.bits, 0.U)
  }.otherwise {
    rbrData := rbr
  }
  val ierData = Mux(lcr(7), dlm, ier)

  def beatPair(lo: UInt, hi: UInt): UInt = Cat(0.U(24.W), hi, 0.U(24.W), lo)
  val readData = MuxLookup(readWord, 0.U(params.dataBits.W))(
    Seq(
      0.U -> beatPair(rbrData, ierData),
      1.U -> beatPair(iirVal, lcr),
      2.U -> beatPair(mcr, lsrReg),
      3.U -> beatPair(msrReg, scr)
    )
  )

  // AXI commit is after the free-running TX/RX/timeout logic, so it wins last-connect.
  when(readEn(0, 0) && !lcr(7)) {
    when(fifoEnabled) {
      when(recvFifo.io.deq.valid) {
        recvPop := true.B
        when(recvFifo.io.count === 1.U) { clrLsr(1) }
        timeoutPending := false.B
        timeoutCnt := timeoutReload
      }
    }.otherwise {
      clrLsr(1)
      timeoutPending := false.B
    }
  }
  when(writeEn(0, 0)) {
    val data = wrByte(0)
    when(lcr(7)) { dll := data }.otherwise { writeTHR(data) }
  }
  when(writeEn(0, 4)) {
    val data = wrByte(4)
    when(lcr(7)) {
      dlm := data
    }.otherwise {
      val changed = (ier ^ data)(3, 0)
      ier := data & 0x0f.U
      when(changed(1)) { thrIPending := data(1) && lsrReg(5) }
    }
  }
  when(readEn(1, 0) && iirVal(2, 0) === 2.U) { thrIPending := false.B }
  when(writeEn(1, 0)) { writeFCR(wrByte(0)) }
  when(writeEn(1, 4)) { lcr := wrByte(4) }
  when(writeEn(2, 0)) { mcr := wrByte(0) }
  when(readEn(2, 4)) { clrLsr(0x10 | 0x02) }
  // MSR's original read-clear assigned msrReg and was then overwritten by msrNext.
  // Clearing msrNext keeps the delta-bit clear on the same final assignment.
  when(readEn(3, 0) && !mcr(4) && (msrReg & 0x0f.U) =/= 0.U) {
    msrNext := msrReg & 0xf0.U
  }
  when(writeEn(3, 4)) { scr := wrByte(4) }

  val respQ = Module(new Queue(new Uart16550Resp(params), 2))
  val room = respQ.io.enq.ready
  axi.ar.ready := room
  axi.aw.ready := room && !axi.ar.valid && axi.w.valid
  axi.w.ready := room && !axi.ar.valid && axi.aw.valid
  respQ.io.enq.valid := axi.ar.fire || writeFire
  respQ.io.enq.bits.read := axi.ar.valid
  respQ.io.enq.bits.id := Mux(axi.ar.valid, axi.ar.bits.id, axi.aw.bits.id)
  respQ.io.enq.bits.data := readData

  val resp = respQ.io.deq
  resp.ready := Mux(resp.bits.read, axi.r.ready, axi.b.ready)
  axi.r.valid := resp.valid && resp.bits.read
  axi.r.bits.id := resp.bits.id
  axi.r.bits.data := resp.bits.data
  axi.r.bits.resp := AXI4.respOkay
  axi.r.bits.last := true.B
  axi.b.valid := resp.valid && !resp.bits.read
  axi.b.bits.id := resp.bits.id
  axi.b.bits.resp := AXI4.respOkay

  io.uart.valid_in := false.B
  lsrReg := VecInit(lsrNext).asUInt
  msrReg := msrNext
}

class Uart16550Resp(params: AXI4Params) extends Bundle {
  val read = Bool()
  val id   = UInt(params.idBits.W)
  val data = UInt(params.dataBits.W)
}
