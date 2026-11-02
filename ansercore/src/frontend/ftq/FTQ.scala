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
    val fromIFU = Flipped(new IFUToFTQIO)
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
  val predecodeValid = fromIFU.preDecodeInfo.valid
  val predecode = fromIFU.preDecodeInfo.bits
  val predecodePtr = fromIFU.ftqPtr
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

  // 后端重定向压过 predecode。两者都没有时，s3 override 只丢掉自己后面的块。
  val bpuOverride = s3ResultValid && s3Override
  val ftqFlush = backendRedirectValid || predecodeRedirect || bpuOverride
  val flushPtr = Wire(new FTQPtr)
  when(bpuOverride){
    keepPtr := s3FTQPtr
  }
  when(predecodeRedirect){
    keepPtr := predecodePtr
  }
  when(backendRedirectValid){
    keepPtr := backendPtr
  }

  val ftqEntries = RegInit(VecInit.fill(FTQSize)(0.U.asTypeOf(new FTQEntry)))

  val lookupAhead = ftqFlush && lookupPtr > keepPtr
  val fetchAhead = ftqFlush && fetchPtr > keepPtr

  when(ftqFlush){
    pushPtr := keepPtr + 1.U
  }.elsewhen(s1FetchFire){
    pushPtr := pushPtr + 1.U
  }

  when(lookupAhead){
    lookupPtr := keepPtr + 1.U
  }.elsewhen(lookupFire){
    lookupPtr := lookupPtr + 1.U
  }

  when(fetchAhead){
    fetchPtr := keepPtr + 1.U
  }.elsewhen(fetchFire){
    fetchPtr := fetchPtr + 1.U
  }

  when(popFire){
    popPtr := popPtr + 1.U
  }

  val full = isFull(pushPtr, popPtr)
  val empty = isEmpty(pushPtr, popPtr)
  // keepPtr 自己留下，冲掉 (keepPtr, pushPtr)。
  val flushMask = circularRangMask(keepPtr + 1.U, pushPtr)

  val backCommitEntry = ftqEntries(backendCommit.ptr.value)
  val commitHit = backCommitEntry.fetchBlock.endOffset === backendCommit.offset
  val commitRange = Mux(
    commitHit,
    circularRangMask(popPtr, backendCommit.ptr) | UIntToOH(backendCommit.ptr.value),
    circularRangMask(popPtr, backendCommit.ptr),
  )
  val commitMask = Mux(backendCommitValid, commitRange, 0.U(FTQSize.W))

  val fetchParallel = lookupPtr === fetchPtr

  def nextPC(start: UInt, offset: UInt): UInt = {
    (start + offset + 1.U)(pcWidth - 1, 0)
  }

