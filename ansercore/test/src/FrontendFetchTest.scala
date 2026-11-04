package ansercore.frontend

import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.MMUToFrontendIO

// PC is a halfword address. Cache lines are 64 bytes and one fetch block is 24 bytes.
object FetchProgram {
  val cacheLineBytes = 64
  val nop            = BigInt("00000013", 16)

  // addi rd, x0, imm. Low bits are 11, so this is not a compressed or control-flow instruction.
  def addi(index: Int): BigInt = {
    val rd  = (index % 31) + 1
    val imm = index & 0xfff
    (BigInt(imm) << 20) | (BigInt(rd) << 7) | BigInt(0x13)
  }

  def image(startByte: Int, instructions: Seq[BigInt], lineCount: Int): Seq[BigInt] = {
    val bytes                              = Array.fill(lineCount * cacheLineBytes)(0)
    def put(addr: Int, inst: BigInt): Unit = {
      for (byte <- 0 until 4) {
        bytes(addr + byte) = ((inst >> (8 * byte)) & 0xff).toInt
      }
    }
    for (addr <- 0 until bytes.length by 4) {
      put(addr, nop)
    }
    instructions.zipWithIndex.foreach { case (inst, index) =>
      put(startByte + index * 4, inst)
    }
    (0 until lineCount).map { line =>
      (0 until cacheLineBytes).foldLeft(BigInt(0)) { (acc, byte) =>
        acc | (BigInt(bytes(line * cacheLineBytes + byte) & 0xff) << (8 * byte))
      }
    }
  }
}

class FrontendFetchHarness(startByte: Int, instructions: Seq[BigInt], lineCount: Int) extends Module {
  require(startByte % 2 == 0, "start byte must be halfword aligned")
  require(instructions.nonEmpty, "fetch program must contain an instruction")
  require(
    startByte + instructions.length * 4 <= lineCount * FetchProgram.cacheLineBytes,
    "program does not fit in the test memory",
  )

  val frontend = Module(new Frontend)
  val fetched  = frontend.io.toBackend.fromIFU.inst

  val io = IO(new Bundle {
    val valid   = Output(Vec(fetched.length, Bool()))
    val inst    = Output(Vec(fetched.length, UInt(32.W)))
    val memFire = Output(Bool())
    val memAddr = Output(UInt(64.W))
  })

  frontend.io.resetVector := (startByte / 2).U(frontend.io.resetVector.getWidth.W)
  frontend.io.fromBackend := 0.U.asTypeOf(chiselTypeOf(frontend.io.fromBackend))
  fetched.foreach(_.ready := true.B)

  val mmu    = Wire(new MMUToFrontendIO)
  val vpn    = frontend.io.toMMU.itlbReq.bits.vpn
  val ppnPad = mmu.itlbResp.ppn.getWidth - vpn.getWidth
  mmu.itlbResp.hit         := true.B
  mmu.itlbResp.pageFault   := false.B
  mmu.itlbResp.accessFault := false.B
  mmu.itlbResp.ppn         := (if (ppnPad == 0) vpn else Cat(0.U(ppnPad.W), vpn))
  mmu.pmpResp.allowFetch   := true.B
  mmu.pmpResp.allowLoad    := true.B
  mmu.pmpResp.allowStore   := true.B
  mmu.pmaResp.instr        := true.B
  mmu.pmaResp.ld           := true.B
  mmu.pmaResp.st           := true.B
  mmu.pmaResp.cacheable    := true.B
  mmu.pmaResp.memory       := true.B
  frontend.io.fromMMU      := mmu

  val lines   = FetchProgram.image(startByte, instructions, lineCount)
  val rom     = VecInit(lines.map(_.U(512.W)))
  val nopLine = VecInit(Seq.fill(FetchProgram.cacheLineBytes / 4)(FetchProgram.nop.U(32.W))).asUInt

  val req  = frontend.io.memReq
  val resp = frontend.io.memResp
  val busy = RegInit(false.B)
  val id   = Reg(chiselTypeOf(req.bits.id))
  val addr = Reg(chiselTypeOf(req.bits.addr))
  val beat = RegInit(0.U(1.W))

