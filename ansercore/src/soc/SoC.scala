/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

package soc

import chisel3._

import circt.stage.ChiselStage
import device.axi._

// Empty SoC: the core is the only master. Every address reaches a DECERR slave.
class SoC(val params: AXI4Params = AXI4Params()) extends Module {
  val core = Module(new AnserCore(params))
  val xbar = Module(
    new AXI4Xbar(
      nMasters   = 1,
      slaveAddrs = Seq(Seq(AXI4AddrSet(0, (BigInt(1) << params.addrBits) - 1))),
      params     = params,
    )
  )
  val error = Module(new AXI4Error(xbar.outParams))

  xbar.io.in(0) <> core.io.axi
  error.io <> xbar.io.out(0)
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
