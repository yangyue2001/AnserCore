package ansercore.mmu.l1Tlb

import chisel3._
import chisel3.util._
import ansercore.mmu.SfenceBundle

class ITlb extends L1TlbModule {
  class ITlbIO extends L1TlbBundle{
    val sfenceReq = Flipped(Valid(new SfenceBundle))
    val itlbReq = Flipped(Valid(new ITlbReq))
    //val itlbResp = OutPut(new itlbResp)
    //val itlbMissReq
  }
  val io = IO(new ITlbIO)
//alias
  val sfenceReqValid = io.sfenceReq.valid
  val sfenceReq = io.sfenceReq.bits
  val itlbReqValid = io.itlbReq.valid
  val itlbReq = io.itlbReq
}