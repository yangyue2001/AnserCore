package ansercore.frontend

import chisel3._
import chisel3.util._
import utils.Mux1H
import chisel3.util.experimental.BoringUtils
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.MMUToFrontendIO

// Reset PC is halfword address 2, so the first instruction sits at byte 4.
// UFTB comes out of reset with tag 0, and PC 0 would hit every way.
class FrontendFetchHarness extends Module {
  val io = IO(new Bundle {
    val instValid   = Output(Bool())
    val inst        = Output(UInt(32.W))
    val s1Valid     = Output(Bool())
    val s1Ready     = Output(Bool())
    val lookupValid = Output(Bool())
    val lookupReady = Output(Bool())
    val fetchValid  = Output(Bool())
    val fetchReady  = Output(Bool())
    val itlbValid   = Output(Bool())
    val memValid    = Output(Bool())
    val memFire     = Output(Bool())
    val memAddr     = Output(UInt(64.W))
    val f0Miss      = Output(Bool())
    val f0Ok        = Output(Bool())
    val f2Valid     = Output(Bool())
    val f2Miss      = Output(Bool())
    val f2Data      = Output(Bool())
    val wayValid    = Output(Bool())
    val refillValid = Output(Bool())
  })

  val frontend = Module(new Frontend)
  val startPc  = 2.U(frontend.io.resetVector.getWidth.W)
  frontend.io.resetVector := startPc

  frontend.io.fromBackend.commit.valid := false.B
  frontend.io.fromBackend.commit.bits := 0.U.asTypeOf(frontend.io.fromBackend.commit.bits)
  frontend.io.fromBackend.redirect.valid := false.B
  frontend.io.fromBackend.redirect.bits := 0.U.asTypeOf(frontend.io.fromBackend.redirect.bits)
  frontend.io.toBackend.fromIFU.inst.foreach(_.ready := true.B)

  val mmu = Wire(new MMUToFrontendIO)
  val vpn = frontend.io.toMMU.itlbReq.bits.vpn
  val ppnPad = mmu.itlbResp.ppn.getWidth - vpn.getWidth
  mmu.itlbResp.hit := true.B
  mmu.itlbResp.pageFault := false.B
  mmu.itlbResp.accessFault := false.B
  mmu.itlbResp.ppn := (if (ppnPad == 0) vpn else Cat(0.U(ppnPad.W), vpn))
  mmu.pmpResp.allowFetch := true.B
  mmu.pmpResp.allowLoad := true.B
  mmu.pmpResp.allowStore := true.B
  mmu.pmaResp.instr := true.B
  mmu.pmaResp.ld := true.B
  mmu.pmaResp.st := true.B
  mmu.pmaResp.cacheable := true.B
  mmu.pmaResp.memory := true.B
  frontend.io.fromMMU := mmu

  val lineBytes = 64
  val lineCount = 8
  val nop = BigInt("00000013", 16)
  val addi = BigInt("00100093", 16)
  val lines = (0 until lineCount).map { line =>
    (0 until lineBytes / 4).foldLeft(BigInt(0)) { (acc, word) =>
      val byteAddr = line * lineBytes + word * 4
      val inst = if (byteAddr == 4) addi else nop
      acc | (inst << (word * 32))
    }
  }
  val rom = VecInit(lines.map(_.U(512.W)))
  val nopLine = VecInit(Seq.fill(lineBytes / 4)(nop.U(32.W))).asUInt

  val req = frontend.io.memReq
  val resp = frontend.io.memResp
  val busy = RegInit(false.B)
  val id = Reg(chiselTypeOf(req.bits.id))
  val addr = Reg(chiselTypeOf(req.bits.addr))
  val beat = RegInit(0.U(1.W))

  req.ready := !busy
  when(req.fire) {
    busy := true.B
    id := req.bits.id
    addr := req.bits.addr
    beat := 0.U
  }

  val lineAddr = addr >> 1
  val inRange = lineAddr < lineCount.U
  val line = Mux(inRange, rom(lineAddr(2, 0)), nopLine)
  val critical = addr(0)
  val selHigh = Mux(beat === 0.U, critical, !critical)
  resp.valid := busy
  resp.bits.id := id
  resp.bits.data := Mux(selHigh, line(511, 256), line(255, 0))
  resp.bits.last := beat === 1.U
  when(resp.fire) {
    when(beat === 1.U) {
      busy := false.B
    }.otherwise {
      beat := 1.U
    }
  }

