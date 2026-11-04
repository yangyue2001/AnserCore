package softmmucheck

import chisel3._
import circt.stage.ChiselStage
import ansercore.HasAnserParameters
import ansercore.PrivMode
import ansercore.mmu.MMU

// Flat ports so the Verilator test does not have to poke frontend bundles.
class SoftMmuProbe extends Module with HasAnserParameters {
  val io = IO(new Bundle {
    val priv = Input(UInt(2.W))
    val satp = Input(UInt(64.W))
    val sum = Input(Bool())
    val mxr = Input(Bool())
    val reqValid = Input(Bool())
    val vpn = Input(UInt(vpnWidth.W))
    val hit = Output(Bool())
    val ppn = Output(UInt(ppnWidth.W))
    val pageFault = Output(Bool())
    val accessFault = Output(Bool())
    val allowFetch = Output(Bool())
    val allowLoad = Output(Bool())
    val allowStore = Output(Bool())
    val pmaLd = Output(Bool())
    val pmaSt = Output(Bool())
    val pmaInstr = Output(Bool())
    val pmaCacheable = Output(Bool())
    val pmaMemory = Output(Bool())
  })

  val mmu = Module(new MMU)
  when(io.priv === 0.U) {
    mmu.io.mmuCtr.privMode := PrivMode.uMode
  }.elsewhen(io.priv === 1.U) {
    mmu.io.mmuCtr.privMode := PrivMode.sMode
  }.otherwise {
    mmu.io.mmuCtr.privMode := PrivMode.mMode
  }
  mmu.io.mmuCtr.satp := io.satp
  mmu.io.mmuCtr.mstatusMPRV := false.B
  mmu.io.mmuCtr.mstatusMPP := PrivMode.mMode
  mmu.io.mmuCtr.mstatusSUM := io.sum
  mmu.io.mmuCtr.mstatusMXR := io.mxr
  mmu.io.fromFrontend.itlbReq.valid := io.reqValid
  mmu.io.fromFrontend.itlbReq.bits.vpn := io.vpn
  mmu.io.fromFrontend.pmpReq := 0.U.asTypeOf(chiselTypeOf(mmu.io.fromFrontend.pmpReq))
  mmu.io.fromFrontend.pmaReq := 0.U.asTypeOf(chiselTypeOf(mmu.io.fromFrontend.pmaReq))

  val resp = mmu.io.toFrontend
  io.hit := resp.itlbResp.hit
  io.ppn := resp.itlbResp.ppn
  io.pageFault := resp.itlbResp.pageFault
  io.accessFault := resp.itlbResp.accessFault
  io.allowFetch := resp.pmpResp.allowFetch
  io.allowLoad := resp.pmpResp.allowLoad
  io.allowStore := resp.pmpResp.allowStore
  io.pmaLd := resp.pmaResp.ld
  io.pmaSt := resp.pmaResp.st
  io.pmaInstr := resp.pmaResp.instr
  io.pmaCacheable := resp.pmaResp.cacheable
  io.pmaMemory := resp.pmaResp.memory
}

object EmitSoftMmu extends App {
  val out = sys.env.getOrElse("SOFTMMU_OUT", "sv")
  val firtool = sys.env.getOrElse(
    "FIRTOOL",
    "/home/yang/.cache/llvm-firtool/1.160.0/bin/firtool",
  )
  ChiselStage.emitSystemVerilogFile(
    new SoftMmuProbe,
    Array("--target-dir", out, "--firtool-binary-path", firtool),
    Array("-disable-all-randomization", "-strip-debug-info"),
  )
  println("EMIT_OK " + out)
}
