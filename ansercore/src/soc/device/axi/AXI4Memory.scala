package device.axi

import chisel3._
import chisel3.util._

// One rising edge, one timing call. memory_request and memory_response only move an id.
// memory_read and memory_write still move 8 bytes. A wider beat calls them once per
// 8-byte lane in that same edge. A combinational call would run again every time
// Verilator re-evaluates the cone.
class AXI4MemoryDpi(dataBits: Int) extends ExtModule {
  require(dataBits >= 64 && dataBits % 64 == 0, "DPI beat must be a positive multiple of 8 bytes")

  val words    = dataBits / 64
  val strbBits = dataBits / 8

  override def desiredName = s"AXI4MemoryDpi_$dataBits"

  val clock = IO(Input(Clock()))
  val reset = IO(Input(Bool()))

  val readReqValid  = IO(Input(Bool()))
  val readReqOffset = IO(Input(UInt(64.W)))
  val readReqId     = IO(Input(UInt(32.W)))
  val readReqAccept = IO(Output(Bool()))

  val writeReqValid  = IO(Input(Bool()))
  val writeReqOffset = IO(Input(UInt(64.W)))
  val writeReqId     = IO(Input(UInt(32.W)))
  val writeReqAccept = IO(Output(Bool()))

  val readRespEnable = IO(Input(Bool()))
  val readRespTake   = IO(Input(Bool()))
  val readRespValid  = IO(Output(Bool()))
  val readRespId     = IO(Output(UInt(32.W)))

  val readEn     = IO(Input(Bool()))
  val readOffset = IO(Input(UInt(64.W)))
  val readData   = IO(Output(UInt(dataBits.W)))

  val writeEn     = IO(Input(Bool()))
  val writeOffset = IO(Input(UInt(64.W)))
  val writeData   = IO(Input(UInt(dataBits.W)))
  val writeStrb   = IO(Input(UInt(strbBits.W)))

  setInline(
    s"$desiredName.sv",
    s"""import "DPI-C" function byte unsigned memory_request(
      |  input longint unsigned offset,
      |  input int unsigned id,
      |  input byte unsigned is_write
      |);
      |import "DPI-C" function void memory_write(
      |  input longint unsigned offset,
      |  input longint unsigned data,
      |  input byte unsigned strobe
      |);
      |import "DPI-C" function longint unsigned memory_read(
      |  input longint unsigned offset
      |);
      |import "DPI-C" function longint unsigned memory_response(
      |  input byte unsigned is_write
      |);
      |
      |module $desiredName(
      |  input         clock,
      |  input         reset,
      |  input         readReqValid,
      |  input  [63:0] readReqOffset,
      |  input  [31:0] readReqId,
      |  output reg    readReqAccept,
      |  input         writeReqValid,
      |  input  [63:0] writeReqOffset,
      |  input  [31:0] writeReqId,
      |  output reg    writeReqAccept,
      |  input         readRespEnable,
      |  input         readRespTake,
      |  output reg    readRespValid,
      |  output reg [31:0] readRespId,
      |  input         readEn,
      |  input  [63:0] readOffset,
      |  output reg [${dataBits - 1}:0] readData,
      |  input         writeEn,
      |  input  [63:0] writeOffset,
      |  input  [${dataBits - 1}:0] writeData,
      |  input  [${strbBits - 1}:0] writeStrb
      |);
      |  longint unsigned read_resp;
      |  longint unsigned write_resp;
      |  integer word;
      |
      |  always @(posedge clock) begin
      |    if (reset) begin
      |      readReqAccept <= 1'b0;
      |      writeReqAccept <= 1'b0;
      |      readRespValid <= 1'b0;
      |      readRespId <= 32'h0;
      |      readData <= '0;
      |    end else begin
      |      // Hold the request until the registered accept is consumed. A reject
      |      // stays valid and is retried on the next edge.
      |      if (readReqValid && !readReqAccept)
      |        readReqAccept <= memory_request(readReqOffset, readReqId, 8'd0) != 8'd0;
      |      else if (!readReqValid)
      |        readReqAccept <= 1'b0;
      |
      |      if (writeReqValid && !writeReqAccept)
      |        writeReqAccept <= memory_request(writeReqOffset, writeReqId, 8'd1) != 8'd0;
      |      else if (!writeReqValid)
      |        writeReqAccept <= 1'b0;
      |
      |      if (readRespValid) begin
      |        if (readRespTake)
      |          readRespValid <= 1'b0;
      |      end else if (readRespEnable) begin
      |        read_resp = memory_response(8'd0);
      |        readRespValid <= read_resp[32];
      |        readRespId <= read_resp[31:0];
      |      end
      |
      |      // B is returned when the bytes are written. Pop the timing response
      |      // so DRAMSim3 does not keep it forever.
      |      write_resp = memory_response(8'd1);
      |
      |      // One timing response covers the burst. The beat itself is several 8-byte calls.
      |      if (readEn) begin
      |        for (word = 0; word < $words; word = word + 1)
      |          readData[word*64 +: 64] <= memory_read(readOffset + word*8);
      |      end
      |      if (writeEn) begin
      |        for (word = 0; word < $words; word = word + 1)
      |          memory_write(writeOffset + word*8, writeData[word*64 +: 64], writeStrb[word*8 +: 8]);
      |      end
      |    end
      |  end
      |endmodule
      |""".stripMargin,
  )
}

