package ansercore.frontend.ifu

import chisel3._
import chisel3.util._
import ansercore.frontend._

class fetchBypassReq extends IFUBundle{
  val pAddr = UInt(paddrWidth.W)
}
class fetchBypassResp extends IFUBundle{
  val hit = Bool()
  val inst = UInt(InstBitsWidth.W)
}
/*

class ItlbReq(params: FrontendParameters) extends Bundle {
  val vaddr = UInt(params.vaddrBits.W)
}

class ItlbResp(params: FrontendParameters) extends Bundle {
  val ptag = UInt(params.ptagBits.W)
  val excp = UInt(params.tlbExcpBits.W)
  val memAttr = UInt(params.memAttrBits.W)
  val miss = Bool()
  val cacheable = Bool()
}

class ItlbIO(params: FrontendParameters) extends Bundle {
  val req = Decoupled(new ItlbReq(params))
  val resp = Flipped(Valid(new ItlbResp(params)))
}

class IcacheTagReq(params: FrontendParameters) extends Bundle {
  val ftqPtr = UInt(params.ftqPtrBits.W)
  val vaddr = UInt(params.vaddrBits.W)
  val needSecondLine = Bool()
}

class IcacheTagResp(params: FrontendParameters) extends Bundle {
  val tags = Vec(params.maxFetchLines, Vec(params.icacheWays, UInt(params.ptagBits.W)))
  val valids = Vec(params.maxFetchLines, UInt(params.wayBits.W))
}

class IcacheDataReq(params: FrontendParameters) extends Bundle {
  val ftqPtr = UInt(params.ftqPtrBits.W)
  val vaddr = UInt(params.vaddrBits.W)
  val lineWaymask = Vec(params.maxFetchLines, UInt(params.wayBits.W))
  val needSecondLine = Bool()
}

class IcacheDataResp(params: FrontendParameters) extends Bundle {
  val data = Vec(params.maxFetchLines, UInt(params.cacheLineBits.W))
}

class IcacheRefill(params: FrontendParameters) extends Bundle {
  val valid = Bool()
  val ptag = UInt(params.ptagBits.W)
  val vSetIndex = UInt(params.cacheBlockIndexBits.W)
  val way = UInt(params.wayIdxBits.W)
  val data = UInt(params.cacheLineBits.W)
}

class IcacheIO(params: FrontendParameters) extends Bundle {
  val tagReq = Decoupled(new IcacheTagReq(params))
  val tagResp = Flipped(Valid(new IcacheTagResp(params)))
  val dataReq = Decoupled(new IcacheDataReq(params))
  val dataResp = Flipped(Valid(new IcacheDataResp(params)))
  val refill = Valid(new IcacheRefill(params))
}

class WayLookupLineInfo(params: FrontendParameters) extends Bundle {
  val waymask = UInt(params.wayBits.W)
  val vSetIndex = UInt(params.cacheBlockIndexBits.W)
  val ptag = UInt(params.ptagBits.W)
  val miss = Bool()
}

class WayLookupEntry(params: FrontendParameters) extends Bundle {
  val ftqPtr = UInt(params.ftqPtrBits.W)
  val startPC = UInt(params.vaddrBits.W)
  val needSecondLine = Bool()
  val tlb = new ItlbResp(params)
  val lines = Vec(params.maxFetchLines, new WayLookupLineInfo(params))
}

class WayLookupFetchReq(params: FrontendParameters) extends Bundle {
  val ftqPtr = UInt(params.ftqPtrBits.W)
}

class WayLookupRefillUpdate(params: FrontendParameters) extends Bundle {
  val ptag = UInt(params.ptagBits.W)
  val vSetIndex = UInt(params.cacheBlockIndexBits.W)
  val way = UInt(params.wayIdxBits.W)
}

class PrefetchMissReq(params: FrontendParameters) extends Bundle {
  val ftqPtr = UInt(params.ftqPtrBits.W)
  val startPC = UInt(params.vaddrBits.W)
  val missMask = Vec(params.maxFetchLines, Bool())
  val lines = Vec(params.maxFetchLines, new WayLookupLineInfo(params))
}

class FetchMissReq(params: FrontendParameters) extends Bundle {
  val ftqPtr = UInt(params.ftqPtrBits.W)
  val startPC = UInt(params.vaddrBits.W)
  val paddrBlock = UInt((params.paddrBits - params.cacheLineOffsetBits).W)
  val burst = Bool()
}

class IFUMemReq(params: FrontendParameters) extends Bundle {
  val isFetch = Bool()
  val mshrId = UInt(params.mshrIdxBits.W)
  val blockAddr = UInt((params.paddrBits - params.cacheLineOffsetBits).W)
  val burst = Bool()
}

class IFUMemResp(params: FrontendParameters) extends Bundle {
  val isFetch = Bool()
  val mshrId = UInt(params.mshrIdxBits.W)
  val data = UInt(params.cacheLineBits.W)
}

class FetchMshrResp(params: FrontendParameters) extends Bundle {
  val ftqPtr = UInt(params.ftqPtrBits.W)
  val data = UInt(params.fetchBits.W)
}

class IBufferEnq(params: FrontendParameters) extends Bundle {
  val packet = new FetchPacket(params)
}
*/