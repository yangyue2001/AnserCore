package ansercore.frontend.bpu.ftb

import chisel3._
import chisel3.util._
import utils.Mux1H


trait FTBHelper {
  this: HasFTBParameters =>

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

  def getSetIndex(pc: UInt) :UInt ={
    require(pc.getWidth == pcWidth)
    pc(BankIdxWidth+SetIdxWidth-1, BankIdxWidth)
  }

  def getTag(pc: UInt) :UInt ={
    require(pc.getWidth == pcWidth)
    pc(SetIdxWidth+BankIdxWidth+TagWidth-1, SetIdxWidth+BankIdxWidth)
  }

  def getTarget(pc: UInt, tarStat: UInt, lower: UInt): UInt = {
    require(pc.getWidth == pcWidth)
    val lowerWidth = lower.getWidth

    val pcHigh = pc(pcWidth-1, lowerWidth)
    val plusOne  = (pcHigh + 1.U)
    val minusOne = (pcHigh - 1.U)
    val targetHigh = MuxLookup(tarStat, pcHigh)(Seq(
      0.U -> pcHigh,
      1.U -> plusOne,
      2.U -> minusOne
    ))
    Cat(targetHigh, lower)
  }

}
