package ansercore.frontend.bpu.ittage

import chisel3._
import chisel3.util._
import ansercore.frontend.bpu.tage.TAGETableInfo

trait ITTAGEHelper {
  this: HasITTAGEParameters =>

  // 1 bank 不占 PC 低位。set 和 tag 从 bit 0 取。
  def BankPCLow: Int = if (NumBanks == 1) 0 else BankIdxWidth

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
      val bank = pc(BankIdxWidth - 1, 0)
      require(bank.getWidth == BankIdxWidth)
      UIntToOH(bank, NumBanks)
    }
    require(mask.getWidth == NumBanks)
    mask
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

  def getSetIndex(pc: UInt, hist: ITTAGEFoldedHist)(implicit info: TAGETableInfo): UInt = {
    require(pc.getWidth == pcWidth)
    val forIdx = hist.forIdx
    require(forIdx.getWidth == SetIdxWidth)
    val pcSet = pc(SetIdxWidth + BankPCLow - 1, BankPCLow)
    require(pcSet.getWidth == SetIdxWidth)
    xorSame(SetIdxWidth, pcSet, forIdx)
  }

  def getTag(pc: UInt, hist: ITTAGEFoldedHist)(implicit info: TAGETableInfo): UInt = {
    require(pc.getWidth == pcWidth)
    require(SetIdxWidth + BankPCLow + TagWidth <= pcWidth, "pc is shorter than set + tag")
    val hist0 = hist.forTag(0)
    val hist1 = hist.forTag(1)
    require(hist0.getWidth == TagWidth)
    require(hist1.getWidth == TagWidth)
    val pcTag = pc(SetIdxWidth + BankPCLow + TagWidth - 1, SetIdxWidth + BankPCLow)
    require(pcTag.getWidth == TagWidth)
    xorSame(TagWidth, pcTag, hist0, hist1)
  }

  def getRegion(pc: UInt): UInt = {
    require(pc.getWidth == pcWidth)
    val region = pc(pcWidth-1, TargetOffsetWidth)
    require(region.getWidth == RegionWidth)
    region
  }

  def getOffset(target: UInt): UInt = {
    require(target.getWidth == pcWidth)
    val offset = target(TargetOffsetWidth-1, 0)
    require(offset.getWidth == TargetOffsetWidth)
    offset
  }

}
