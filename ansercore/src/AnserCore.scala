/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

package ansercore

import chisel3._

import _root_.circt.stage.ChiselStage
import ansercore.backend.softBackend.SoftBackend
import ansercore.frontend.Frontend
import difftest.DifftestModule
import ansercore.mmu.MMU
import device.axi.AXI4MasterIO
import device.axi.AXI4Params
import device.axi.AXIModule
import soc.ICacheToAXI

// resetByte is a byte address. The frontend reset vector counts halfwords.
class AnserCore(
  params: AXI4Params = AXI4Params(addrBits = 56, dataBits = 256),
  resetByte: Long = 0x80000000L,
) extends AXIModule(params)
    with HasAnserParameters {
  require(resetByte % 2 == 0, "reset byte must be halfword aligned")

  val frontend = Module(new Frontend)
  val backend  = Module(new SoftBackend)
  val mmu      = Module(new MMU)
  val bridge   = Module(new ICacheToAXI(params))

  frontend.io.resetVector := (resetByte >>> 1).U(pcWidth.W)
  mmu.io.mmuCtr           := backend.io.mmuCtr
  mmu.io.fromFrontend     := frontend.io.toMMU
  frontend.io.fromMMU     := mmu.io.toFrontend
  backend.io.fromFrontend <> frontend.io.toBackend
  frontend.io.fromBackend <> backend.io.toFrontend
  bridge.io.memReq <> frontend.io.memReq
  bridge.io.memResp <> frontend.io.memResp
  forwardMaster(io, bridge.io.axi)

  // Both ports are masters, so <> would tie two outputs together.
  private def forwardMaster(outer: AXI4MasterIO, inner: AXI4MasterIO): Unit = {
    outer.aw.valid := inner.aw.valid
    outer.aw.bits := inner.aw.bits
    inner.aw.ready := outer.aw.ready
    outer.w.valid := inner.w.valid
    outer.w.bits := inner.w.bits
    inner.w.ready := outer.w.ready
    inner.b.valid := outer.b.valid
    inner.b.bits := outer.b.bits
    outer.b.ready := inner.b.ready
    outer.ar.valid := inner.ar.valid
    outer.ar.bits := inner.ar.bits
    inner.ar.ready := outer.ar.ready
    inner.r.valid := outer.r.valid
    inner.r.bits := outer.r.bits
    outer.r.ready := inner.r.ready
  }
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

  DifftestModule.collect("dut", "out/verilog")
}
