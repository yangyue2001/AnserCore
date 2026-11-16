package ansercore.frontend.bpu.phr

import chisel3._
import chisel3.util._
import scala.math.min
import utils.Rota

trait PHRHelper {
  this: HasPHRParameters =>
// 没毛
  def pathHash(startPC: UInt, target: UInt): UInt = {
    require(startPC.getWidth == pcWidth)
    require(target.getWidth == pcWidth)
    // 保存的 PC 已经去掉字节地址 bit 0。再丢掉最低位，和 target 对齐后异或。
    val pcPart = startPC(HashWidth, 1)
    val targetPart = target(HashWidth, 1)
    require(pcPart.getWidth == HashWidth)
    require(targetPart.getWidth == HashWidth)
    pcPart ^ targetPart
  }

/*
  def readLogical(phr: Vec[UInt], ptr: PHRPtr): UInt = {
    require(phr.length == HistoryChunks)
    require(phr.head.getWidth == Shamt)
    val read = Wire(Vec(MaxHistChunks, UInt(Shamt.W)))
    (0 until MaxHistChunks).foreach { index =>
      val phrRota = Rota(phr, i+1, Rota.Right)
      read(i) := phrRota(ptr.value)
    }
    read.asUInt
  }

  def computeFolded(bits: UInt, foldedLen: Int, histLen: Int): UInt = {
    require(bits.getWidth >= histLen)
    require(histLen > 0 && foldedLen > 0 && foldedLen <= histLen)
    val history = bits(histLen - 1, 0)
    val nChunks = (histLen + foldedLen - 1) / foldedLen
    val chunks = (0 until nChunks).map { index =>
      val lo = index * foldedLen
      val hi = min((index + 1) * foldedLen, histLen) - 1
      val part = history(hi, lo)
      if (part.getWidth == foldedLen) part else Cat(0.U((foldedLen - part.getWidth).W), part)
    }
    chunks.reduce(_ ^ _)
  }

  def foldAll(logical: UInt): PHRAllFoldedHistories = {
    require(logical.getWidth == MaxHistLens)
    val folded = Wire(new PHRAllFoldedHistories)
    folded.allHist.foreach { hist =>
      val info = hist.foldInfo
      hist.foldedHist := computeFolded(logical, info.FoldedLength, info.HistoryLength)
    }
    folded
  }

  def foldedAt(mem: Vec[UInt], ptr: PHRPtr): PHRAllFoldedHistories = {
    foldAll(readLogical(mem, ptr))
  }

  def holdPHR(mem: Vec[UInt], ptr: PHRPtr, folded: PHRAllFoldedHistories): PHRView = {
    val view = Wire(new PHRView)
    view.mem := mem
    view.ptr := ptr
    view.folded := folded
    view
  }

  def updatePHR(mem: Vec[UInt], ptr: PHRPtr, hash: UInt): PHRView = {
    require(hash.getWidth == HashWidth)
    val hashChunks = hash.asTypeOf(Vec(PathHashChunks, UInt(Shamt.W)))
    val writeSlot = (0 until PathHashChunks).map { chunk =>
      // chunk 0 是 hash 的低位，也是最新的历史。
      ptrPlus(ptr, PathHashChunks - 1 - chunk)
    }
    val nextMem = Wire(Vec(HistoryChunks, UInt(Shamt.W)))
    (0 until HistoryChunks).foreach { slot =>
      val slotIdx = slot.U(PtrWidth.W)
      val hits = (0 until PathHashChunks).map { chunk =>
        (writeSlot(chunk) === slotIdx) -> hashChunks(chunk)
      }
      nextMem(slot) := MuxCase(mem(slot), hits)
    }
    val nextPtr = ptr + PathHashChunks.U
    val view = Wire(new PHRView)
    view.mem := nextMem
    view.ptr := nextPtr
    view.folded := foldedAt(nextMem, nextPtr)
    view
  }

  // taken 为假只回到预测现场；为真则在该现场上写入纠正后的 path hash。
  def applyOverride(mem: Vec[UInt], ptr: PHRPtr, taken: Bool, hash: UInt): PHRView = {
    val restored = holdPHR(mem, ptr, foldedAt(mem, ptr))
    val updated = updatePHR(mem, ptr, hash)
    Mux(taken, updated.asUInt, restored.asUInt).asTypeOf(new PHRView)
  }
  */
}
