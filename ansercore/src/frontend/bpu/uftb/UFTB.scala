package ansercore.frontend.bpu.uftb

import chisel3._
import chisel3.util._
import ansercore.frontend.bpu.BPUParameters
import utils.SaturateCounter
import ansercore.frontend.bpu.ftb.FTB
import ansercore.frontend.bpu.ftb.FTBEntry
import ansercore.frontend.CFIAttribute

class UFTB extends UFTBModule with UFTBHelper {
  class UFTBIO extends UFTBBundle {
    val s1PC = Input(UInt(pcWidth.W))
    val prediction = Output(new UFTBPrediction)
    val meta = Output(new UFTBMeta)
    val train      = Flipped(Valid(new UFTBTrainReq))
  }
  val io = IO(new UFTBIO)
  val predictPC = io.s1PC
  val trainValid = io.train.valid
  val train = io.train.bits

//------------------------predirect-------------------------//
  val uftb = RegInit(VecInit(Seq.fill(NumWays)(0.U.asTypeOf(new FTBEntry))))
  val ctr = RegInit(
  VecInit.fill(NumWays)(
    VecInit.fill(NumBr)(
      SaturateCounter(CtrWidth, 0)
    )
  )
)
  val pcTag = getTag(predictPC)
  val wayHit = uftb.map{ entry =>
    entry.tag === pcTag
  }

  val hitBrSlot = Mux1H(wayHit, uftb.map(_.brSlot))
  val hitTailSlot = Mux1H(wayHit, uftb.map(_.tailSlot))
  val hitCtr = Mux1H(wayHit, ctr)
  val branchCtr = hitCtr(0)
  val tailCtr = hitCtr(1)
  val brHit = hitBrSlot.valid
  val tailHit = !hitTailSlot.cfiAttribute.isNone

  val (sequentialTarget, sequentialOffset) = sequentialFetch(s1PC)
  val fallThroughTarget = prediction.fallThroughTarget := Mux(
    tailHit,
    (s1PC + tailSlot.offset + 1.U)(pcWidth - 1, 0),
    sequentialTarget,
  )

  val branchTarget = getTarget(predictPC, hitBrSlot.tarStat, hitBrSlot.lower)
  val tailCondTarget = getTarget(predictPC, hitTailSlot.tarStat, hitTailSlot.lower(11, 0))
  val tailDirectTarget = getTarget(predictPC, hitTailSlot.tarStat, hitTailSlot.lower)
  val tailTarget = Mux(hitTailSlot.cfiAttribute.isConditional, tailCondTarget, tailDirectTarget)

  val branchSlotTaken = brHit && branchCtr.isPositive
  val tailSlotTaken = hitTailSlot.cfiAttribute.isUnconditional || hitTailSlot.cfiAttribute.isConditional && tailCtr.isPositive

  val taken = branchSlotTaken || tailSlotTaken
  val cfiOffset = Mux(branchSlotTaken, hitBrSlot.offset, Mux(tailHit, hitTailSlot.offset, sequentialOffset))
  val target = Mux(branchSlotTaken, branchTarget, Mux(tailSlotTaken, tailTarget, fallThroughTarget))

/*
//------------------train--------------------//
//train0
  val uftbHit = train.uftbMeta.brSlot.valid || train.uftbMeta.tailSlot.valid
  val alias = train.uftbMeta.isEqual(train.ftbMeta)

  val allocate = trainValid && (!uftbHit || alias) && train.s3Override

  val branchCtrUpdate = trainValid && !alias && train.uftbMeta.brSlot.valid
  val tailCtrUpdate = trainValid && !alias && train.uftbMeta.tailSlot.valid && !train.uftbMeta.tailSlot.share
  val update = Cat(branchCtrUpdate, tailCtrUpdate)
  val trainHitWay = uftb.map(_.tag === getTag(train.trainPC))
  val trainHitCtr = Mux1H(trainHitWay, ctr)
//train1
  val t1Update = RegEnable(update, trainValid)
  val t1Allocate = RegEnable(allocate, trainValid)
  val replaceWay = Wire(Vec(NumWays, Bool()))
  val t1HitWay = RegEnable(VecInit(trainHitWay), trainValid)
  val t1HitCtr = RegEnable(trainHitCtr, trainValid)

  ctr.zipWithIndex.foreach{case(ctr, wayIdx) =>

  }
  */
  //Connect
  val prediction = Wire(new UFTBPrediction)
  prediction.taken := taken
  prediction.cfiOffset := cfiOffset
  prediction.cfiAttribute := 0.U.asTypeOf(new CFIAttribute)
  prediction.target := target
  prediction.hit := brHit || tailHit

  //Output
  io.prediction := prediction
}