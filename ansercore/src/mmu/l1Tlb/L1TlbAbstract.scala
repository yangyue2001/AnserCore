package ansercore.mmu.l1Tlb

import chisel3._
import chisel3.util._
import ansercore.mmu.MMUConstant.SatpAsidWidth

abstract class L1TlbBundle extends Bundle with HasL1TlbParameters
abstract class L1TlbModule extends Module with HasL1TlbParameters


abstract class L1TlbEntry extends L1TlbBundle{
  val valid = Bool()
  val ppn = UInt(ppnWidth.W)
  val vpn = UInt(vpnWidth.W)
  val pageFault = Bool()
  val accessFault = Bool()
  val g = Bool()
  val asid = UInt(SatpAsidWidth.W)
  val size = PageSize()
}

abstract class L1TlbReq extends L1TlbBundle{
  val vpn = UInt(vpnWidth.W)
}


abstract class L1TlbResp extends L1TlbBundle{
  val ppn = UInt(ppnWidth.W)
  val hit = Bool()
  val pageFault = Bool()
  val accessFault = Bool()
}



