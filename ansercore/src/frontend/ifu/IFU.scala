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
  icache.io.fetch.valid := fetch.valid
  icache.io.fetch.bits := fetch.bits.icacheFetch
  fetch.ready := icache.io.fetch.ready

  // ifuPrediction 跟着 icache 的 fire 走，s2 才和取回的指令对齐。
  val fetchS0Fire = icache.io.fetchS0Fire
  val fetchS1Fire = icache.io.fetchS1Fire
  val s0Prediction = fetch.bits.ifuPrediction
  val s0StartPC = fetch.bits.icacheFetch.fetchPC
  val s0FtqPtr = fetch.bits.icacheFetch.ftqPtr
  val s1Prediction = RegEnable(s0Prediction, fetchS0Fire)
  val s1StartPC = RegEnable(s0StartPC, fetchS0Fire)
  val s1FtqPtr = RegEnable(s0FtqPtr, fetchS0Fire)
  val s2Prediction = RegEnable(s1Prediction, fetchS1Fire)
  val s2StartPC = RegEnable(s1StartPC, fetchS1Fire)
  val s2FtqPtr = RegEnable(s1FtqPtr, fetchS1Fire)

  val resp = icache.io.fetchResp
  predecode.io.flush             := io.flush
  predecode.io.req.inst          := resp.bits.inst
  predecode.io.req.predTaken     := s2Prediction.taken
  predecode.io.req.predEndOffset := s2Prediction.endOffset
  predecode.io.req.startPC       := s2StartPC
  predecode.io.req.rasTopAddr    := s2Prediction.rasTopAddr
  predecode.io.req.predTarget    := s2Prediction.target

  val predecodeInfo = predecode.io.predecodeInfo
  val instEnd       = predecode.io.instEnd
  // 块可以停在 32 位指令的低半字。这个槽 instEnd=0，必须留给 IBuffer，
  // 下一块的 slot0 才拼得回原来的指令。
  val endSlot       = predecodeInfo.endOffset
  val trailingHalf  = !instEnd(endSlot)
  val hasInst       = WireDefault(false.B)
  val lastSlot      = WireDefault(0.U(FetchBlockOffsetWidth.W))
  for (idx <- 0 until FetchBlockNumSlots) {
    val slot = idx.U(FetchBlockOffsetWidth.W)
    when(instEnd(idx) && slot <= endSlot) {
      hasInst  := true.B
      lastSlot := slot
    }
  }
  when(trailingHalf) {
    hasInst  := true.B
    lastSlot := endSlot
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
    assert(s2FtqPtr === resp.bits.ftqPtr, "IFU prediction lost alignment with icache")
  }

  io.toFTQ.valid              := respFire
  io.toFTQ.bits.ftqPtr        := resp.bits.ftqPtr
  io.toFTQ.bits.startPc       := s2StartPC
  io.toFTQ.bits.preDecodeInfo := predecodeInfo
}
