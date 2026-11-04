package softbackendcheck

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import ansercore.backend.softBackend.SoftBackend
import ansercore.frontend.FrontendExcept
import ansercore.frontend.HasFrontendParameters

// 扁平端口，测试只例化 SoftBackend，不例化前端。
class SoftBackendProbe extends Module with HasFrontendParameters {
  val io = IO(new Bundle {
    val instValid = Input(Vec(DecodeWidth, Bool()))
    val inst = Input(Vec(DecodeWidth, UInt(32.W)))
    val ftqValue = Input(Vec(DecodeWidth, UInt(FTQSizeWidth.W)))
    val ftqFlag = Input(Vec(DecodeWidth, Bool()))
    val endOffset = Input(Vec(DecodeWidth, UInt(FetchBlockOffsetWidth.W)))
    val predPc = Input(Vec(DecodeWidth, UInt(pcWidth.W)))
    val predNext = Input(Vec(DecodeWidth, UInt(pcWidth.W)))
    val predTaken = Input(Vec(DecodeWidth, Bool()))
    val predTarget = Input(Vec(DecodeWidth, UInt(pcWidth.W)))
    val ready = Output(Vec(DecodeWidth, Bool()))
    val queryValue = Output(UInt(FTQSizeWidth.W))
    val queryFlag = Output(Bool())
    val queryOffset = Output(UInt(FetchBlockOffsetWidth.W))
    val commitValid = Output(Bool())
    val commitValue = Output(UInt(FTQSizeWidth.W))
    val commitFlag = Output(Bool())
    val commitOffset = Output(UInt(FetchBlockOffsetWidth.W))
    val redirectValid = Output(Bool())
    val redirectValue = Output(UInt(FTQSizeWidth.W))
    val redirectFlag = Output(Bool())
    val redirectOffset = Output(UInt(FetchBlockOffsetWidth.W))
    val redirectTarget = Output(UInt(pcWidth.W))
    val redirectTaken = Output(Bool())
    val redirectMispredict = Output(Bool())
    val redirectCond = Output(Bool())
    val priv = Output(UInt(2.W))
    val satp = Output(UInt(64.W))
    val mprv = Output(Bool())
    val mpp = Output(UInt(2.W))
    val sum = Output(Bool())
    val mxr = Output(Bool())
  })

  val backend = Module(new SoftBackend)
  backend.io.resetPc := "h80000000".U
  val instPort = backend.io.fromFrontend.fromIFU.inst
  for (idx <- 0 until DecodeWidth) {
    val pkt = instPort(idx).bits
    instPort(idx).valid := io.instValid(idx)
    pkt.inst := io.inst(idx)
    pkt.isLastInFetchBlock := false.B
    pkt.ftqPtr.value := io.ftqValue(idx)
    pkt.ftqPtr.flag := io.ftqFlag(idx)
    pkt.endOffset := io.endOffset(idx)
    pkt.except := FrontendExcept.none
    io.ready(idx) := instPort(idx).ready
  }

  val query = backend.io.toFrontend.predQuery
  val hit = Wire(Vec(DecodeWidth, Bool()))
  for (idx <- 0 until DecodeWidth) {
    hit(idx) := io.instValid(idx) &&
      query.ptr.value === io.ftqValue(idx) &&
      query.ptr.flag === io.ftqFlag(idx) &&
      query.offset === io.endOffset(idx)
  }
  val predResp = backend.io.fromFrontend.fromFTQ.predQueryResp
  predResp.pc := Mux1H(hit.asUInt, io.predPc)
  predResp.nextPC := Mux1H(hit.asUInt, io.predNext)
  predResp.taken := Mux1H(hit.asUInt, io.predTaken)
  predResp.target := Mux1H(hit.asUInt, io.predTarget)

  io.queryValue := query.ptr.value
  io.queryFlag := query.ptr.flag
  io.queryOffset := query.offset
  val commit = backend.io.toFrontend.commit
  io.commitValid := commit.valid
  io.commitValue := commit.bits.ptr.value
  io.commitFlag := commit.bits.ptr.flag
  io.commitOffset := commit.bits.offset
  val redirect = backend.io.toFrontend.redirect
  io.redirectValid := redirect.valid
  io.redirectValue := redirect.bits.redirectPosition.ptr.value
  io.redirectFlag := redirect.bits.redirectPosition.ptr.flag
  io.redirectOffset := redirect.bits.redirectPosition.offset
  io.redirectTarget := redirect.bits.target
  io.redirectTaken := redirect.bits.taken
  io.redirectMispredict := redirect.bits.mispredict
  io.redirectCond := redirect.bits.cfiAttribute.isConditional
  val mmuCtr = backend.io.mmuCtr
  io.priv := mmuCtr.privMode.asUInt
  io.satp := mmuCtr.satp
  io.mprv := mmuCtr.mstatusMPRV
  io.mpp := mmuCtr.mstatusMPP.asUInt
  io.sum := mmuCtr.mstatusSUM
  io.mxr := mmuCtr.mstatusMXR
}

object EmitSoftBackend extends App {
  val out = sys.env.getOrElse("SOFTBACKEND_OUT", "sv")
  val firtool = sys.env.getOrElse(
    "FIRTOOL",
    "/home/yang/.cache/llvm-firtool/1.160.0/bin/firtool",
  )
  ChiselStage.emitSystemVerilogFile(
    new SoftBackendProbe,
    Array("--target-dir", out, "--firtool-binary-path", firtool),
    Array("-disable-all-randomization", "-strip-debug-info"),
  )
  println("EMIT_OK " + out)
}
