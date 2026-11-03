package ansercore.frontend.ftq

import chisel3._
import chisel3.util._
import utils.HasCircularQueuePtrHelper
import ansercore.frontend.FTQToBPUIO
import ansercore.frontend.BPUToFTQIO
import ansercore.frontend.IFUToFTQIO
import ansercore.frontend.FTQToIFUIO
import ansercore.frontend.BackendToFTQIO
import ansercore.frontend.FTQToBackendIO
import ansercore.frontend.CFIAttribute
import ansercore.frontend.bpu.BPUTrainMeta

class FTQ extends FTQModule with HasCircularQueuePtrHelper {
  class FTQIO extends FTQBundle {
//ftq <=> bpu
    val fromBPU = Flipped(new BPUToFTQIO)
    val toBPU = new FTQToBPUIO
// ftq <=> ifu
    val fromIFU = Flipped(Valid(new IFUToFTQIO))
    val toIFU = new FTQToIFUIO
// ftq <=> backend
    val fromBackend = Flipped(new BackendToFTQIO)
    val toBackend = new FTQToBackendIO
  }

  val io = IO(new FTQIO)
//---------------alias----------------------------//
  val fromBPU = io.fromBPU
  val fromIFU = io.fromIFU
  val fromBackend = io.fromBackend
  //BPU s1
  val s1FetchValid = fromBPU.s1Fetch.valid
  val s1FetchPC = fromBPU.s1Fetch.bits
  val s1FetchFire = fromBPU.s1Fetch.fire
  //BPU s3
  val s3ResultValid = fromBPU.s3Result.valid
  val s3Result = fromBPU.s3Result.bits
  val s3Prediction = s3Result.prediction
  val s3Meta = s3Result.meta
  val s3FTQPtr = s3Result.s3FTQPtr
  val s3Override = s3Result.s3Override
  //backend
  val backendCommitValid = fromBackend.commit.valid
  val backendCommit = fromBackend.commit.bits
  val backendRedirectValid = fromBackend.redirect.valid
  val backendRedirect = fromBackend.redirect.bits
  val backendPtr = backendRedirect.redirectPosition.ptr
  //predecode
  val predecodeValid = fromIFU.valid
  val predecode = fromIFU.bits.preDecodeInfo
  val predecodePtr = fromIFU.bits.ftqPtr
  val predecodeRedirect = predecodeValid && predecode.redirectValid

  //lookup
  val lookupFire = io.toIFU.lookUp.fire
  //fetch
  val fetchFire = io.toIFU.fetch.fire
  //commit
  val popFire = io.toBPU.train.fire
//---------------FIFO----------------------------//
  val pushPtr = RegInit(0.U.asTypeOf(new FTQPtr))
  val lookupPtr = RegInit(0.U.asTypeOf(new FTQPtr))
  val fetchPtr = RegInit(0.U.asTypeOf(new FTQPtr))
  val popPtr = RegInit(0.U.asTypeOf(new FTQPtr))

  // 后端重定向压过 predecode。两者都没有时，s3 override 冲掉自己和后面的块。
  val bpuOverride = s3ResultValid && s3Override
  val redirectFlush = backendRedirectValid || predecodeRedirect
  val redirectPtr = Mux(backendRedirectValid, backendPtr, predecodePtr)
  val ftqFlush = bpuOverride || redirectFlush
  val ftqEntries = RegInit(VecInit.fill(FTQSize)(0.U.asTypeOf(new FTQEntry)))


  when(redirectFlush){
    pushPtr := redirectPtr + 1.U
  }.elsewhen(bpuOverride){
    pushPtr := s3FTQPtr
  }.elsewhen(s1FetchFire){
    pushPtr := pushPtr + 1.U
  }

  when(redirectFlush){
    lookupPtr := redirectPtr + 1.U
  }.elsewhen(bpuOverride){
    lookupPtr := s3FTQPtr
  }.elsewhen(lookupFire){
    lookupPtr := lookupPtr + 1.U
  }

  when(redirectFlush){
    fetchPtr := redirectPtr + 1.U
  }.elsewhen(bpuOverride){
    fetchPtr := s3FTQPtr
  }.elsewhen(fetchFire){
    fetchPtr := fetchPtr + 1.U
  }

  when(popFire){
    popPtr := popPtr + 1.U
  }

  val full = isFull(pushPtr, popPtr)
  val empty = isEmpty(pushPtr, popPtr)
  // redirect 留下自己，冲掉 (redirectPtr, pushPtr)。
  // s3 override 连自己一起冲掉，范围是 [s3FTQPtr, pushPtr)。
  val flushStart = Mux(redirectFlush, redirectPtr + 1.U, s3FTQPtr)
  val flushMask = circularRangMask(flushStart, pushPtr)

  val backCommitEntry = ftqEntries(backendCommit.ptr.value)
  val commitHit = backCommitEntry.fetchBlock.endOffset === backendCommit.offset