// Simulation RAM. Bytes live in sim_verilator's MemoryService; this slave only
// speaks AXI. `base` is the physical address of offset 0 and must match the image
// base (0x80000000). `memBytes` must match --ram-size. Only one instance: the DPI
// service is a single global RAM.
//
// A read burst is one timing request. Each AXI beat then calls memory_read once
// per 8 bytes, in the same cycle. Writes update bytes as W is consumed and return
// B without waiting for the timing response.
// An address outside [base, base + memBytes) is DECERR and does not call DPI.
// Same-ID reads and writes wait for each other. A read id stays busy until the
// master takes its last R beat, so a later AR cannot pass that response.
// Different IDs may pass.
class AXI4Memory(
  memBytes: Long,
  base: Long = 0x80000000L,
  params: AXI4Params = AXI4Params(),
  burstLen: Int = 16,
  numOutstanding: Int = 64,
) extends AXI4Slave(params) {
  require(params.dataBits >= 64 && params.dataBits % 64 == 0, "AXI data width must be a positive multiple of 8 bytes")
  require(params.idBits > 0 && params.idBits <= 32, "memory_request id is 32 bits")
  require(params.addrBits >= 1 && params.addrBits <= 64, "memory offset is computed in 64 bits")
  require(BigInt(base).bitLength <= params.addrBits, "memory base must fit in the AXI address")
  require(memBytes > 0 && memBytes % params.beatBytes == 0, "memory size must cover whole beats")
  require(base >= 0 && base % params.beatBytes == 0, "memory base must be beat aligned")
  require(burstLen >= 16 && burstLen <= 256, "burst length must cover the supported AXI lens")
  require(numOutstanding >= 2 && isPow2(numOutstanding), "outstanding trackers must be a power of two")

  val slotBits = log2Ceil(numOutstanding)
  val beatBits = log2Ceil(burstLen + 1)
  val beatSize = log2Ceil(params.beatBytes).U(3.W)
  val dpi      = Module(new AXI4MemoryDpi(params.dataBits))
  dpi.clock := clock
  dpi.reset := reset.asBool

  // Offset 0 is the first byte of the service. The burst address is aligned to the
  // AXI beat. Each beat is then read or written as consecutive 8-byte words.
  def wordOffset(addr: UInt, beat: UInt): UInt = {
    val delta   = addr.pad(64) - base.U(64.W)
    val aligned = delta & (~(params.beatBytes - 1).U(64.W)).asUInt
    aligned + Cat(beat, 0.U(log2Ceil(params.beatBytes).W)).pad(64)
  }

  def coversBurst(addr: UInt, len: UInt): Bool = {
    val start  = wordOffset(addr, 0.U)
    val beats  = len.pad(9) + 1.U(9.W)
    val nbytes = Cat(beats, 0.U(log2Ceil(params.beatBytes).W)).pad(64)
    val end    = start +& nbytes
    addr.pad(64) >= base.U(64.W) && end <= memBytes.U(end.getWidth.W)
  }

  class ReadIssue extends Bundle {
    val ar    = new AXI4BundleA(params)
    val slot  = UInt(slotBits.W)
    val error = Bool()
  }

  class WriteIssue extends Bundle {
    val aw    = new AXI4BundleA(params)
    val error = Bool()
  }

  class ReadMeta extends Bundle {
    val id   = UInt(params.idBits.W)
    val resp = UInt(2.W)
    val last = Bool()
  }

  // Per-slot registers, not a Vec. Firtool rejects a connect into an array element.
  // Bool state is a mask for the same reason.
  val rBusy = RegInit(0.U(numOutstanding.W))
  val rErr  = RegInit(0.U(numOutstanding.W))
  val rBeat = Seq.fill(numOutstanding)(RegInit(0.U(beatBits.W)))
  val rCmd  = Seq.fill(numOutstanding)(RegInit(0.U.asTypeOf(new AXI4BundleA(params))))

  def slotMux[T <: Data](index: UInt, values: Seq[T]): T = {
    val gen = chiselTypeOf(values.head)
    values.zipWithIndex.map { case (value, i) =>
      Mux(index === i.U(slotBits.W), value, 0.U.asTypeOf(gen))
    }.reduce((left, right) => (left.asUInt | right.asUInt).asTypeOf(gen))
  }

  val readFree  = !rBusy.andR
  val allocSlot = PriorityEncoder((~rBusy).asUInt)
  val arQueue   = Module(new Queue(new ReadIssue, numOutstanding))

  // Writes that have been accepted but whose bytes are not in the RAM yet.
  // Completion is in AW order, so a head pointer is enough to retire them.
  val wHead    = RegInit(0.U(slotBits.W))
  val wTail    = RegInit(0.U(slotBits.W))
  val wIdValid = RegInit(0.U(numOutstanding.W))
  val wIdMem   = Seq.fill(numOutstanding)(RegInit(0.U(params.idBits.W)))

  val awQueue   = Module(new Queue(new WriteIssue, numOutstanding))
  val wTrackers = Module(new Queue(new WriteIssue, numOutstanding))
  val wQueue    = Module(new Queue(new AXI4BundleW(params), numOutstanding * burstLen))
  val bQueue    = Module(new Queue(new AXI4BundleB(params), numOutstanding))

  val occupied   = bQueue.io.count +& wTrackers.io.count
  val bRoom      = occupied < numOutstanding.U(occupied.getWidth.W)
  val warConflict = (0 until numOutstanding).map { i =>
    rBusy(i) && rCmd(i).id === io.aw.bits.id
  }.reduce(_ || _)
  val awCan  = awQueue.io.enq.ready && wTrackers.io.enq.ready && bRoom && !warConflict
  val awFire = io.aw.valid && awCan
  io.aw.ready          := awCan
  awQueue.io.enq.valid := awFire
  wTrackers.io.enq.valid := awFire
  val writeError = !coversBurst(io.aw.bits.addr, io.aw.bits.len)
  awQueue.io.enq.bits.aw    := io.aw.bits
  awQueue.io.enq.bits.error := writeError
  wTrackers.io.enq.bits     := awQueue.io.enq.bits

  val rawConflict = (0 until numOutstanding).map { i =>
    wIdValid(i) && wIdMem(i) === io.ar.bits.id
  }.reduce(_ || _)
  val rawNow = awFire && io.aw.bits.id === io.ar.bits.id
  val idConflict = (0 until numOutstanding).map { i =>
    rBusy(i) && rCmd(i).id === io.ar.bits.id
  }.reduce(_ || _)
  val arCan  = arQueue.io.enq.ready && readFree && !idConflict && !rawConflict && !rawNow
  val arFire = io.ar.valid && arCan
  io.ar.ready          := arCan
  arQueue.io.enq.valid := arFire
  arQueue.io.enq.bits.ar    := io.ar.bits
  arQueue.io.enq.bits.slot  := allocSlot
  arQueue.io.enq.bits.error := !coversBurst(io.ar.bits.addr, io.ar.bits.len)

  val readTaken  = RegInit(false.B)
  val errPending = RegInit(false.B)
  val errSlot    = RegInit(0.U(slotBits.W))
  val readHead   = arQueue.io.deq.bits
  dpi.readReqValid  := arQueue.io.deq.valid && !readTaken && !readHead.error
  dpi.readReqOffset := wordOffset(readHead.ar.addr, 0.U)
  dpi.readReqId     := readHead.slot.pad(32)
  arQueue.io.deq.ready := Mux(readHead.error, !errPending && !readTaken, dpi.readReqAccept && !readTaken)
  when(arQueue.io.deq.fire) {
    readTaken := true.B
  }.elsewhen(readTaken) {
    readTaken := false.B
  }

  val rIssued  = RegInit(0.U(log2Ceil(numOutstanding + 1).W))
  val readFlap = RegInit(false.B)
  val flapSlot = RegInit(0.U(slotBits.W))
  val skidValid = RegInit(false.B)
  val skid      = RegInit(0.U.asTypeOf(new ReadMeta))
  val rQueue    = Module(new Queue(new AXI4BundleR(params), burstLen))

  dpi.readRespEnable := rIssued =/= 0.U && !readFlap && !errPending && !dpi.readRespValid
  val useErr  = errPending && !readFlap
  val useDram = dpi.readRespValid && !useErr && !readFlap
  val beatGo  = readFlap || useErr || useDram
  val slot    = Mux(readFlap, flapSlot, Mux(useErr, errSlot, dpi.readRespId(slotBits - 1, 0)))
  val beat     = slotMux(slot, rBeat)
  val cmd      = slotMux(slot, rCmd)
  val lastBeat = beat.pad(16) === cmd.len.pad(16)
  // memory_read's result is registered in the DPI, so the meta waits one cycle in
  // skid and meets that data at rQueue. A 1-entry Queue cannot do this: pipe mode
  // reads and writes the same memory address in one cycle.
  val load = beatGo && (!skidValid || rQueue.io.enq.ready)
  dpi.readRespTake := load && useDram
  dpi.readEn       := load && !rErr(slot)
  dpi.readOffset   := wordOffset(cmd.addr, beat)

  val startErr = arQueue.io.deq.fire && readHead.error
  val doneErr  = load && useErr
  when(startErr) {
    errPending := true.B
    errSlot    := readHead.slot
  }.elsewhen(doneErr) {
    errPending := false.B
  }
  when(load) {
    skidValid := true.B
    skid.id   := cmd.id
    skid.resp := Mux(rErr(slot), AXI4.respDecerr, AXI4.respOkay)
    skid.last := lastBeat
    when(lastBeat) {
      readFlap := false.B
    }.otherwise {
      readFlap := true.B
      flapSlot := slot
    }
  }.elsewhen(skidValid && rQueue.io.enq.ready) {
    skidValid := false.B
  }

  for (i <- 0 until numOutstanding) {
    val idx = i.U(slotBits.W)
    when(arFire && allocSlot === idx) {
      rCmd(i)  := arQueue.io.enq.bits.ar
      rBeat(i) := 0.U
    }.elsewhen(load && slot === idx) {
      rBeat(i) := Mux(lastBeat, 0.U, beat + 1.U)
    }
  }
  val readRetire = rQueue.io.deq.fire && rQueue.io.deq.bits.last
  val retireOH = (0 until numOutstanding).map { i =>
    Mux(
      readRetire && rBusy(i) && rCmd(i).id === rQueue.io.deq.bits.id,
      UIntToOH(i.U(slotBits.W), numOutstanding),
      0.U(numOutstanding.W),
    )
  }.reduce(_ | _)
  val allocOH     = UIntToOH(allocSlot, numOutstanding)
  val busyCleared = rBusy & (~retireOH).asUInt
  rBusy := Mux(arFire, busyCleared | allocOH, busyCleared)
  val errDrop    = rErr & (~retireOH).asUInt
  val errCleared = Mux(arFire, errDrop & (~allocOH).asUInt, errDrop)
  rErr := Mux(arFire && arQueue.io.enq.bits.error, errCleared | allocOH, errCleared)

  rQueue.io.enq.valid     := skidValid
  rQueue.io.enq.bits.id   := skid.id
  rQueue.io.enq.bits.resp := skid.resp
  rQueue.io.enq.bits.last := skid.last
  rQueue.io.enq.bits.data := Mux(skid.resp === AXI4.respDecerr, 0.U, dpi.readData)
  io.r <> rQueue.io.deq

  val readInc = arQueue.io.deq.fire && !readHead.error
  val readDec = load && lastBeat && !rErr(slot)
  when(readInc && !readDec) {
    rIssued := rIssued + 1.U
  }.elsewhen(!readInc && readDec) {
    rIssued := rIssued - 1.U
  }

  val writeTaken = RegInit(false.B)
  val writeHead  = awQueue.io.deq.bits
  dpi.writeReqValid  := awQueue.io.deq.valid && !writeTaken && !writeHead.error
  dpi.writeReqOffset := wordOffset(writeHead.aw.addr, 0.U)
  dpi.writeReqId     := writeHead.aw.id.pad(32)
  awQueue.io.deq.ready := Mux(writeHead.error, !writeTaken, dpi.writeReqAccept && !writeTaken)
  when(awQueue.io.deq.fire) {
    writeTaken := true.B
  }.elsewhen(writeTaken) {
    writeTaken := false.B
  }

  io.w.ready          := wQueue.io.enq.ready
  wQueue.io.enq.valid := io.w.valid
  wQueue.io.enq.bits  := io.w.bits

  val wIssued = RegInit(0.U(log2Ceil(numOutstanding + 1).W))
  val wBeat   = RegInit(0.U(beatBits.W))
  val wHeadAw = wTrackers.io.deq.bits
  val wLast   = wQueue.io.deq.bits.last
  wQueue.io.deq.ready := wIssued =/= 0.U && wTrackers.io.deq.valid && (!wLast || bQueue.io.enq.ready)

  dpi.writeEn     := wQueue.io.deq.fire && !wHeadAw.error
  dpi.writeOffset := wordOffset(wHeadAw.aw.addr, wBeat)
  dpi.writeData   := wQueue.io.deq.bits.data
  dpi.writeStrb   := wQueue.io.deq.bits.strb
  when(wQueue.io.deq.fire) {
    wBeat := Mux(wLast, 0.U, wBeat + 1.U)
  }

  bQueue.io.enq.valid     := wQueue.io.deq.fire && wLast
  bQueue.io.enq.bits.id   := wHeadAw.aw.id
  bQueue.io.enq.bits.resp := Mux(wHeadAw.error, AXI4.respDecerr, AXI4.respOkay)
  wTrackers.io.deq.ready  := bQueue.io.enq.fire
  io.b <> bQueue.io.deq

  val writeInc = awQueue.io.deq.fire
  val writeDec = wQueue.io.deq.fire && wLast
  when(writeInc && !writeDec) {
    wIssued := wIssued + 1.U
  }.elsewhen(!writeInc && writeDec) {
    wIssued := wIssued - 1.U
  }
  when(writeDec) {
    wHead := wHead + 1.U
  }
  when(awFire) {
    wTail := wTail + 1.U
  }
  for (i <- 0 until numOutstanding) {
    val idx = i.U(slotBits.W)
    when(awFire && wTail === idx) {
      wIdMem(i) := io.aw.bits.id
    }
  }
  val popOH     = UIntToOH(wHead, numOutstanding)
  val pushOH    = UIntToOH(wTail, numOutstanding)
  val idCleared = Mux(writeDec, wIdValid & (~popOH).asUInt, wIdValid)
  wIdValid := Mux(awFire, idCleared | pushOH, idCleared)

  when(io.ar.fire) {
    assert(io.ar.bits.burst === AXI4.burstIncr, "only support burst incr")
    assert(
      io.ar.bits.len === 0.U || io.ar.bits.len === 1.U || io.ar.bits.len === 3.U ||
        io.ar.bits.len === 7.U || io.ar.bits.len === 15.U
    )
    assert(io.ar.bits.len === 0.U || io.ar.bits.size === beatSize, "wide burst must use the full AXI beat")
  }
  when(io.aw.fire) {
    assert(io.aw.bits.burst === AXI4.burstIncr, "only support burst incr")
    assert(
      io.aw.bits.len === 0.U || io.aw.bits.len === 1.U || io.aw.bits.len === 3.U ||
        io.aw.bits.len === 7.U || io.aw.bits.len === 15.U
    )
    assert(io.aw.bits.len === 0.U || io.aw.bits.size === beatSize, "wide burst must use the full AXI beat")
  }
  val beatAlign = log2Ceil(params.beatBytes)
  when(dpi.readReqValid) {
    assert(dpi.readReqOffset(beatAlign - 1, 0) === 0.U, "read request offset is not beat aligned")
  }
  when(dpi.writeReqValid) {
    assert(dpi.writeReqOffset(beatAlign - 1, 0) === 0.U, "write request offset is not beat aligned")
  }
  when(dpi.readEn) {
    assert(dpi.readOffset(beatAlign - 1, 0) === 0.U, "memory_read offset is not beat aligned")
  }
  when(dpi.writeEn) {
    assert(dpi.writeOffset(beatAlign - 1, 0) === 0.U, "memory_write offset is not beat aligned")
  }
  when(load) { assert(rBusy(slot), "AXI4Memory: read beat without a tracker") }
  when(readRetire) { assert(retireOH.orR, "AXI4Memory: read retire without a tracker") }
  when(wQueue.io.deq.fire) { assert(wTrackers.io.deq.valid, "AXI4Memory: write data without a tracker") }
  when(writeDec) {
    assert(wBeat.pad(16) === wHeadAw.aw.len.pad(16), "AXI4Memory: write last does not match AWLEN")
    assert(wIdValid(wHead), "AXI4Memory: write id scoreboard underflow")
    assert(wIssued =/= 0.U || writeInc, "AXI4Memory: write issue count underflow")
  }
  when(readDec) { assert(rIssued =/= 0.U || readInc, "AXI4Memory: read issue count underflow") }
}
