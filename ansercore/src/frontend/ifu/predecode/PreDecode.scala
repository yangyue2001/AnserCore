package ansercore.frontend.ifu.predecode

import chisel3._
import chisel3.util._
import utils.Mux1H
import ansercore.frontend.ifu.IFUBundle
import ansercore.frontend.ifu.IFUModule
import ansercore.frontend.FrontendHelper
import ansercore.frontend.CFITypte
import ansercore.frontend.RASAction

class PreDecode extends IFUModule with PreDecodeHelper with FrontendHelper {
  class PreDecodeIO extends IFUBundle {
    val req            = Input(new PreDecodeReq)
    val flush          = Input(Bool())
    val fire           = Input(Bool())
    val predecodeInfo  = Output(new PredecodeInfo)
    val instEnd        = Output(Vec(FetchBlockNumSlots, Bool()))
  }
  val io = IO(new PreDecodeIO)

  //-------------------------alias-----------------------------//
  val instReq = io.req
  val inst    = instReq.inst
  val fire    = io.fire
  val flush   = io.flush

  //---------------------------decode--------------------------//
  // 上一个 fetch block 若停在 32 位指令的低半字，这里和 slot 0 拼回去。
  val lastIsHalfRviReg = RegInit(false.B)
  val lastHalfRviReg   = Reg(UInt(16.W))

  val mayBeRvc = Wire(Vec(FetchBlockNumSlots, Bool()))
  val isRvc    = Wire(Vec(FetchBlockNumSlots, Bool()))
  val instEnd  = Wire(Vec(FetchBlockNumSlots, Bool()))
  val rawInst  = Wire(Vec(FetchBlockNumSlots, UInt(32.W)))

  instEnd(0) := lastIsHalfRviReg || mayBeRvc(0)
  isRvc(0)   := mayBeRvc(0) && !lastIsHalfRviReg
  rawInst(0) := Mux(
    lastIsHalfRviReg,
    Cat(inst(0), lastHalfRviReg),
    Cat(0.U(16.W), inst(0)),
  )

  for (i <- 1 until FetchBlockNumSlots) {
    val instStart = instEnd(i - 1)
    instEnd(i) := !instStart || mayBeRvc(i)
    isRvc(i)   := mayBeRvc(i) && instStart
    rawInst(i) := Mux(
      instStart,
      Cat(0.U(16.W), inst(i)),
      Cat(inst(i), inst(i - 1)),
    )
  }

  for (i <- 0 until FetchBlockNumSlots) {
    mayBeRvc(i) := isRVC(inst(i))
  }

  val cfiAttribute = rawInst.zip(isRvc).map { case (raw, rvc) =>
    getCFIType(raw, rvc)
  }

  //-----------------------check---------------------------------------//
  // predEndOffset 由取指保证，一定小于块内槽数，并且一定在本页内。
  // not-taken 可以停在 32 位指令的低半字，下一块再拼完。
  // taken 必须停在一条完整的控制流指令上，否则终点无效，继续看到本页末尾。
  val (crossPage, pageEndOffset) = getPageEndSlot(instReq.startPC)
  val predEndOffsetOH            = UIntToOH(instReq.predEndOffset, FetchBlockNumSlots)
  val predEndIsInst              = Mux1H(predEndOffsetOH, instEnd)
  val predEndCfi                 = Mux1H(predEndOffsetOH, cfiAttribute)
  val predEndIsCfi               = predEndCfi.branch || predEndCfi.jal || predEndCfi.jalr
  val validPredEnd               = !instReq.predTaken || (predEndIsInst && predEndIsCfi)

  val uncondTaken = VecInit(cfiAttribute.zip(instEnd).map { case (cfi, end) =>
    end && (cfi.jal || cfi.jalr)
  })

