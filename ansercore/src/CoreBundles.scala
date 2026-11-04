package ansercore

import chisel3._
import chisel3.util._
import ansercore.frontend.ftq.FTQPosition
import ansercore.frontend.IFUToBackendIO
import ansercore.mmu.l1Tlb.ITlbReq
import ansercore.mmu.l1Tlb.ITLBResp
import ansercore.mmu.pmp.PMPReq
import ansercore.mmu.pmp.PMPResp
import ansercore.mmu.pma.PMAReq
import ansercore.mmu.pma.PMAResp
import ansercore.frontend.CFIAttribute
import ansercore.frontend.FTQToBackendIO

class TopToFrontendIO extends AnserBundle{
  val resetVector = Output(UInt(pcWidth.W))
  val hartId = Output(UInt(hartIdWidth.W))
}


class BackendToFrontendIO extends AnserBundle{
  val commit = Valid(new FTQPosition)
  val redirect = Valid(new BackendRedirect)
  val predQuery = Output(new FTQPosition)
}


class FrontendToBackendIO extends AnserBundle {
  val fromIFU = new IFUToBackendIO
  val fromFTQ = new FTQToBackendIO
}


class FrontendToMMUIO extends AnserBundle{
  val itlbReq = Valid(new ITlbReq)
  val pmpReq = Output(new PMPReq)
  val pmaReq = Output(new PMAReq)

}

class MMUToFrontendIO extends AnserBundle{
  val itlbResp = Output(new ITLBResp)
  val pmpResp = Output(new PMPResp)
  val pmaResp = Output(new PMAResp)
}


// FrontEnd <=> BackEnd
class BackendRedirect extends AnserBundle{
  val redirectPosition = new FTQPosition
  val target = UInt(pcWidth.W)
  val taken = Bool()
  val mispredict = Bool()
  val cfiAttribute = new CFIAttribute
}




object PrivMode extends ChiselEnum{
  val uMode = Value("b00".U(2.W))
  val sMode = Value("b01".U(2.W))
  val mMode = Value("b11".U(2.W))
}