package ansercore.frontend.bpu

import chisel3._
import chisel3.util._

import ansercore.frontend.bpu.ftb.FTBTrain
import ansercore.frontend.bpu.ftb.FTB
import ansercore.frontend.bpu.tage.TAGETrain
import ansercore.frontend.bpu.tage.TAGE
import ansercore.frontend.bpu.tage.TAGEAllFoldedHistories
import ansercore.frontend.bpu.ittage.ITTAGETrain
import ansercore.frontend.bpu.ittage.ITTAGE
import ansercore.frontend.bpu.ittage.ITTAGEAllFoldedHistories
import ansercore.frontend.bpu.uftb.UFTB
import ansercore.frontend.bpu.uftb.UFTBTrainReq
import ansercore.frontend.bpu.ras.RAS
import ansercore.frontend.bpu.phr.PHR
import ansercore.frontend.FrontendHelper
import ansercore.frontend.FTQToBPUIO
import ansercore.frontend.BPUToFTQIO
import ansercore.frontend.CFIAttribute

class BPU extends BPUModule with FrontendHelper {
  class BPUIO extends BPUBundle {
    val ctrl = Input(new BPUCtrl)
    val resetVector = Input(UInt(pcWidth.W))
    val fromFTQ = Flipped(new FTQToBPUIO)
    val toFTQ = new BPUToFTQIO
  }
  val io = IO(new BPUIO)

//-----------------------alias----------------------------------------//
  val redirectValid = io.fromFTQ.redirect.valid
  val redirect = io.fromFTQ.redirect.bits
  val resetPC = io.resetVector

  // 后级结果要回到 s0 选 PC，先声明，避免前向引用。
  val s1Pred = Wire(Bool())
  val s1Target = Wire(UInt(pcWidth.W))
  val s2Override = Wire(Bool())
  val s2Target = Wire(UInt(pcWidth.W))
  val s3Override = Wire(Bool())
  val s3Target = Wire(UInt(pcWidth.W))

  val ftb = Module(new FTB)
  val tage = Module(new TAGE)
  val ittage = Module(new ITTAGE)
  val uftb = Module(new UFTB)
  val ras = Module(new RAS)
  val phr = Module(new PHR)

  // TAGE / FTB / ITTAGE 的提交训练这次不接。UFTB 的填槽也先关掉。
  ftb.io.ftbTrain.valid := false.B
  ftb.io.ftbTrain.bits := 0.U.asTypeOf(new FTBTrain)
  tage.io.tageTrain.valid := false.B
  tage.io.tageTrain.bits := 0.U.asTypeOf(new TAGETrain)
  ittage.io.ittageTrain.valid := false.B
  ittage.io.ittageTrain.bits := 0.U.asTypeOf(new ITTAGETrain)
  uftb.io.train.valid := false.B
  uftb.io.train.bits := 0.U.asTypeOf(new UFTBTrainReq)

  io.fromFTQ.train.ready := true.B

//----------------------s0--------------------------------------------//
  // 三个表都复位完成后锁存，之后才允许发预测请求。
  val resetDownNext = Wire(Bool())
  val predictorsReset = ftb.io.resetDown && tage.io.resetDown && ittage.io.resetDown
  val resetDown = RegEnable(predictorsReset, false.B, !resetDownNext)
  resetDownNext := resetDown

  val s0Valid = resetDown
  val s1Ready = Wire(Bool())
  val s0Go = s1Ready && tage.io.predictReq.ready && ftb.io.predictReq.ready
  // 重定向当拍如果 FTB 或 TAGE 还不能收请求，s0 不前进，s1PC 仍会记下重定向目标。
  val s0ValidOut = s0Valid && s0Go

  val s1PC = Wire(UInt(pcWidth.W))
  val s0PC = MuxCase(s1PC, Seq(
    redirectValid -> redirect.target,
    s3Override -> s3Target,
    s2Override -> s2Target,
    s1Pred -> s1Target,
  ))

  ftb.io.predictReq.valid := s0Valid && s1Ready && tage.io.predictReq.ready
  ftb.io.predictReq.bits.pc := s0PC
  tage.io.predictReq.valid := s0Valid && s1Ready && ftb.io.predictReq.ready
  tage.io.predictReq.bits.pc := s0PC
  tage.io.predictReq.bits.predPHR := 0.U.asTypeOf(new TAGEAllFoldedHistories)

//----------------------s1--------------------------------------------//
  val s1Valid = RegEnable(s0ValidOut, false.B, s1Ready)
  val s1Flush = redirectValid || s2Override || s3Override

