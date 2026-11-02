package ansercore.frontend.bpu.tage

import chisel3._
import chisel3.util._
import ansercore.frontend.bpu.phr.PHRAllFoldedHistories


trait TAGEHelper extends HasTAGEParameters{

  def fitWidth(value: UInt, width: Int): UInt = {
    val valueWidth = value.getWidth
    require(valueWidth > 0, "value width must be positive")
    require(width > 0, "target width must be positive")
    require(valueWidth <= width, s"$valueWidth bit value does not fit in $width bits")
    val fitted = Wire(UInt(width.W))
    fitted := (if (valueWidth == width) value else Cat(0.U((width - valueWidth).W), value))
    fitted
  }

  def xorSame(width: Int, values: UInt*): UInt = {
    require(values.nonEmpty, "xor needs at least one value")
    require(width > 0, "xor width must be positive")
    values.foreach { value =>
      require(value.getWidth == width, s"${value.getWidth} bit value cannot xor a $width bit value")
    }
    val result = Wire(UInt(width.W))
    result := values.reduce(_ ^ _)
    result
  }

  def getBankMask(pc: UInt): UInt = {
    require(pc.getWidth == pcWidth)
    val mask = if (NumBanks == 1) {
      1.U(1.W)
    } else {
      UIntToOH(getBankIndex(pc), NumBanks)
    }
    require(mask.getWidth == NumBanks)
    mask
  }

  def getBankIndex(pc: UInt): UInt = {
    require(pc.getWidth == pcWidth)
    require(NumBanks > 1, "1 bank has no bank index")
    val bank = pc(BankIdxWidth - 1, 0)
    require(bank.getWidth == BankIdxWidth)
    bank
  }

  def bankSelected(valid: Bool, bankMask: UInt, bankIdx: Int): Bool = {
    if (NumBanks == 1) valid else valid && bankMask(bankIdx)
  }

  // 1 个 bank 直接连那一块 SRAM。多 bank 才寄存掩码并选择。
  def assignBankRead[T <: Data](
    dest: scala.collection.Seq[T],
    bankData: scala.collection.Seq[scala.collection.Seq[T]],
    bankMask: UInt,
    readValid: Bool,
  ): Unit = {
    if (NumBanks == 1) {
      dest.zipWithIndex.foreach { case (entry, idx) =>
        entry := bankData.head(idx)
      }
    } else {
      val readBank = RegEnable(bankMask, readValid)
      dest.zipWithIndex.foreach { case (entry, idx) =>
        entry := Mux1H((0 until NumBanks).map { bankIdx =>
          readBank(bankIdx) -> bankData(bankIdx)(idx)
        })
      }
    }
  }

  def getSetIndex(pc: UInt, hist: TAGEFoldedHist)(implicit info: TAGETableInfo) :UInt ={
    require(pc.getWidth == pcWidth)
    val forIdx = fitWidth(hist.forIdx, SetIdxWidth)
    val pcSet = pc(SetIdxWidth + BankPCLow - 1, BankPCLow)
    require(pcSet.getWidth == SetIdxWidth)
    xorSame(SetIdxWidth, pcSet, forIdx)
  }

  def getTag(pc: UInt, hist: TAGEFoldedHist, offset: UInt)(implicit info: TAGETableInfo): UInt = {
    require(pc.getWidth == pcWidth)
    require(TagWidth >= 2, "tag needs a TagWidth-1 component")
    val forTag0 = fitWidth(hist.forTag(0), TagWidth)
    val forTag1 = fitWidth(hist.forTag(1), TagWidth)
    val pcTag = pc(SetIdxWidth + BankPCLow + TagWidth - 1, SetIdxWidth + BankPCLow)
    require(pcTag.getWidth == TagWidth)
    val offsetTag = fitWidth(offset, TagWidth)
    xorSame(TagWidth, pcTag, forTag0, forTag1, offsetTag)
  }

  def getBaseSetIndex(pc: UInt): UInt = {
    val pcSet = pc(BaseTableSetIdxWidth + BankPCLow - 1, BankPCLow)
    require(pcSet.getWidth == BaseTableSetIdxWidth)
    pcSet
  }

  def getFoldedHist(allFoldedPathHist: PHRAllFoldedHistories): TAGEAllFoldedHistories = {
    val tageFoldHist = Wire(new TAGEAllFoldedHistories)

    TableInfos.zipWithIndex.foreach { case (tableInfo, tableIdx) =>
      implicit val info = tableInfo
      val folded = tableInfo.getTAGEFoldedHistoryInfo.map { histInfo =>
        allFoldedPathHist.getHistWithInfo(histInfo).foldedHist
      }
      // 第二分量先补到 TagWidth-1，再补最低位 0，结果正好是 TagWidth。
      val tag1Lo = fitWidth(folded(2), TagWidth - 1)
      val tag1 = Cat(tag1Lo, 0.U(1.W))
      require(tag1.getWidth == TagWidth)
      tageFoldHist.hist(tableIdx).forIdx    := fitWidth(folded(0), SetIdxWidth)
      tageFoldHist.hist(tableIdx).forTag(0) := fitWidth(folded(1), TagWidth)
      tageFoldHist.hist(tableIdx).forTag(1) := tag1
    }

    tageFoldHist
  }
}
