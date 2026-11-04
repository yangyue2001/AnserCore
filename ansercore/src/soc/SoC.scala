/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

package soc

import chisel3._

import _root_.circt.stage.ChiselStage
import ansercore.AnserCore
import ansercore.backend.softBackend.SoftRetire
import device.axi._

// The core fetches through one AXI master. RAM lives at memBase; other addresses stall.
class SoC(
  val params: AXI4Params = AXI4Params(addrBits = 56, dataBits = 256),
  val memBase: Long = 0x80000000L,
  val memBytes: Long = 128L * 1024 * 1024,
) extends Module {
  val io = IO(new Bundle {
    val retire = Output(new SoftRetire)
  })

  require(memBytes >= 64 && (memBytes & (memBytes - 1)) == 0, "AXI memory size must be a power of two")
  require((memBase & (memBytes - 1)) == 0, "AXI memory base must be aligned to its size")
  require(params.dataBits >= 64 && params.dataBits % 64 == 0, "AXI data width must be a multiple of 8 bytes")

  val core = Module(new AnserCore)
  // resetVector counts halfwords. The first fetch is the first byte of AXI memory.
  core.io.resetVector := (memBase >>> 1).U(core.io.resetVector.getWidth.W)

  val bridge = Module(new ICacheToAXI(params))
  bridge.io.memReq <> core.io.memReq
  bridge.io.memResp <> core.io.memResp

  val xbar = Module(
    new AXI4Xbar(
      nMasters   = 1,
      slaveAddrs = Seq(Seq(AXI4AddrSet(memBase, memBytes - 1))),
      params     = params,
    )
  )
  val mem = Module(new AXI4Memory(memBytes, memBase, xbar.outParams))

  xbar.io.in(0) <> bridge.io.axi
  mem.io <> xbar.io.out(0)
  io.retire := core.io.retire
}

object Main extends App {
  println("Generating the SoC hardware design...")

  ChiselStage.emitSystemVerilogFile(
    gen         = new SoC(),
    args        = Array("--target-dir", "out/verilog"),
    firtoolOpts = Array(
      "-preserve-aggregate=1d-vec",
      "-disable-layers=Verification",
      "--disable-opt",
    ),
  )

  ChiselStage.emitSystemVerilogFile(
    gen         = new SoC(),
    args        = Array("--target-dir", "out/sta"),
    firtoolOpts = Array(
      "--lowering-options=disallowLocalVariables,disallowExpressionInliningInPorts,disallowPackedArrays",
      "-disable-layers=Verification",
      "--disable-opt",
    ),
  )
}