  val commitRange = Mux(
    commitHit,
    circularRangMask(popPtr, backendCommit.ptr) | UIntToOH(backendCommit.ptr.value),
    circularRangMask(popPtr, backendCommit.ptr),
  )

  val fetchParallel = lookupPtr === fetchPtr

  def nextPC(start: UInt, offset: UInt): UInt = {
    (start + offset + 1.U)(pcWidth - 1, 0)
  }

//状态机
  ftqEntries.zipWithIndex.foreach{case(entry, idx) =>
    val state = entry.state
    val nextState = Wire(FTQState())
    val commitEn = backendCommitValid && commitRange(idx)
    val entryFlushed = ftqFlush && flushMask(idx)
    val lookupEn = idx.U === lookupPtr.value && lookupFire
    val fetchEn = idx.U === fetchPtr.value && fetchFire
    val popEn = idx.U === popPtr.value && popFire
    val pushEn = s1FetchFire && idx.U === pushPtr.value

    val s3En = s3ResultValid && idx.U === s3FTQPtr.value && !entryFlushed
    val predecodeEn = predecodeValid && idx.U === predecodePtr.value && !entryFlushed
    val backendEn = backendRedirectValid && idx.U === backendPtr.value && !entryFlushed

    assert(
      PopCount(Seq(s3En, predecodeEn, backendEn)) <= 1.U,
      s"different stages cannot be active in the same cycle"
    )
    switch(state){
      is(FTQState.lookup){
        when(lookupEn){
          when(fetchParallel){
            nextState := FTQState.waitCommit
          }.otherwise{
            nextState := FTQState.fetch
          }
        }.otherwise{
          nextState := FTQState.lookup
        }
      }

      is(FTQState.fetch){
        when(fetchEn){
          nextState := FTQState.waitCommit
        }.otherwise{
          nextState := FTQState.fetch
        }
      }

      is(FTQState.waitCommit){
        when(commitEn){
          nextState := FTQState.commit
        }.otherwise{
          nextState := FTQState.waitCommit
        }
      }

      is(FTQState.commit){
        when(popEn){
          nextState := FTQState.lookup
        }.otherwise{
          nextState := FTQState.commit
        }
      }
    }

    when(entryFlushed){
      state := FTQState.lookup
    }.otherwise{
      state := nextState
    }

    val block = entry.fetchBlock
    val oldTaken = block.taken
    val oldTarget = block.target
    val oldEnd = block.endOffset


    val predecodeDirWrong = oldTaken =/= predecode.taken || oldEnd =/= predecode.endOffset
    val predecodeTargetWrong = predecode.taken && oldTarget =/= predecode.target
    val backendDirWrong = oldTaken =/= backendRedirect.taken || oldEnd =/= backendRedirect.redirectPosition.offset
    val backendTargetWrong = backendRedirect.taken && oldTarget =/= backendRedirect.target

    when(pushEn){
      block.startAddr := s1FetchPC
    }

    when(backendEn){
      block.endOffset := backendRedirect.redirectPosition.offset
      block.target := backendRedirect.target
      block.taken := backendRedirect.taken
      block.cfiAttribute := backendRedirect.cfiAttribute
    }.elsewhen(predecodeEn){
      block.endOffset := predecode.endOffset
      block.target := predecode.target
      block.taken := predecode.taken
      block.cfiAttribute := predecode.cfiAttribute
    }.elsewhen(s3En){
      block.endOffset := s3Prediction.endOffset
      block.target := s3Prediction.target
      block.taken := s3Prediction.taken
      block.cfiAttribute := s3Prediction.endCFIAttribute
    }.elsewhen(pushEn){
      block.endOffset := (FetchBlockNumSlots - 1).U(FetchBlockOffsetWidth.W)
      block.target := nextPC(s1FetchPC, (FetchBlockNumSlots - 1).U(FetchBlockOffsetWidth.W))
      block.taken := false.B
      block.cfiAttribute := 0.U.asTypeOf(new CFIAttribute)
    }

    when(backendEn){
      block.dirMispredict := backendRedirect.mispredict && backendDirWrong
      block.targetMispredict := backendRedirect.mispredict && backendTargetWrong
    }.elsewhen(predecodeEn){
      block.dirMispredict := predecode.redirectValid && predecodeDirWrong
      block.targetMispredict := predecode.redirectValid && predecodeTargetWrong
    }.elsewhen(s3En){
      block.dirMispredict := false.B
      block.targetMispredict := false.B
    }

    when(predecodeEn){
      entry.isRVC := predecode.isRVC
    }

    when(s3En){
      entry.meta.ftbMeta := s3Meta.ftb
      entry.meta.tageMeta := s3Meta.tage
      entry.meta.ittageMeta := s3Meta.ittage
      entry.spec.rasMeta := s3Meta.ras
      entry.spec.phrMeta := s3Meta.phr
    }
  }


