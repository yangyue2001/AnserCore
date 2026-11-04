package ansercore.frontend.ftq

import chisel3._
import chisel3.util._

trait FTQHelper {
  this: HasFTQParameters =>

  // 顺序下一条。endOffset 是这条指令的结尾槽。
  def nextPC(startPC: UInt, endOffset: UInt): UInt = {
    require(startPC.getWidth == pcWidth)
    (startPC + endOffset + 1.U)(pcWidth - 1, 0)
  }

  // C 指令的 PC 就是结尾槽。32 位指令停在高半字，PC 要回到前一个槽。
  def cfiPC(startPC: UInt, endOffset: UInt, isRVC: Bool): UInt = {
    require(startPC.getWidth == pcWidth)
    val endPC = (startPC + endOffset)(pcWidth - 1, 0)
    Mux(isRVC, endPC, (endPC - 1.U)(pcWidth - 1, 0))
  }
}
