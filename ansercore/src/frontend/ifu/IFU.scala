package ansercore.frontend.ifu

import chisel3._
import chisel3.util._
import ansercore.frontend.FTQToIFUIO
import ansercore.frontend.IFUToFTQIO
import ansercore.frontend.IFUToBackendIO
import ansercore.frontend.ifu.icache.ICache
import ansercore.frontend.ifu.icache.ICacheMemResp
import ansercore.FrontendToMMUBundle
import ansercore.MMUToFrontendIO
import ansercore.PrivMode


class IFU extends IFUModule {
  class IFUIO extends IFUBundle {
    val flush = Input(Bool())
    val fromFTQ = Flipped(new FTQToIFUIO)
    val toFTQ = Valid(new IFUToFTQIO)
    val toBackend = Flipped(new IFUToBackendIO)
    val toMMU = Output(new FrontendToMMUBundle)
    val fromMMU = Input(new MMUToFrontendIO)
  }
  val io: IFUIO = IO(new IFUIO)

  val ftqLookup = io.fromFTQ.lookUp
  val bpuOverride = io.fromFTQ.s3Override

  val icache = Module(new ICache)
  icache.io.flush := io.flush
  icache.io.fencei := false.B
  icache.io.privMode := PrivMode.mMode
  icache.io.fromMMU := io.fromMMU
  icache.io.bpuOverride.valid := bpuOverride
  icache.io.bpuOverride.bits := 0.U.asTypeOf(chiselTypeOf(icache.io.bpuOverride.bits))
  icache.io.lookup.valid := ftqLookup.valid
  icache.io.lookup.bits := ftqLookup.bits
  ftqLookup.ready := icache.io.lookup.ready
  icache.io.fetch.valid := io.fromFTQ.fetch.valid
  icache.io.fetch.bits := io.fromFTQ.fetch.bits
  io.fromFTQ.fetch.ready := icache.io.fetch.ready
  // 预译码还没接。先把取回的指令排空，避免 f2 卡住。
  icache.io.fetchResp.ready := true.B
  icache.io.memReq.ready := false.B
  icache.io.memResp.valid := false.B
  icache.io.memResp.bits := 0.U.asTypeOf(new ICacheMemResp)

  io.toMMU := icache.io.toMMU
  // 预译码还没接回 FTQ。接口已经是 Valid(IFUToFTQIO)，先把上报关掉。
  io.toFTQ.valid := false.B
  io.toFTQ.bits := 0.U.asTypeOf(new IFUToFTQIO)
}
