package ansercore.frontend.bpu.ras

import chisel3._
import chisel3.util._
import utils.QueuePtr


class RASPtr(numEntrys: Int) extends QueuePtr[RASPtr](numEntrys)

class RASMeta extends RASBundle{
  val sp      = new RASPtr(NumEntrys)
  val topAddr = UInt(pcWidth.W)
}

class RASUpdateReq extends RASBundle {
  val rasAction = UInt(2.W)
  val pushPC    = UInt(pcWidth.W)
}

class RASRecoverReq extends RASBundle {
  val meta     = new RASMeta
  val update   = new RASUpdateReq
}