  //lookup
  val lookupBypass = lookupPtr === pushPtr
  val lookupEntry = ftqEntries(lookupPtr.value)
  val lookupEntryValid = (pushPtr > lookupPtr) && lookupEntry.isLookup
  val lookupReq = lookupEntryValid || lookupBypass && s1FetchFire
  val lookupValid = lookupReq
  val ftqLookup = Wire(new FTQLookup)
  ftqLookup.ftqPtr := Mux(lookupBypass, pushPtr, lookupPtr)
  ftqLookup.lookupPC := Mux(lookupBypass, s1FetchPC, lookupEntry.fetchBlock.startAddr)

  //fetch
  val fetchEntry = ftqEntries(fetchPtr.value)
  val fetchBypass = fetchParallel && lookupBypass
  val fetchEntryValid = fetchEntry.isFetch
  val fetchReq = fetchEntryValid || fetchParallel && lookupReq
  val fetchValid = fetchReq
  val ftqFetch = Wire(new FTQFetch)
  ftqFetch.ftqPtr := Mux(fetchBypass, pushPtr, fetchPtr)
  ftqFetch.fetchPC := Mux(fetchBypass, s1FetchPC, fetchEntry.fetchBlock.startAddr)
  ftqFetch.endOffset := Mux(
    fetchBypass,
    (FetchBlockNumSlots - 1).U(FetchBlockOffsetWidth.W),
    fetchEntry.fetchBlock.endOffset,
  )
  ftqFetch.parallel := fetchParallel
  val fetchFullOffset = (FetchBlockNumSlots - 1).U(FetchBlockOffsetWidth.W)
  ftqFetch.taken := !fetchBypass && fetchEntry.fetchBlock.taken
  ftqFetch.target := Mux(
    fetchBypass,
    nextPC(s1FetchPC, fetchFullOffset),
    fetchEntry.fetchBlock.target,
  )
  ftqFetch.rasTopAddr := Mux(fetchBypass, 0.U(pcWidth.W), fetchEntry.spec.rasMeta.topAddr)

  //commit
  val commitEntry = ftqEntries(popPtr.value)
  //redirect。s3 override 不产生 BPU redirect。
  val backRedirectEntry = ftqEntries(backendPtr.value)
  val redirectEntry = ftqEntries(redirectPtr.value)
  // predecode.target 只是跳转目标。not-taken 时重定向回到顺序下一条。
  val predecodeRedirectTarget = Mux(
    predecode.taken,
    predecode.target,
    nextPC(fromIFU.bits.startPc, predecode.endOffset),
  )
  val redirectCfi = Mux(backendRedirectValid, backendRedirect.cfiAttribute, predecode.cfiAttribute)
  val redirectTarget = Mux(backendRedirectValid, backendRedirect.target, predecodeRedirectTarget)
  val redirectTaken = Mux(backendRedirectValid, backendRedirect.taken, predecode.taken)
  val redirectStartPC = Mux(backendRedirectValid, backRedirectEntry.fetchBlock.startAddr, fromIFU.bits.startPc)
  val redirectEndOffset = Mux(backendRedirectValid, backendRedirect.redirectPosition.offset, predecode.endOffset)

  val trainBlock = Wire(new FTQFetchBlcokEntry)
  trainBlock := commitEntry.fetchBlock

  val trainMeta = Wire(new BPUTrainMeta)
  trainMeta.ftb := commitEntry.meta.ftbMeta
  trainMeta.tage := commitEntry.meta.tageMeta
  trainMeta.ittage := commitEntry.meta.ittageMeta
  trainMeta.phr := commitEntry.spec.phrMeta

//to BPU
  io.fromBPU.s1Fetch.ready := !full || popFire
  io.toBPU.bpuPtr := pushPtr

  io.toBPU.train.valid := commitEntry.isCommit
  io.toBPU.train.bits.fetchBlockInfo := trainBlock
  io.toBPU.train.bits.meta := trainMeta
  io.toBPU.redirect.valid := redirectFlush
  io.toBPU.redirect.bits.target := redirectTarget
  io.toBPU.redirect.bits.taken := redirectTaken
  io.toBPU.redirect.bits.startPC := redirectStartPC
  io.toBPU.redirect.bits.endOffset := redirectEndOffset
  io.toBPU.redirect.bits.cfiAttribute := redirectCfi
  io.toBPU.redirect.bits.meta.phr := redirectEntry.spec.phrMeta
  io.toBPU.redirect.bits.meta.ras := redirectEntry.spec.rasMeta

//to IFU
  io.toIFU.s3Override := s3ResultValid && s3Override
  io.toIFU.lookUp.valid := lookupValid
  io.toIFU.lookUp.bits := ftqLookup
  io.toIFU.fetch.valid := fetchValid
  io.toIFU.fetch.bits := ftqFetch

//to Backend
}
