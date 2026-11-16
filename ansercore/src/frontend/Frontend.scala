package ansercore.frontend

import chisel3._
import chisel3.util._

import ansercore.BackendToFrontendIO
import ansercore.FrontendToBackendIO
import ansercore.FrontendToMMUIO
import ansercore.MMUToFrontendIO
import ansercore.frontend.bpu.BPU
import ansercore.frontend.bpu.BPUCtrl
import ansercore.frontend.ftq.FTQ
import ansercore.frontend.ifu.IFU
import ansercore.frontend.ifu.icache.ICacheMemReq
import ansercore.frontend.ifu.icache.ICacheMemResp

class Frontend extends FrontendModule {
  class FrontendIO extends FrontendBundle {
    val resetVector = Input(UInt(pcWidth.W))
    val toMMU       = Output(new FrontendToMMUIO)
    val fromMMU     = Input(new MMUToFrontendIO)
    val toBackend   = new FrontendToBackendIO
    val fromBackend = Flipped(new BackendToFrontendIO)
    val memReq      = Decoupled(new ICacheMemReq)
    val memResp     = Flipped(Decoupled(new ICacheMemResp))
  }
  val io = IO(new FrontendIO)

  val bpu = Module(new BPU)
  val ftq = Module(new FTQ)
  val ifu = Module(new IFU)

  bpu.io.ctrl        := 0.U.asTypeOf(new BPUCtrl)
  bpu.io.resetVector := io.resetVector
  bpu.io.fromFTQ <> ftq.io.toBPU
  bpu.io.toFTQ <> ftq.io.fromBPU

  ftq.io.toIFU <> ifu.io.fromFTQ
  ftq.io.fromIFU <> ifu.io.toFTQ
  ftq.io.fromBackend.commit <> io.fromBackend.commit
  ftq.io.fromBackend.redirect <> io.fromBackend.redirect
  ftq.io.fromBackend.predQuery := io.fromBackend.predQuery
  io.toBackend.fromFTQ <> ftq.io.toBackend

  ifu.io.flush := io.fromBackend.redirect.valid
  ifu.io.fromMMU <> io.fromMMU
  ifu.io.toMMU <> io.toMMU
  ifu.io.toBackend <> io.toBackend.fromIFU
  ifu.io.memReq <> io.memReq
  ifu.io.memResp <> io.memResp
}
