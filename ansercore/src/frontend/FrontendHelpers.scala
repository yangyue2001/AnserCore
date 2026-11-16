package ansercore.frontend

import chisel3._
import chisel3.util._

import ansercore.frontend.ifu.HasIFUParameters


trait FrontendHelper {
  this: HasFrontendParameters =>

  // 页内 slot 数是 2^(页偏移位宽 - 指令低位)，不是两个位宽相除。
  private def pageSlotIdxWidth: Int = {
    require(PageOffsetWidth > InstOffsetWidth)
    PageOffsetWidth - InstOffsetWidth
  }

  private def pageSlots: Int = {
    val slots = 1 << pageSlotIdxWidth
    require(FetchBlockNumSlots > 0 && FetchBlockNumSlots <= slots)
    slots
  }

  private def startSlotInPage(startPC: UInt): UInt = {
    require(startPC.getWidth == pcWidth)
    val fullPC = Cat(startPC, 0.U(InstOffsetWidth.W))
    val slot = fullPC(PageOffsetWidth - 1, InstOffsetWidth)
    require(slot.getWidth == pageSlotIdxWidth)
    slot
  }

  def isCrossPage(startPC: UInt): Bool =
    startSlotInPage(startPC) > (pageSlots - FetchBlockNumSlots).U

  def getPageEndOffset(startPC: UInt): UInt = {
    val startSlot = startSlotInPage(startPC)
    val slotsLeft = pageSlots.U((pageSlotIdxWidth + 1).W) - Cat(0.U(1.W), startSlot)
    (slotsLeft - 1.U(slotsLeft.getWidth.W))(FetchBlockOffsetWidth - 1, 0)
  }

  def getPageEndSlot(startPC: UInt): (Bool, UInt) =
    (isCrossPage(startPC), getPageEndOffset(startPC))


  // 顺序取指最多走一个 fetch block。块尾跨页时，目标停在下一页入口。
  def sequentialFetch(startPC: UInt): (UInt, UInt) = {
    require(startPC.getWidth == pcWidth)
    val (crossPage, pageEndOffset) = getPageEndSlot(startPC)
    val nextPC = (startPC + FetchBlockNumSlots.U)(pcWidth - 1, 0)
    val pagePC = fallThroughPagePC(startPC)
    require(pagePC.getWidth == pcWidth)
    require(pageEndOffset.getWidth == FetchBlockOffsetWidth)

    val target = Wire(UInt(pcWidth.W))
    target := Mux(crossPage, pagePC, nextPC)
    val offset = Wire(UInt(FetchBlockOffsetWidth.W))
    offset := Mux(crossPage, pageEndOffset, (FetchBlockNumSlots - 1).U(FetchBlockOffsetWidth.W))
    (target, offset)
  }

  def fallThroughPagePC(startPC: UInt): UInt ={
    require(startPC.getWidth == pcWidth)
    val fullPC = Cat(startPC, 0.U(1.W))
    val nextPagePC = fullPC(vaddrWidth-1, PageOffsetWidth) + 1.U
    Cat(nextPagePC, 0.U(PageOffsetWidth.W))(vaddrWidth-1, 1)
  }

  def getVaddr(addr: UInt): UInt = {
    require(addr.getWidth > 0 && addr.getWidth <= vaddrWidth)
    val vaddr =  if(addr.getWidth == vaddrWidth) addr
    else Cat(addr, 0.U((vaddrWidth-addr.getWidth).W))
    vaddr
  }

  def getPaddr(addr: UInt): UInt = {
    require(addr.getWidth > 0 && addr.getWidth <= paddrWidth)
    val paddr =  if(addr.getWidth == paddrWidth) addr
    else Cat(addr, 0.U((paddrWidth-addr.getWidth).W))
    paddr
  }
}