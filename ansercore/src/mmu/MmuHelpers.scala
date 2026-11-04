package ansercore.mmu

import chisel3._
import chisel3.util._

trait MMUHelpers {
  this: HasMMUParameters =>

  def getVpnFromVaddr(vaddr: UInt): UInt ={
    vaddr(vaddrWidth, PageOffsetWidth)
  }
}