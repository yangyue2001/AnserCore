package socdiff

import chisel3._
import _root_.circt.stage.ChiselStage
import difftest._
import soc.SoC

// SoC plus one commit probe. A positive commit limit drives exit to all-ones
// after that many retires. Zero keeps running until --max-cycles.
class SocDiffDut(commitLimit: Int = 8, ramBytes: Long = 128L << 20)
    extends Module
    with HasDiffTestInterfaces {
  require(commitLimit >= 0)
  require(ramBytes >= 64 && (ramBytes & (ramBytes - 1)) == 0)

  val exit = IO(Output(UInt(64.W)))
  val soc  = Module(new SoC(memBytes = ramBytes))

  val retire = soc.io.retire
  val commit = DifftestModule(new Commit)
  commit.coreid := 0.U
  commit.valid  := retire.valid
  commit.index  := 0.U
  commit.rfwen  := retire.rfwen
  commit.wdest  := retire.wdest
  commit.pc     := retire.pc
  commit.inst   := retire.inst
  commit.wdata  := retire.wdata
  commit.skip   := retire.skip

  if (commitLimit == 0) {
    exit := 0.U
  } else {
    require(commitLimit <= 65535)
    val seen = RegInit(0.U(16.W))
    when(retire.valid && seen =/= commitLimit.U) {
      seen := seen + 1.U
    }
    // One cycle later, so the retire that reaches the limit is compared first.
    val done = RegInit(false.B)
    when(seen === commitLimit.U) {
      done := true.B
    }
    exit := Mux(done, (~0.U(64.W)).asUInt, 0.U)
  }
}

object EmitSocDiff extends App {
  val ramMb = sys.env.getOrElse("SOCDIFF_RAM_MB", "128").toLong
  val commits = sys.env.getOrElse("SOCDIFF_COMMITS", "8").toInt
  val out = sys.env.getOrElse("SOCDIFF_OUT", "sv")
  val firtool = sys.env.getOrElse(
    "FIRTOOL",
    "/home/yang/.cache/llvm-firtool/1.160.0/bin/firtool",
  )
  ChiselStage.emitSystemVerilogFile(
    DifftestModule.top(new SocDiffDut(commits, ramMb << 20)),
    Array("--target-dir", out, "--firtool-binary-path", firtool),
    Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "--split-verilog",
      "-disable-layers=Verification",
    ),
  )
  println("EMIT_OK " + out)
}
