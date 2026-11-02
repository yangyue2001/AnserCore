package ansercore.mmu.l2Tlb

import chisel3._
import chisel3.util._
import ansercore.mmu.MmuConstant._


class ColtPteCacheEntry(implicit info: PteCacheParams) extends PteCacheEntry {
  val ppnHigh = UInt(compressedPpnWidth.W)
  val ppnLow = Vec(ColtFactor, UInt(ColtFactorWidth.W))
  val pageFault = Vec(ColtFactor, Bool())
}

class RawPteCacheEntry(implicit info: PteCacheParams) extends PteCacheEntry{
  val ppn = UInt(ppnWidth.W)
  val pageFault = Bool()
}