  req.ready := !busy
  when(req.fire) {
    busy := true.B
    id   := req.bits.id
    addr := req.bits.addr
    beat := 0.U
  }

  // One request is a 64-byte line. addr is a 32-byte beat address, and addr(0) is the critical beat.
  val lineAddr = addr >> 1
  val inRange  = lineAddr < lineCount.U
  val lineIdx  = lineAddr(log2Ceil(lineCount) - 1, 0)
  val line     = Mux(inRange, rom(lineIdx), nopLine)
  val critical = addr(0)
  val selHigh  = Mux(beat === 0.U, critical, !critical)
  resp.valid     := busy
  resp.bits.id   := id
  resp.bits.data := Mux(selHigh, line(511, 256), line(255, 0))
  resp.bits.last := beat === 1.U
  when(resp.fire) {
    when(beat === 1.U) {
      busy := false.B
    }.otherwise {
      beat := 1.U
    }
  }

  io.valid   := VecInit(fetched.map(_.valid))
  io.inst    := VecInit(fetched.map(_.bits.inst))
  io.memFire := req.fire
  io.memAddr := req.bits.addr
}

class FrontendFetchTest extends AnyFlatSpec with Matchers {
  private def fetch(
    startByte:    Int,
    instructions: Seq[BigInt],
    limit:        Int,
  ): (Seq[BigInt], Set[Int], Int) = {
    val got    = scala.collection.mutable.ArrayBuffer.empty[BigInt]
    val lines  = scala.collection.mutable.Set.empty[Int]
    var cycles = 0
    var hole   = false
    simulate(new FrontendFetchHarness(startByte, instructions, lineCount = 16)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      while (got.size < instructions.length && cycles < limit) {
        var seenInvalid = false
        for (port <- 0 until dut.io.valid.length) {
          val valid = dut.io.valid(port).peek().litToBoolean
          if (!valid) {
            seenInvalid = true
          } else {
            if (seenInvalid) hole = true
            if (got.size < instructions.length) got += dut.io.inst(port).peek().litValue
          }
        }
        if (dut.io.memFire.peek().litToBoolean) {
          lines += (dut.io.memAddr.peek().litValue >> 1).toInt
        }
        if (got.size < instructions.length) {
          dut.clock.step()
          cycles += 1
        }
      }
    }
    hole shouldBe false
    (got.toSeq, lines.toSet, cycles)
  }

  "Frontend" should "fetch the instruction at the reset PC" in {
    val addi             = BigInt("00100093", 16)
    val (got, _, cycles) = fetch(startByte = 4, Seq(addi), limit = 8000)
    println(s"reset-pc cycles=$cycles")
    got shouldBe Seq(addi)
  }

  // Byte 46 makes the first 24-byte block cross a cache line, and instruction 4 itself
  // occupies bytes 62..65. Later blocks cross the following lines while both sides still miss.
  "Frontend" should "fetch sequential instructions across cache-line misses" in {
    val count                = 72
    val startByte            = 46
    val instructions         = (0 until count).map(FetchProgram.addi)
    val (got, lines, cycles) = fetch(startByte, instructions, limit = 30000)
    val lastByte             = startByte + count * 4 - 1
    val covered              = (startByte / FetchProgram.cacheLineBytes) to (lastByte / FetchProgram.cacheLineBytes)
    println(
      s"stream cycles=$cycles instructions=${got.size} lines=${lines.toSeq.sorted.mkString(",")} covered=${covered.mkString(",")}"
    )

    got.length shouldBe instructions.length
    got.zip(instructions).zipWithIndex.foreach { case ((actual, expected), index) =>
      withClue(s"instruction $index at byte ${startByte + index * 4}: ") {
        actual shouldBe expected
      }
    }
    covered.foreach { line =>
      withClue(s"cache line $line was not requested, saw ${lines.toSeq.sorted.mkString(",")}: ") {
        lines.contains(line) shouldBe true
      }
    }
  }
}
