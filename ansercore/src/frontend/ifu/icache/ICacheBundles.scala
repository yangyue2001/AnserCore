package ansercore.frontend.ifu.icache

import chisel3._
import chisel3.util._
import utils.CircularQueuePtr
import ansercore.Parameters
import ansercore.frontend.ftq.FTQPtr


class ICacheFetchResp extends ICacheBundle{
  val ftqPtr = new FTQPtr
  val endOffset = UInt(FetchBlockOffsetWidth.W)
  val inst = Vec(FetchBlockNumSlots, UInt((MinInstBytes * 8).W))
}

class ICacheRefillReq extends ICacheBundle{
  val setIdx = UInt(SetIdxWidth.W)
  val pTag = UInt(tagWidth.W)
  val data = UInt(CachelineBits.W)
}

// way 要等 tag 读回来、s1 选定之后才有。MissUnit 的请求里不带。
class ICacheRefillInfo extends ICacheBundle{
  val setIdx = UInt(SetIdxWidth.W)
  val pTag = UInt(tagWidth.W)
  val wayMask = UInt(numWays.W)
}

class ICacheMissReq extends ICacheBundle{
  val vAlias = UInt(VaddrAliasWidth.W)
  val beatPAddr = UInt(ICacheToL2BeatAddrWidth.W)
  val crossCacheline = Bool()
}

class ICacheTagEntry extends ICacheBundle{
  val valid = Bool()
  val tag = UInt(tagWidth.W)
}

class ICacheDataEntry extends ICacheBundle{
  val data = UInt(DataWidth.W)
}

// fetch 三级流水里带着走的控制。way 在并行时要等 wayLookup，s0 先记下来的 mask 只给串行用。
class ICacheFetchMeta extends ICacheBundle{
  val ftqPtr = new FTQPtr
  val endOffset = UInt(FetchBlockOffsetWidth.W)
  val parallel = Bool()
  val lineOff = UInt(OffsetWidth.W)
  val lineBytes = Vec(2, UInt(FetchByteWidth.W))
  val crossCacheline = Bool()
  val bankMask = Vec(2, UInt(DataNumBanks.W))
  val setIdx = UInt(SetIdxWidth.W)
  val wayMask = Vec(2, UInt(numWays.W))
  val pTag = UInt(tagWidth.W)
  val except = new WayLookupException
  val isNC = Bool()
  val isMMIO = Bool()
}

class ICacheFetchWindow extends ICacheBundle{
  val crossCacheline = Bool()
  val bankMask = Vec(2, UInt(DataNumBanks.W))
  val lineBytes = Vec(2, UInt(FetchByteWidth.W))
  val lineOff = UInt(OffsetWidth.W)
  val endOffset = UInt(FetchBlockOffsetWidth.W)
}

//wayLookup

class WayLookupPtr(implicit p: Parameters) extends CircularQueuePtr[WayLookupPtr](p.frontendParameters.ifuParameters.icache.wayLookupSize)

class WayLookupTLBInfo extends ICacheBundle{
  val pTag = UInt(tagWidth.W)
  val isNC = Bool()
  val isMMIO = Bool()
}


class WayLookupException extends ICacheBundle{
  val pageFault = Bool()
  val accessFault = Bool()
}

class WayLookupPacket extends ICacheBundle{
  val wayLookupInfo = new WayLookupEntry
  val exceptInfo = new WayLookupException
}


class WayLookupExceptionEntry extends ICacheBundle{
  val ptr = UInt(WayLookupWidth.W)
  val except = new WayLookupException
}

class WayLookupCacheInfo extends ICacheBundle{
  val vSetIdx = UInt(SetIdxWidth.W)
  val crossCacheline = Bool()
  val wayMask = Vec(2, UInt(numWays.W))
}

class WayLookupEntry extends ICacheBundle{
  val itlbInfo = new WayLookupTLBInfo
  val cacheInfo = new WayLookupCacheInfo
}


//MissUnit
class MshrEntry extends ICacheBundle{
  val valid = Bool()
  val flush = Bool()
  val waitIssue = Vec(2, Bool())
  val waitResp = Vec(2, Bool())
  val refill = Vec(2, Bool())
  val beatPAddr = UInt(ICacheToL2BeatAddrWidth.W)
  val vAlias = UInt(VaddrAliasWidth.W)
  val fetch = Vec(2, Bool())

  def slotBusy(slot: Int): Bool ={
    waitIssue(slot) || waitResp(slot) || refill(slot)
  }
}


class MissDataBuffer extends ICacheBundle {
  val busy = Bool()
  val hold = Bool()
  val idx = UInt(log2Ceil(NumMshrs).W)
  val line = UInt(1.W)
  val data = Vec(ICacheToL2BeatsPerLine, UInt(ICacheToL2DataWidth.W))
}