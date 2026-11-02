package ansercore.mmu

import chisel3._
import chisel3.util._
import ansercore.mmu.MmuConstant._


object Pbmt extends ChiselEnum{
  val pma = Value("b00".U(2.W))
  val nc = Value("b01".U(2.W))
  val io = Value("b10".U(2.W))
}

class SfenceBundle extends MmuBundle{
  val allVaddr = Bool()
  val vpn = UInt(vpnWidth.W)
  val allAsid = Bool()
  val asid = UInt(SatpAsidWidth.W)
}
