/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

package difftest.common

import chisel3._

class DifftestFlash extends ExtModule {
  val en   = IO(Input(Bool()))
  val addr = IO(Input(UInt(32.W)))
  val data = IO(Output(UInt(64.W)))
}

object DifftestFlash {
  def apply() = Module(new DifftestFlash)
}