//状态机
  ftqEntries.zipWithIndex.foreach{case(entry, idx) =>
    val state = entry.state
    val nextState = Wire(FTQState())
    val flushEn = ftqFlush && flushMask(idx)
    val commitEn = commitMask(idx)

    val lookupEn = idx.U === lookupPtr.value
    val fetchEn = idx.U === fetchPtr.value
    val popEn = idx.U === popPtr.value
    val pushEn = s1FetchFire && !ftqFlush && idx.U === pushPtr.value
    val s3En = s3ResultValid && idx.U === s3FTQPtr.value && !flushEn
    val predecodeEn = predecodeValid && idx.U === predecodePtr.value && !flushEn
    val backendEn = backendRedirectValid && idx.U === backendPtr.value

    switch(state){
      is(FTQState.lookup){
        when(lookupFire && lookupEn){
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
        when(fetchFire && fetchEn){
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
        when(popFire && popEn){
          nextState := FTQState.lookup
        }.otherwise{
          nextState := FTQState.commit
        }
      }
    }

    when(flushEn){
      state := FTQState.lookup
    }.otherwise{
      state := nextState
    }

    val block = entry.fetchBlock
    val oldTaken = block.taken
    val oldTarget = block.target
    val oldEnd = block.endOffset
    // predecode 的 target 只在 taken 时是跳转目标。not-taken 的下一条用顺序 PC。
    val predecodeNext = Mux(predecode.taken, predecode.target, nextPC(block.startAddr, predecode.endOffset))
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
      block.dirMispredict := backendRedirect.mispredict && backendDirWrong
      block.targetMispredict := backendRedirect.mispredict && backendTargetWrong
      when(predecodeEn){
        block.cfiAttribute := predecode.cfiAttr
        entry.isRVC := predecode.isRVC
      }
    }.elsewhen(predecodeEn){
      block.endOffset := predecode.endOffset
      block.target := predecodeNext
      block.taken := predecode.taken
      block.cfiAttribute := predecode.cfiAttr
      block.dirMispredict := predecode.redirectValid && predecodeDirWrong
      block.targetMispredict := predecode.redirectValid && predecodeTargetWrong
      entry.isRVC := predecode.isRVC
    }.elsewhen(s3En){
      block.endOffset := s3Prediction.endOffset
      block.target := s3Prediction.target
      block.taken := s3Prediction.taken
      block.cfiAttribute := s3Prediction.endCFIAttribute
      block.dirMispredict := false.B
      block.targetMispredict := false.B
    }.elsewhen(pushEn){
      block.endOffset := (FetchBlockNumSlots - 1).U(FetchBlockOffsetWidth.W)
      block.target := nextPC(s1FetchPC, (FetchBlockNumSlots - 1).U(FetchBlockOffsetWidth.W))
      block.taken := false.B
      block.cfiAttribute := 0.U.asTypeOf(new CFIAttribute)
      block.dirMispredict := false.B
      block.targetMispredict := false.B
      entry.isRVC := 0.U.asTypeOf(entry.isRVC)
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
  val lookupEntryValid = pushPtr > lookupPtr
  val lookupEntry = ftqEntries(lookupPtr.value)
  val lookupValid = lookupEntry.isLookup && lookupEntryValid || lookupBypass && s1FetchFire
  val ftqLookup = Wire(new FTQLookup)
  ftqLookup.ftqPtr := Mux(lookupBypass, pushPtr, lookupPtr)
  ftqLookup.lookupPC := Mux(lookupBypass, s1FetchPC, lookupEntry.fetchBlock.startAddr)

  //fetch
  val fetchEntry = ftqEntries(fetchPtr.value)
  val fetchBypass = fetchParallel && lookupBypass
  val fetchValid = fetchEntry.isFetch || fetchParallel && lookupValid
  val ftqFetch = Wire(new FTQFetch)
  ftqFetch.ftqPtr := Mux(fetchBypass, pushPtr, fetchPtr)
  ftqFetch.fetchPC := Mux(fetchBypass, s1FetchPC, fetchEntry.fetchBlock.startAddr)
  ftqFetch.endOffset := Mux(
    fetchBypass,
    (FetchBlockNumSlots - 1).U(FetchBlockOffsetWidth.W),
    fetchEntry.fetchBlock.endOffset,
  )
  ftqFetch.parallel := fetchParallel

  //commit
  val commitEntry = ftqEntries(popPtr.value)
  val popFlushed = ftqFlush && flushMask(popPtr.value)
  //redirect
  val redirectEntry = ftqEntries(keepPtr.value)
  val redirectFromS3 = s3ResultValid && s3FTQPtr === keepPtr
  val redirectCfi = Wire(new CFIAttribute)
  redirectCfi := Mux(redirectFromS3, s3Prediction.endCFIAttribute, redirectEntry.fetchBlock.cfiAttribute)
  when(predecodeValid && predecodePtr === keepPtr){
    redirectCfi := predecode.cfiAttr
  }

  val redirectTarget = Wire(UInt(pcWidth.W))
  val redirectTaken = Wire(Bool())
  val redirectOffset = Wire(UInt(FetchBlockOffsetWidth.W))
  redirectTarget := Mux(redirectFromS3, s3Prediction.target, redirectEntry.fetchBlock.target)
  redirectTaken := Mux(redirectFromS3, s3Prediction.taken, redirectEntry.fetchBlock.taken)
  redirectOffset := Mux(redirectFromS3, s3Prediction.endOffset, redirectEntry.fetchBlock.endOffset)
  when(predecodeRedirect){
    redirectTarget := Mux(predecode.taken, predecode.target, nextPC(redirectEntry.fetchBlock.startAddr, predecode.endOffset))
    redirectTaken := predecode.taken
    redirectOffset := predecode.endOffset
  }
  when(backendRedirectValid){
    redirectTarget := backendRedirect.target
    redirectTaken := backendRedirect.taken
    redirectOffset := backendRedirect.redirectPosition.offset
  }

  val trainBlock = Wire(new FTQFetchBlcokEntry)
  trainBlock := commitEntry.fetchBlock
  when(predecodeValid && predecodePtr === popPtr){
    trainBlock.endOffset := predecode.endOffset
    trainBlock.target := Mux(predecode.taken, predecode.target, nextPC(commitEntry.fetchBlock.startAddr, predecode.endOffset))
    trainBlock.taken := predecode.taken
    trainBlock.cfiAttribute := predecode.cfiAttr
    trainBlock.dirMispredict := predecode.redirectValid && (
      commitEntry.fetchBlock.taken =/= predecode.taken ||
        commitEntry.fetchBlock.endOffset =/= predecode.endOffset
    )
    trainBlock.targetMispredict := predecode.redirectValid && predecode.taken &&
      commitEntry.fetchBlock.target =/= predecode.target
  }
  when(backendRedirectValid && backendPtr === popPtr){
    trainBlock.endOffset := backendRedirect.redirectPosition.offset
    trainBlock.target := backendRedirect.target
    trainBlock.taken := backendRedirect.taken
    trainBlock.dirMispredict := backendRedirect.mispredict && (
      commitEntry.fetchBlock.taken =/= backendRedirect.taken ||
        commitEntry.fetchBlock.endOffset =/= backendRedirect.redirectPosition.offset
    )
    trainBlock.targetMispredict := backendRedirect.mispredict && backendRedirect.taken &&
      commitEntry.fetchBlock.target =/= backendRedirect.target
  }

  val trainMeta = Wire(new BPUTrainMeta)
  trainMeta.ftb := commitEntry.meta.ftbMeta
  trainMeta.tage := commitEntry.meta.tageMeta
  trainMeta.ittage := commitEntry.meta.ittageMeta
  trainMeta.phr := commitEntry.spec.phrMeta
  when(redirectFromS3 && s3FTQPtr === popPtr){
    trainMeta.ftb := s3Meta.ftb
    trainMeta.tage := s3Meta.tage
    trainMeta.ittage := s3Meta.ittage
    trainMeta.phr := s3Meta.phr
  }

//to BPU
  io.fromBPU.s1Fetch.ready := !full || popFire
  io.toBPU.bpuPtr := pushPtr

  io.toBPU.train.valid := commitEntry.isCommit && !popFlushed
  io.toBPU.train.bits.fetchBlockInfo := trainBlock
  io.toBPU.train.bits.meta := trainMeta
  io.toBPU.redirect.valid := backendRedirectValid || predecodeRedirect
  io.toBPU.redirect.bits.target := redirectTarget
  io.toBPU.redirect.bits.taken := redirectTaken
  io.toBPU.redirect.bits.cfiPC := (redirectEntry.fetchBlock.startAddr + redirectOffset)(pcWidth - 1, 0)
  io.toBPU.redirect.bits.rasAction := redirectCfi.rasAction.asUInt
  io.toBPU.redirect.bits.meta.phr := Mux(redirectFromS3, s3Meta.phr, redirectEntry.spec.phrMeta)
  io.toBPU.redirect.bits.meta.ras := Mux(redirectFromS3, s3Meta.ras, redirectEntry.spec.rasMeta)

//to IFU
  val s3Kept = s3ResultValid && !(ftqFlush && flushMask(s3FTQPtr.value))
  io.toIFU.s3Override := s3Kept && s3Override
  io.toIFU.lookUp.valid := lookupValid && !lookupAhead
  io.toIFU.lookUp.bits := ftqLookup
  io.toIFU.fetch.valid := fetchValid && !fetchAhead
  io.toIFU.fetch.bits := ftqFetch

//to Backend
}
