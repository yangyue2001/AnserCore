package ansercore.frontend.ifu.icache

import chisel3._
import chisel3.util._
import utils.CircularQueuePtr
import ansercore.Parameters
import ansercore.frontend.ftq.FTQPtr
import ansercore.frontend.FrontendExcept

class ICacheFetch extends ICacheBundle{
  val fetchPC = UInt(pcWidth.W)
  val ftqPtr = new FTQPtr
  val parallel = Bool()
}

class ICacheFetchWindow extends ICacheBundle{
  val crossCacheline = Bool()
  val bankMask = Vec(2, UInt(DataNumBanks.W))
  val lineBytes = Vec(2, UInt(FetchByteWidth.W))
  val lineOff = UInt(OffsetWidth.W)
  val endOffset = UInt(FetchBlockOffsetWidth.W)
}

class ICacheFetchResp extends ICacheBundle{
  val ftqPtr = new FTQPtr
  val inst = Vec(FetchBlockNumSlots, UInt((MinInstBytes * 8).W))
  val except = FrontendExcept()
}

class ICacheRefillReq extends ICacheBundle{
  val refillInfo = new ICacheRefillInfo
  val pTag = UInt(tagWidth.W)
  val data = UInt(CachelineBits.W)
}

// way 要等 tag 读回来、s1 选定之后才有。MissUnit 的请求里不带。
class ICacheRefillInfo extends ICacheBundle{
  val setIdx = UInt(SetIdxWidth.W)
  val wayMask = UInt(numWays.W)
}

class ICacheMissReq extends ICacheBundle{
  val setIdx = UInt(SetIdxWidth.W)
  val wayMask = Vec(2, UInt(numWays.W))
  val pTag = UInt(tagWidth.W)
  val crossCacheline = Bool()
}

class ICacheTagEntry extends ICacheBundle{
  val valid = Bool()
  val tag = UInt(tagWidth.W)
}

class ICacheDataEntry extends ICacheBundle{
  val data = UInt(DataWidth.W)
}

//wayLookup

class WayLookupPtr(implicit p: Parameters) extends CircularQueuePtr[WayLookupPtr](p.frontendParameters.ifuParameters.icache.wayLookupSize)

class WayLookupTLBInfo extends ICacheBundle{
  val isNC = Bool()
  val isMMIO = Bool()
}

class WayLookupPacket extends ICacheBundle{
  val wayLookupInfo = new WayLookupEntry
  val exceptInfo = FrontendExcept()
}


class WayLookupExceptionEntry extends ICacheBundle{
  val ptr = UInt(WayLookupWidth.W)
  val except = FrontendExcept()
}

class WayLookupCacheInfo extends ICacheBundle{
  val vSetIdx = UInt(SetIdxWidth.W)
  val crossCacheline = Bool()
  val inFlying = Vec(2, Bool())
  val wayMask = Vec(2, UInt(numWays.W))
}

class WayLookupEntry extends ICacheBundle{
  val itlbInfo = new WayLookupTLBInfo
  val cacheInfo = new WayLookupCacheInfo
}

//MissUnit
class MshrEntry extends ICacheBundle{
  val states = Vec(2, MSHRState())
  val refillWay = Vec(2, UInt(numWays.W))
  val vSetIdx = UInt(SetIdxWidth.W)
  val pTag = UInt(tagWidth.W)

  def slotIdle(slot: Int): Bool = {
    states(slot) === MSHRState.idle
  }

  def slotValid(slot: Int): Bool ={
    !slotIdle(slot)
  }

  def slotIssue(slot: Int): Bool ={
    states(slot) === MSHRState.issue
  }

  def slotResponse(slot: Int): Bool = {
    states(slot) === MSHRState.response
  }

  def slotRefill(slot: Int): Bool = {
    states(slot) === MSHRState.refill
  }

  def valid: Bool = {
    slotValid(0) || slotValid(1)
  }
}

object MSHRState extends ChiselEnum{
  val idle, issue, response, refill = Value
}

class MissDataBuffer extends ICacheBundle {
  val refill = Bool()
  val beats = UInt(ICacheToL2BeatWidth.W)
  val idx = UInt(log2Ceil(NumMshrs).W)
  val line = Bool()
  val data = Vec(ICacheToL2BeatsPerLine, UInt(ICacheToL2DataWidth.W))
}