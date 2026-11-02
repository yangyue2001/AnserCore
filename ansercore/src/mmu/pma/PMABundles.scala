package ansercore.mmu.pma

import chisel3._
import chisel3.util._

import ansercore.mmu.MmuBundle

class PMAReq extends MmuBundle{
  val addr = UInt(paddrWidth.W)
}

class PMAResp extends MmuBundle {
  val ld     = Bool()
  val st     = Bool()
  val instr  = Bool()
  val cacheable = Bool()
  val memory    = Bool()
}
