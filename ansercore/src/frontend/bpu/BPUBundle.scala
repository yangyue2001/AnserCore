package ansercore.frontend.bpu


import chisel3._
import chisel3.util._

import ansercore.frontend.bpu.ras.RASMeta
import ansercore.frontend.bpu.tage.TAGEMeta
import ansercore.frontend.bpu.ftb.FTBMeta
import ansercore.frontend.bpu.phr.PHRMeta
import ansercore.frontend.bpu.ittage.ITTAGEMeta
import ansercore.frontend.ftq.FTQPtr
import ansercore.frontend.ftq.FTQFetchBlcokEntry
import ansercore.frontend.CFIAttribute


/* *** public *** */
// Csr -> BPU
class BPUCtrl extends BPUBundle {
  //predictor enable
  val uftbEnable: Bool = Bool()
  val abtbEnable: Bool = Bool()
  val ftbEnable: Bool = Bool()
  val tageEnable:   Bool = Bool()
  val ittageEnable: Bool = Bool()
  val rasEnable:    Bool = Bool()
}

class BPUMeta extends BPUBundle {
  val ftb = new FTBMeta
  val tage = Vec(NumBr, new TAGEMeta)
  val ittage = new ITTAGEMeta
  val phr = new PHRMeta
  val ras = new RASMeta
}


class BPUS3Result extends BPUBundle{
  val meta = new BPUMeta
  val prediction = new BPUPrediction
  val s3FTQPtr = new FTQPtr
  val s3Override = Bool()
}

// BPU -> FTQ
class BPUPrediction extends BPUBundle {
  val target  = UInt(pcWidth.W)
  val endOffset = UInt(FetchBlockOffsetWidth.W)
  val endCFIAttribute = new CFIAttribute
  val taken = Bool()
}

class BPURedirectMeta extends BPUBundle {
  val phr = new PHRMeta
  val ras = new RASMeta
}

class BPUTrainMeta extends BPUBundle {
  val ftb    = new FTBMeta
  val tage   = Vec(NumBr,new TAGEMeta)
  val ittage = new ITTAGEMeta
  val phr    = new PHRMeta
}

class BPURedirect extends BPUBundle {
  val cfiPC     = UInt(pcWidth.W)
  val target    = UInt(pcWidth.W)
  val taken     = Bool()
  val rasAction = UInt(2.W)
  val meta      = new BPURedirectMeta
}


// Backend & FTQ -> BPU
class BPUTrain extends BPUBundle {
  val fetchBlockInfo = new FTQFetchBlcokEntry
  val meta = new BPUTrainMeta
}