  s1PC := RegEnable(s0PC, resetPC, s1Ready || redirectValid)

  uftb.io.s1PC := s1PC
  val uftbPrediction = uftb.io.prediction
  val s1NeedITTAGE = uftbPrediction.hit && uftbPrediction.cfiAttribute.needITTAGE
  val s1ITTAGEReady = !s1NeedITTAGE || ittage.io.predictReq.ready
  val s1Go = s1ITTAGEReady && io.toFTQ.s1Fetch.ready
  s1Ready := !s1Valid || s1Go || s1Flush

  val s1ValidOut = s1Valid && s1Go && !s1Flush
  val s1Fire = s1ValidOut

  val (sequenceTarget, sequenceOffset) = sequentialFetch(s1PC)
  val s1Prediction = Wire(new BPUPrediction)
  s1Prediction.target := Mux(uftbPrediction.hit, uftbPrediction.target, sequenceTarget)
  s1Prediction.endOffset := Mux(uftbPrediction.hit, uftbPrediction.cfiOffset, sequenceOffset)
  s1Prediction.taken := uftbPrediction.hit && uftbPrediction.taken
  s1Prediction.endCFIAttribute := Mux(
    uftbPrediction.hit,
    uftbPrediction.cfiAttribute,
    0.U.asTypeOf(new CFIAttribute),
  )
  s1Pred := s1Fire
  s1Target := s1Prediction.target

  val s1FTQPtr = io.fromFTQ.bpuPtr

  ftb.io.s1Fire := s1Fire
  tage.io.s1Fire := s1Fire
  ittage.io.predictReq.valid := s1Valid && s1NeedITTAGE && !s1Flush && io.toFTQ.s1Fetch.ready
  ittage.io.predictReq.bits.pc := s1PC
  ittage.io.predictReq.bits.predPHR := 0.U.asTypeOf(new ITTAGEAllFoldedHistories)
  io.toFTQ.s1Fetch.valid := s1ValidOut
  io.toFTQ.s1Fetch.bits := s1PC

//----------------------s2--------------------------------------------//
  val s2Valid = RegNext(s1ValidOut, false.B)
  val s2Flush = redirectValid || s3Override
  val s2ValidOut = s2Valid && !s2Flush
  val s2Fire = s2ValidOut

  val s2PC = RegEnable(s1PC, s1Fire)
  val s2S1Prediction = RegEnable(s1Prediction, s1Fire)
  val s2FTQPtr = RegEnable(s1FTQPtr, s1Fire)
  ftb.io.s2PC := s2PC

  val ftbPrediction = ftb.io.prediction
  val tagePrediction = tage.io.prediction
  tage.io.s2PC := s2PC
  tage.io.s2PHR := 0.U.asTypeOf(new TAGEAllFoldedHistories)
  tage.io.s2Offset := VecInit(Seq(ftbPrediction.branchOffset, ftbPrediction.tailOffset))

  val tailExists = !ftbPrediction.tailCFIAttribute.isNone
  val brTaken = ftbPrediction.branchHit && tagePrediction(0)
  val tailTaken = tailExists && (
    ftbPrediction.tailCFIAttribute.isUnconditional || tagePrediction(1)
  )
  val ftbHit = ftbPrediction.branchHit || tailExists

  val branchAttribute = Wire(new CFIAttribute)
  branchAttribute.cfiType := 1.U
  branchAttribute.rasAction := 0.U

  val s2FTBPrediction = Wire(new BPUPrediction)
  s2FTBPrediction.target := Mux(
    brTaken,
    ftbPrediction.branchTarget,
    Mux(tailTaken, ftbPrediction.tailTarget, ftbPrediction.fallThroughTarget),
  )
  s2FTBPrediction.endOffset := Mux(
    brTaken,
    ftbPrediction.branchOffset,
    ftbPrediction.fallThroughOffset,
  )
  s2FTBPrediction.endCFIAttribute := Mux(brTaken, branchAttribute, ftbPrediction.tailCFIAttribute)
  s2FTBPrediction.taken := brTaken || tailTaken

  val s2Prediction = Wire(new BPUPrediction)
  s2Prediction := Mux(ftbHit, s2FTBPrediction, s2S1Prediction)
  s2Override := s2Fire && ftbHit && (
    s2Prediction.target =/= s2S1Prediction.target ||
      s2Prediction.endOffset =/= s2S1Prediction.endOffset
  )
  s2Target := s2Prediction.target

