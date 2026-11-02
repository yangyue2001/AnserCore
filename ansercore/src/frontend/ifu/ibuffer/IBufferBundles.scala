package ansercore.frontend.ifu.ibuffer

import chisel3._
import chisel3.util._

import ansercore.frontend.ftq.FTQPtr
import ansercore.frontend.FrontendExcept


class IBufferEntry extends IBufferBundle{
  val slot = UInt(16.W)
  val instEnd = Bool()
  val isLastInFetchBlock = Bool()
}

class IBufferExceptionEntry extends IBufferBundle{
  val except = FrontendExcept()
  val bankPtr = UInt(NumWriteBanks.W)
  val rowPtr = UInt(WriteBankDepth.W)
}

class InstPacket extends IBufferBundle{
  val inst = UInt(32.W)
  val isLastInFetchBlock = Bool()
  val ftqPtr= new FTQPtr
  val endOffset = UInt(FetchBlockOffsetWidth.W)
  val except = FrontendExcept()
}

class IBufferReq extends IBufferBundle{
  val ftqPtr= new FTQPtr
  val slots = Vec(FetchBlockNumSlots, UInt(16.W))
  val slotInstEnd = Vec(FetchBlockNumSlots, Bool()) // 这个slot 是否是一个指令的end
  val lastSlotOffset = UInt(FetchBlockOffsetWidth.W) // fetch Block 内部最后一个slot
}