  val fallThroughOffset = Mux(crossPage, pageEndOffset, (FetchBlockNumSlots - 1).U)
  val maxOffset         = Mux(validPredEnd, instReq.predEndOffset, fallThroughOffset)
  val inRange           = VecInit(uncondTaken.zipWithIndex.map { case (taken, idx) =>
    taken && idx.U(FetchBlockOffsetWidth.W) <= maxOffset
  })
  val hasUncond         = inRange.asUInt.orR
  val uncondOffset      = OHToUInt(PriorityEncoderOH(inRange.asUInt))
  val finalOffset       = Mux(hasUncond, uncondOffset, maxOffset)
  val finalOffsetOH     = UIntToOH(finalOffset, FetchBlockNumSlots)
  val finalIsInst       = Mux1H(finalOffsetOH, instEnd)
  val finalIsRvc        = Mux1H(finalOffsetOH, isRvc)
  val finalInst         = Mux1H(finalOffsetOH, rawInst)
  val finalCfi          = Mux1H(finalOffsetOH, cfiAttribute)
  val finalTaken        = hasUncond || (validPredEnd && instReq.predTaken)

  val finalPC      = (instReq.startPC + finalOffset)(pcWidth - 1, 0)
  val finalStartPC = Mux(finalIsRvc, finalPC, (finalPC - 1.U)(pcWidth - 1, 0))
  val jalTarget    = (finalStartPC + getJalOffset(finalInst, finalIsRvc))(pcWidth - 1, 0)
  val brTarget     = (finalStartPC + getBrOffset(finalInst, finalIsRvc))(pcWidth - 1, 0)
  // 预测命中的 jalr 直接沿用预测目标。更早发现的 jalr 没有自己的 RAS 顶，只能借用 req.rasTopAddr。
  val predictedJalr = validPredEnd && instReq.predTaken && finalCfi.jalr && finalOffset === instReq.predEndOffset
  val jalrTarget    = Mux(predictedJalr, instReq.predTarget, instReq.rasTopAddr)
  val takenTarget   = Mux(finalCfi.jal, jalTarget, Mux(finalCfi.branch, brTarget, jalrTarget))
  val sequentialTarget = (instReq.startPC + finalOffset + 1.U)(pcWidth - 1, 0)

  val offsetMismatch = finalOffset =/= instReq.predEndOffset
  val takenMismatch  = finalTaken =/= instReq.predTaken
  val checkTarget    = finalTaken && finalOffset === instReq.predEndOffset && (finalCfi.jal || finalCfi.branch)
  val targetMismatch = checkTarget && Mux(finalCfi.branch, brTarget, jalTarget) =/= instReq.predTarget
  val redirectValid  = !validPredEnd || offsetMismatch || takenMismatch || targetMismatch

  val predecode = Wire(new PredecodeInfo)
  predecode.redirectValid := redirectValid
  predecode.endOffset     := finalOffset
  predecode.target        := takenTarget
  predecode.taken         := finalTaken
  predecode.cfiAttribute.cfiType := Mux(
    !finalIsInst,
    CFITypte.none,
    Mux(
      finalCfi.branch,
      CFITypte.conditional,
      Mux(finalCfi.jal, CFITypte.direct, Mux(finalCfi.jalr, CFITypte.indirect, CFITypte.none)),
    ),
  )
  predecode.cfiAttribute.rasAction := Mux(
    !finalIsInst,
    RASAction.none,
    Mux(
      finalCfi.call && finalCfi.ret,
      RASAction.pushAndPop,
      Mux(finalCfi.call, RASAction.push, Mux(finalCfi.ret, RASAction.pop, RASAction.none)),
    ),
  )
  predecode.isRVC := VecInit(isRvc.zip(instEnd).map { case (rvc, end) =>
    rvc && end
  })
  io.predecodeInfo := predecode
  io.instEnd       := instEnd

  when(flush) {
    lastIsHalfRviReg := false.B
  }.elsewhen(fire) {
    lastIsHalfRviReg := !finalIsInst
    lastHalfRviReg   := Mux1H(finalOffsetOH, inst)
  }
}