  val fetched = frontend.io.toBackend.fromIFU.inst
  io.instValid := fetched.map(_.valid).reduce(_ || _)
  io.inst := Mux1H(PriorityEncoderOH(VecInit(fetched.map(_.valid)).asUInt), VecInit(fetched.map(_.bits.inst)))
  io.s1Valid := BoringUtils.bore(frontend.bpu.io.toFTQ.s1Fetch.valid)
  io.s1Ready := BoringUtils.bore(frontend.bpu.io.toFTQ.s1Fetch.ready)
  io.lookupValid := BoringUtils.bore(frontend.ftq.io.toIFU.lookUp.valid)
  io.lookupReady := BoringUtils.bore(frontend.ftq.io.toIFU.lookUp.ready)
  io.fetchValid := BoringUtils.bore(frontend.ftq.io.toIFU.fetch.valid)
  io.fetchReady := BoringUtils.bore(frontend.ftq.io.toIFU.fetch.ready)
  io.itlbValid := frontend.io.toMMU.itlbReq.valid
  io.memValid := frontend.io.memReq.valid
  io.memFire := frontend.io.memReq.fire
  io.memAddr := frontend.io.memReq.bits.addr
  val icache = frontend.ifu.icache
  io.f0Miss := BoringUtils.bore(icache.f0Miss)
  io.f0Ok := BoringUtils.bore(icache.f0SerialOK)
  io.f2Valid := BoringUtils.bore(icache.f2Valid)
  io.f2Miss := BoringUtils.bore(icache.f2CacheMiss)
  io.f2Data := BoringUtils.bore(icache.f2HasData)
  io.wayValid := BoringUtils.bore(icache.wayLookup.io.s0Entry.valid)
  io.refillValid := BoringUtils.bore(icache.mshrUnit.io.refill.valid)
}

class FrontendFetchTest extends AnyFlatSpec with Matchers {
  "Frontend" should "fetch the instruction at the reset PC" in {
    simulate(new FrontendFetchHarness) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      var found = false
      var inst = BigInt(0)
      var cycle = 0
      var s1 = 0
      var lookup = 0
      var lookupReady = 0
      var fetch = 0
      var fetchReady = 0
      var itlb = 0
      var memValid = 0
      var memFire = 0
      var memAddr = BigInt(0)
      var f0Miss = 0
      var f0Ok = 0
      var f2Valid = 0
      var f2Miss = 0
      var f2Data = 0
      var wayValid = 0
      var refill = 0
      while (!found && cycle < 8000) {
        if (dut.io.s1Valid.peek().litToBoolean) s1 += 1
        if (dut.io.lookupValid.peek().litToBoolean) lookup += 1
        if (dut.io.lookupReady.peek().litToBoolean) lookupReady += 1
        if (dut.io.fetchValid.peek().litToBoolean) fetch += 1
        if (dut.io.fetchReady.peek().litToBoolean) fetchReady += 1
        if (dut.io.itlbValid.peek().litToBoolean) itlb += 1
        if (dut.io.memValid.peek().litToBoolean) memValid += 1
        if (dut.io.memFire.peek().litToBoolean) {
          memFire += 1
          memAddr = dut.io.memAddr.peek().litValue
        }
        if (dut.io.f0Miss.peek().litToBoolean) f0Miss += 1
        if (dut.io.f0Ok.peek().litToBoolean) f0Ok += 1
        if (dut.io.f2Valid.peek().litToBoolean) f2Valid += 1
        if (dut.io.f2Miss.peek().litToBoolean) f2Miss += 1
        if (dut.io.f2Data.peek().litToBoolean) f2Data += 1
        if (dut.io.wayValid.peek().litToBoolean) wayValid += 1
        if (dut.io.refillValid.peek().litToBoolean) refill += 1
        if (dut.io.instValid.peek().litToBoolean) {
          found = true
          inst = dut.io.inst.peek().litValue
        } else {
          dut.clock.step()
          cycle += 1
        }
      }

      println(
        s"cycles=$cycle found=$found s1=$s1 lookup=$lookup fetch=$fetch fetchReady=$fetchReady itlb=$itlb memFire=$memFire memAddr=0x${memAddr.toString(16)} f0Miss=$f0Miss f0Ok=$f0Ok way=$wayValid f2Valid=$f2Valid f2Miss=$f2Miss f2Data=$f2Data refill=$refill"
      )
      found shouldBe true
      inst shouldBe BigInt("00100093", 16)
    }
  }
}
