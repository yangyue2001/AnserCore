package ansercore.mmu

import chisel3._
import chisel3.util._

trait MmuHelpers {
  this: HasMmuParameters =>

  def getVpnFromVaddr(vaddr: UInt): UInt ={
    vaddr(vaddrWidth, PageOffsetWidth)
  }
}