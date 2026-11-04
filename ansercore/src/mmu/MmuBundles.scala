package ansercore.mmu

import chisel3._
import chisel3.util._
import ansercore.mmu.MMUConstant._
import ansercore.CoreConstant.XLEN
import ansercore.PrivMode


object Pbmt extends ChiselEnum{
  val pma = Value("b00".U(2.W))
  val nc = Value("b01".U(2.W))
  val io = Value("b10".U(2.W))
}

class SfenceBundle extends MMUBundle{
  val allVaddr = Bool()
  val vpn = UInt(vpnWidth.W)
  val allAsid = Bool()
  val asid = UInt(SatpAsidWidth.W)
}

class MMUCtr extends MMUBundle{
  val privMode = PrivMode()
  val satp = UInt(XLEN.W)
  val mstatusMPRV = Bool()
  val mstatusMPP = PrivMode()
  val mstatusSUM = Bool()
  val mstatusMXR = Bool()
}