  val ftbMeta = ftb.io.meta
  val tageMeta = tage.io.meta

//----------------------s3--------------------------------------------//
  val s3Valid = RegNext(s2ValidOut, false.B)
  val s3Flush = redirectValid
  val s3ValidOut = s3Valid && !s3Flush

  val s3PC = RegEnable(s2PC, s2Fire)
  val s3S2Prediction = RegEnable(s2Prediction, s2Fire)
  val s3FTQPtr = RegEnable(s2FTQPtr, s2Fire)
  val s3FTBMeta = RegEnable(ftbMeta, s2Fire)
  val s3TAGEMeta = RegEnable(tageMeta, s2Fire)

  ittage.io.s2Fire := s2Fire
  ittage.io.s3PC := s3PC
  ittage.io.s3PHR := 0.U.asTypeOf(new ITTAGEAllFoldedHistories)

  val s3CFIAttribute = s3S2Prediction.endCFIAttribute
  val s3UseRAS = s3CFIAttribute.isIndirect && s3CFIAttribute.hasPop
  val s3UseITTAGE = s3CFIAttribute.needITTAGE && ittage.io.prediction.taken
  val rasTarget = ras.io.prediction
  val ittageTarget = ittage.io.prediction.target

  s3Target := Mux(s3UseRAS, rasTarget, Mux(s3UseITTAGE, ittageTarget, s3S2Prediction.target))
  s3Override := s3ValidOut && (s3Target =/= s3S2Prediction.target)

  val s3Prediction = Wire(new BPUPrediction)
  s3Prediction.target := s3Target
  s3Prediction.endOffset := s3S2Prediction.endOffset
  s3Prediction.endCFIAttribute := s3CFIAttribute
  s3Prediction.taken := s3S2Prediction.taken || s3UseRAS || s3UseITTAGE

  val callReturnPC = (s3PC + s3Prediction.endOffset + 1.U)(pcWidth - 1, 0)
  ras.io.specUpate.valid := s3ValidOut && (s3CFIAttribute.hasPush || s3CFIAttribute.hasPop)
  ras.io.specUpate.bits.rasAction := s3CFIAttribute.rasAction
  ras.io.specUpate.bits.pushPC := callReturnPC
  val redirectCFIPC = (redirect.startPC + redirect.endOffset)(pcWidth - 1, 0)
  ras.io.recover.valid := redirectValid
  ras.io.recover.bits.meta := redirect.meta.ras
  ras.io.recover.bits.update.rasAction := redirect.cfiAttribute.rasAction.asUInt
  ras.io.recover.bits.update.pushPC := redirectCFIPC

//----------------------output----------------------------------------//
  val s3Result = Wire(new BPUS3Result)
  s3Result.meta.ftb := s3FTBMeta
  s3Result.meta.tage := s3TAGEMeta
  s3Result.meta.ittage := ittage.io.meta
  s3Result.meta.phr := phr.io.meta
  s3Result.meta.ras := ras.io.meta
  s3Result.prediction := s3Prediction
  s3Result.s3FTQPtr := s3FTQPtr
  s3Result.s3Override := s3Override

  io.toFTQ.s3Result.valid := s3ValidOut
  io.toFTQ.s3Result.bits := s3Result

  phr.io.s1Fire := s1Fire
  phr.io.s2Fire := s2Fire
  phr.io.s1Update.valid := s1Fire
  phr.io.s1Update.bits.cfiPC := s1PC
  phr.io.s1Update.bits.target := s1Target
  phr.io.s2Override.valid := s2Override
  phr.io.s2Override.bits.taken := s2Prediction.taken
  phr.io.s2Override.bits.update.cfiPC := s2PC
  phr.io.s2Override.bits.update.target := s2Target
  phr.io.s3Override.valid := s3Override
  phr.io.s3Override.bits.taken := s3Prediction.taken
  phr.io.s3Override.bits.update.cfiPC := s3PC
  phr.io.s3Override.bits.update.target := s3Target
  phr.io.redirect.valid := redirectValid
  phr.io.redirect.bits.restore := redirect.meta.phr
  phr.io.redirect.bits.update.taken := redirect.taken
  phr.io.redirect.bits.update.update.cfiPC := redirectCFIPC
  phr.io.redirect.bits.update.update.target := redirect.target
  phr.io.trainMeta := io.fromFTQ.train.bits.meta.phr
}
