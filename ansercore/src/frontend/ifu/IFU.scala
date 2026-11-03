package ansercore.frontend.ifu

import chisel3._
import chisel3.util._
import ansercore.frontend.FTQToIFUIO
import ansercore.frontend.IFUToFTQIO
import ansercore.frontend.IFUToBackendIO
import ansercore.frontend.ftq.FTQPtr
import ansercore.frontend.ifu.icache.ICache
import ansercore.frontend.ifu.icache.ICacheMemResp
import ansercore.frontend.ifu.predecode.PreDecode
import ansercore.frontend.ifu.ibuffer.IBuffer
import ansercore.FrontendToMMUBundle
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
    val toMMU     = Output(new FrontendToMMUBundle)
    val fromMMU   = Input(new MMUToFrontendIO)
  }
  val io: IFUIO = IO(new IFUIO)

  val icache        = Module(new ICache)
  val predecode     = Module(new PreDecode)
  val ibuffer       = Module(new IBuffer)
  // ICache 会丢掉 startPC 和预测，并且返回拍数不固定。按取指进入的顺序先存起来。
  val fetchSideband = Module(new Queue(new FetchSideband, 2, hasFlush = true))
  fetchSideband.io.flush.get := io.flush

  icache.io.flush             := io.flush
  icache.io.fencei            := false.B
  icache.io.privMode          := PrivMode.mMode
  icache.io.fromMMU           := io.fromMMU
  io.toMMU                    := icache.io.toMMU
  icache.io.bpuOverride.valid := io.fromFTQ.s3Override
  icache.io.bpuOverride.bits  := 0.U.asTypeOf(new FTQPtr)
  icache.io.memReq.ready      := false.B
  icache.io.memResp.valid     := false.B
  icache.io.memResp.bits      := 0.U.asTypeOf(new ICacheMemResp)

  icache.io.lookup.valid  := io.fromFTQ.lookUp.valid
  icache.io.lookup.bits   := io.fromFTQ.lookUp.bits
  io.fromFTQ.lookUp.ready := icache.io.lookup.ready

  val fetch   = io.fromFTQ.fetch
  val sideEnq = fetchSideband.io.enq
  icache.io.fetch.valid      := fetch.valid && sideEnq.ready
  icache.io.fetch.bits       := fetch.bits
  fetch.ready                := icache.io.fetch.ready && sideEnq.ready
  sideEnq.valid              := fetch.valid && icache.io.fetch.ready
  sideEnq.bits.ftqPtr        := fetch.bits.ftqPtr
  sideEnq.bits.startPC       := fetch.bits.fetchPC
  sideEnq.bits.predEndOffset := fetch.bits.endOffset
  sideEnq.bits.taken         := fetch.bits.taken
  sideEnq.bits.target        := fetch.bits.target
  sideEnq.bits.rasTopAddr    := fetch.bits.rasTopAddr

  val resp = icache.io.fetchResp
  val side = fetchSideband.io.deq
  predecode.io.flush             := io.flush
  predecode.io.req.inst          := resp.bits.inst
  predecode.io.req.predTaken     := side.bits.taken
  predecode.io.req.predEndOffset := side.bits.predEndOffset
  predecode.io.req.startPC       := side.bits.startPC
  predecode.io.req.rasTopAddr    := side.bits.rasTopAddr
  predecode.io.req.predTarget    := side.bits.target

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

  val respHold = resp.valid && side.valid && !io.flush
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
  resp.ready        := side.valid && respReady && !io.flush
  side.ready        := resp.valid && respReady && !io.flush
  predecode.io.fire := respFire
  when(respFire) {
    assert(side.bits.ftqPtr === resp.bits.ftqPtr, "IFU fetch sideband lost alignment with icache")
  }

  io.toFTQ.valid              := respFire
  io.toFTQ.bits.ftqPtr        := resp.bits.ftqPtr
  io.toFTQ.bits.startPc       := side.bits.startPC
  io.toFTQ.bits.preDecodeInfo := predecodeInfo
}
