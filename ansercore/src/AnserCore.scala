/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

package ansercore

import chisel3._
import chisel3.util._

import _root_.circt.stage.ChiselStage
import ansercore.backend.softBackend.SoftBackend
import ansercore.backend.softBackend.SoftRetire
import ansercore.frontend.Frontend
import ansercore.frontend.ifu.icache.ICacheMemReq
import ansercore.frontend.ifu.icache.ICacheMemResp
import ansercore.mmu.MMU

class AnserCore extends AnserModule {
  class AnserCoreIO extends AnserBundle {
    val resetVector = Input(UInt(pcWidth.W))
    val memReq      = Decoupled(new ICacheMemReq)
    val memResp     = Flipped(Decoupled(new ICacheMemResp))
    val retire      = Output(new SoftRetire)
  }
  val io = IO(new AnserCoreIO)

  val frontend = Module(new Frontend)
  val backend  = Module(new SoftBackend)
  val mmu      = Module(new MMU)

  frontend.io.resetVector := io.resetVector
  mmu.io.mmuCtr           := backend.io.mmuCtr
  mmu.io.fromFrontend     := frontend.io.toMMU
  frontend.io.fromMMU     := mmu.io.toFrontend
  backend.io.fromFrontend <> frontend.io.toBackend
  frontend.io.fromBackend <> backend.io.toFrontend
  frontend.io.memReq <> io.memReq
  frontend.io.memResp <> io.memResp
  backend.io.resetPc := Cat(io.resetVector, 0.U(InstOffsetWidth.W)).pad(64)
  io.retire := backend.io.retire
}

object Main extends App {
  println("Generating the AnserCore hardware design...")

  ChiselStage.emitSystemVerilogFile(
    gen         = new AnserCore(),
    args        = Array("--target-dir", "out/verilog"),
    firtoolOpts = Array(
      "-preserve-aggregate=1d-vec",
      "-disable-layers=Verification",
    ),
  )

  ChiselStage.emitSystemVerilogFile(
    gen         = new AnserCore(),
    args        = Array("--target-dir", "out/sta"),
    firtoolOpts = Array(
      "--lowering-options=disallowLocalVariables,disallowExpressionInliningInPorts,disallowPackedArrays",
      "-disable-layers=Verification",
    ),
  )

}
