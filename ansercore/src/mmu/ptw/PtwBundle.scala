package ansercore.mmu.ptw

import chisel3._
import chisel3.util._

import ansercore.mmu.MMUConstant._
import ansercore.mmu.Pbmt

class FlagEntry extends Bundle{
  val d = Bool()
  val a = Bool()
  val g = Bool()
  val u = Bool()
  val x = Bool()
  val w = Bool()
  val r = Bool()
  val v = Bool()
}

class PageTableEntry extends Bundle{
  val napot = UInt(NapotWidth.W)
  val pbmt = Pbmt()
  val reserved = UInt(ReservedWidth.W)
  val ppn = UInt(PpnWidth.W)
  val rsw = UInt(RswWidth.W)
  val flag = new FlagEntry
}

