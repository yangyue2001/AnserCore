package ansercore.mmu.l1Tlb

import chisel3._
import chisel3.util._

class ITlbEntry extends L1TlbEntry{
  val u = Bool()
  val x = Bool()
}

class DTlbEntry extends L1TlbEntry{
  val d = Bool()
  val u = Bool()
  val x = Bool()
  val w = Bool()
  val r = Bool()
}

class ITlbReq extends L1TlbReq{

}

class ITLBResp extends L1TlbResp{

}

class DTlbReq extends L1TlbReq{

}

object PageSize extends ChiselEnum{
  val size4KB, size64KB, size2MB, size1GB, size512GB, size256GB = Value
}

class L1TlbMissReq extends L1TlbBundle{
  val vpn = UInt(vpnWidth.W)
  val id = UInt()
}


class OutstandingEntry extends L1TlbBundle{
  val valid = Bool()
  val sent = Bool()
  val vpn = UInt(vpnWidth.W)
  val robIdx = UInt(RobIdxWidth.W)
}