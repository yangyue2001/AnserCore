package ansercore.frontend.bpu.phr

import chisel3._
import chisel3.util._

import utils.QueuePtr


class PHRPtr(chunks: Int) extends QueuePtr[PHRPtr](chunks)


class PHRMeta extends PHRBundle {
  val ptr = new PHRPtr(HistoryChunks)
  val lowBits = Vec(RemainChunks,UInt(Shamt.W))
}

class PHRUpdate extends PHRBundle {
  val cfiPC = UInt(pcWidth.W)
  val target = UInt(pcWidth.W)
}

class PHROverride extends PHRBundle {
  val taken = Bool()
  val update = new PHRUpdate
}

class PHRRedirect extends PHRBundle {
  val restore = new PHRMeta
  val update = new PHROverride
}

class PHRFoldedHistory(implicit info: FoldedHistoryInfo) extends PHRBundle with PHRHelper {

  require(FoldedLength >= Shamt)
  val foldInfo = info
  val foldedHist: UInt = UInt(FoldedLength.W)

/*
  def needOldestBits: Boolean = info.HistoryLength > info.FoldedLength
  def oldestBitToGetFromPHR: Seq[Int]     = (0 until maxUpdateNum).map(info.HistoryLength - _ - 1)
  def oldestBitPosInFolded:  Seq[Int]     = oldestBitToGetFromPHR.map(_ % info.FoldedLength)
  def oldestBitWrapAround:   Seq[Boolean] = oldestBitToGetFromPHR.map(_ / info.FoldedLength > 0)
  def oldestBitStart:        Int          = oldestBitPosInFolded.head

  def getOldestBitFromGhr(phr: Vec[Bool], histPtr: PHRPtr): Seq[Bool] =
    // TODO: wrap inc for histPtr value
    oldestBitToGetFromPHR.map(i => phr(i))

  // slow path, read bits from phr
  def update(phr: Vec[Bool], histPtr: PHRPtr, hashHigh: UInt, num: Int, shiftBits: UInt): PHRFoldedHistory = {
    val oldestBits = VecInit(getOldestBitFromGhr(phr, histPtr))
    update(oldestBits, num, shiftBits, hashHigh)
  }
*/

/*
  def foldFromPHR(logicalPHR: UInt): PHRFoldedHistory= {
    require(logicalPHR.getWidth == MaxHistLens)
    val res = Wire(new PHRFoldedHistory)
    res.foldedHist:= computeFolded(logicalPHR, info.FoldedLength, FoldHistoryLength)

    res
  }

  def update(logicalPHR: UInt, hash: UInt): PHRFoldedHistory = {
    require(hash.getWidth == HashWidth)
    require(logicalPHR.getWidth == MaxHistLens)

    def needOldestBits: Boolean = HistoryLength > FoldedLength

    val res = Wire(new PHRFoldedHistory)
    val newFoldedHist = if(needOldestBits){
      val res = Wire(Vec(FoldedLength, Bool()))

      (0 until RemainChunks).foreach{ i =>
        res(i+Shamt) := foldedHist(i)
      }

      (0 until Shamt).foreach{ i =>
        //res(i) := evictedBits(i)
      }
      res.asUInt
    }else{
      Cat(foldedHist(FoldedLength-1, Shamt), 0.U(Shamt.W))
    }

    val hashFolded = computeFolded(hash, FoldedLength, HistoryLength)
    res.foldedHist := newFoldedHist ^ hashFolded
    res
  }
  */
}


class PHRAllFoldedHistories extends PHRBundle{
  val allHist = MixedVec(AllFoldedHistoryInfo.toSeq.sortBy(_.asTuple).map{ flodInfo =>
    implicit val info = flodInfo
    new PHRFoldedHistory
  })

  // 匹配
  def getHistWithInfo(info: FoldedHistoryInfo): PHRFoldedHistory = {
    val selected = this.allHist.filter(_.foldInfo.equals(info))
    require(selected.length == 1)
    selected.head
  }

  def update(logicalPHR: UInt, hash: UInt): PHRAllFoldedHistories = {
    require(hash.getWidth == HashWidth)
    require(logicalPHR.getWidth == MaxHistLens)
    val res = Wire(new PHRAllFoldedHistories)
    /*
    for (i <- res.allHist.indices) {
      res.allHist(i) := this.allHist(i).update(logicalPHR, hash)
    }
    */
    res
  }
/*
  def display(cond: Bool): Unit =
    for (h <- hist) {
      XSDebug(
        cond,
        p"hist len ${h.info.HistoryLength}, folded len ${h.info.FoldedLength}, value ${Binary(h.foldedHist)}\n"
      )
    }
*/
}