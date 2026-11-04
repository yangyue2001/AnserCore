/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

package device.axi

import chisel3._

// valid_o/ch_o leave the UART. valid_in is the UART's read strobe. ch_in comes from outside.
class UartIO extends Bundle {
  val valid_o  = Output(Bool())
  val ch_o     = Output(UInt(8.W))
  val valid_in = Output(Bool())
  val ch_in    = Input(UInt(8.W))
}
