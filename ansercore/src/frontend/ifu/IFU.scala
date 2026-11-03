package ansercore.frontend.ifu

import chisel3._
import chisel3.util._
import ansercore.frontend.FTQToIFUIO
import ansercore.frontend.IFUToFTQIO
import ansercore.frontend.IFUToBackendIO
import ansercore.frontend.ftq.FTQPtr
import ansercore.frontend.ifu.icache.ICache
import ansercore.frontend.ifu.icache.ICacheMemReq
import ansercore.frontend.ifu.icache.ICacheMemResp
import ansercore.frontend.ifu.predecode.PreDecode
import ansercore.frontend.ifu.ibuffer.IBuffer
import ansercore.FrontendToMMUIO
import ansercore.MMUToFrontendIO
import ansercore.PrivMode

class IFU extends IFUModule {
  class FetchSideband extends IFUBundle {
    val ftqPtr        = new FTQPtr
    val startPC       = UInt(pcWidth.W)
    val predEndOffset = UInt(FetchBlockOffsetWidth.W)
    val taken         = Bool()
    val target        = UInt(pcWidth.W)
    val rasTopAddr    = UInt(pcWidth.W)
  }

  class IFUIO extends IFUBundle {
    val flush     = Input(Bool())
    val fromFTQ   = Flipped(new FTQToIFUIO)
    val toFTQ     = Valid(new IFUToFTQIO)
    val toBackend = new IFUToBackendIO
    val toMMU     = Output(new FrontendToMMUIO)
    val fromMMU   = Input(new MMUToFrontendIO)
    val memReq    = Decoupled(new ICacheMemReq)
    val memResp   = Flipped(Decoupled(new ICacheMemResp))
  }
  val io: IFUIO = IO(new IFUIO)

  val icache    = Module(new ICache)
  val predecode = Module(new PreDecode)
  val ibuffer   = Module(new IBuffer)

  icache.io.flush             := io.flush
  icache.io.fencei            := false.B
  icache.io.privMode          := PrivMode.mMode
  icache.io.fromMMU           := io.fromMMU
  io.toMMU                    := icache.io.toMMU
  icache.io.bpuOverride.valid := io.fromFTQ.s3Override
  icache.io.bpuOverride.bits  := 0.U.asTypeOf(new FTQPtr)
  icache.io.memReq <> io.memReq
  icache.io.memResp <> io.memResp

  icache.io.lookup.valid  := io.fromFTQ.lookUp.valid
  icache.io.lookup.bits   := io.fromFTQ.lookUp.bits
  io.fromFTQ.lookUp.ready := icache.io.lookup.ready

  val fetch = io.fromFTQ.fetch
  icache.io.fetch <> fetch
  // s2 会因为 miss 停很多拍。sideband 跟 fetch 的 s0/s1 走，才能和 fetchResp 对齐。
  val s0Side = Wire(new FetchSideband)
  s0Side.ftqPtr        := fetch.bits.ftqPtr
  s0Side.startPC       := fetch.bits.fetchPC
  s0Side.predEndOffset := fetch.bits.endOffset
  s0Side.taken         := fetch.bits.taken
  s0Side.target        := fetch.bits.target
  s0Side.rasTopAddr    := fetch.bits.rasTopAddr
  val s1Side = RegEnable(s0Side, icache.io.fetchS0Fire)
  val s2Side = RegEnable(s1Side, icache.io.fetchS1Fire)

  val resp = icache.io.fetchResp
  predecode.io.flush             := io.flush
  predecode.io.req.inst          := resp.bits.inst
  predecode.io.req.predTaken     := s2Side.taken
  predecode.io.req.predEndOffset := s2Side.predEndOffset
  predecode.io.req.startPC       := s2Side.startPC
  predecode.io.req.rasTopAddr    := s2Side.rasTopAddr
  predecode.io.req.predTarget    := s2Side.target

  val predecodeInfo = predecode.io.predecodeInfo
  val instEnd       = predecode.io.instEnd
  // 32 位指令被截成低半字时，半字留在 predecode，IBuffer 只收完整指令。
  val hasInst       = WireDefault(false.B)
  val lastSlot      = WireDefault(0.U(FetchBlockOffsetWidth.W))
  for (idx <- 0 until FetchBlockNumSlots) {
    val slot = idx.U(FetchBlockOffsetWidth.W)
    when(instEnd(idx) && slot <= predecodeInfo.endOffset) {
      hasInst  := true.B
      lastSlot := slot
    }
  }

  val respHold = resp.valid && !io.flush
  ibuffer.io.flush                   := io.flush
  ibuffer.io.enq.valid               := respHold && hasInst
  ibuffer.io.enq.bits.ftqPtr         := resp.bits.ftqPtr
  ibuffer.io.enq.bits.slots          := resp.bits.inst
  ibuffer.io.enq.bits.slotInstEnd    := instEnd
  ibuffer.io.enq.bits.lastSlotOffset := lastSlot
  io.toBackend.inst.zip(ibuffer.io.deq).foreach { case (out, deq) =>
    out.valid := deq.valid
    out.bits  := deq.bits
    deq.ready := out.ready
  }

  val respReady = !hasInst || ibuffer.io.enq.ready
  val respFire  = respHold && respReady
  resp.ready        := respReady && !io.flush
  predecode.io.fire := respFire
  when(respFire) {
    assert(s2Side.ftqPtr === resp.bits.ftqPtr, "IFU fetch sideband lost alignment with icache")
  }

  io.toFTQ.valid              := respFire
  io.toFTQ.bits.ftqPtr        := resp.bits.ftqPtr
  io.toFTQ.bits.startPc       := s2Side.startPC
  io.toFTQ.bits.preDecodeInfo := predecodeInfo
}
