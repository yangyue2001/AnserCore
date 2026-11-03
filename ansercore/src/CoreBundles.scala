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
/*
class BackendToFrontendIO extends YQHBundle{
  val frontendCsr = Output(new )
  val redirect = Valid(new )

}
*/

class FrontEndToMemIO extends YQHBundle{

}

class MemToFrontend extends YQHBundle{

}

class TopToFrontendIO extends YQHBundle{
  val resetVector = Output(UInt(pcWidth.W))
  val hartId = Output(UInt(hartIdWidth.W))
}


class BackendToFrontendIO extends YQHBundle{
  val redirect = new BackendRedirect
}


class FrontendToBackendIO extends YQHBundle {
    // Frontend -> Backend
    //val fromFTQ     = new FTQToCtrlIO
    val fromIFU     = new IFUToBackendIO
    // Backend -> Frontend
    //val toFTQ       = Flipped(new CtrlToFTQIO)
    val redirect = Flipped(new BackendRedirect)
  }


class FrontendToMMUBundle extends YQHBundle{
  val itlbReq = Valid(new ITlbReq)
  val pmpReq = new PMPReq
  val pmaReq = new PMAReq

}

class MMUToFrontendIO extends YQHBundle{
  val itlbResp = new ITLBResp
  val pmpResp = new PMPResp
  val pmaResp = new PMAResp
}


// FrontEnd <=> BackEnd
class BackendRedirect extends YQHBundle{
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