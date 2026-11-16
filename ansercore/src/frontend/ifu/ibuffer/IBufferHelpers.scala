package ansercore.frontend.ifu.ibuffer

import chisel3._
import chisel3.util._

trait IBufferHelper extends HasIBufferParameters {

  def linearOf(bankOH: UInt, rowOH: UInt, numBanks: Int): UInt = {
    val linear = OHToUInt(rowOH) * numBanks.U + OHToUInt(bankOH)
    linear(log2Ceil(IBufferSize) - 1, 0)
  }

  def wrapOffset(base: UInt, offset: UInt): UInt = {
    val sum = base +& offset
    val wrapped = Mux(sum >= IBufferSize.U, sum - IBufferSize.U, sum)
    wrapped(log2Ceil(IBufferSize) - 1, 0)
  }

  def oneHotBankRow(linear: UInt, numBanks: Int, numRows: Int): (UInt, UInt) = {
    val bank = linear % numBanks.U
    val row = linear / numBanks.U
    (UIntToOH(bank, numBanks), UIntToOH(row, numRows))
  }
}
