package ansercore.mmu.l2Tlb

import chisel3._
import chisel3.util._

class L2Tlb extends L2TlbModule{
  class L2TlbIO extends L2TlbBundle{

  }
  val io = IO(new L2TlbIO)
  //val l0PteCache =
  //val l1PteCache =
  //val l2PteCache =
  //val l3PteCache =
}