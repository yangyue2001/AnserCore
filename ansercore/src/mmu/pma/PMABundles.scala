package ansercore.mmu.pma

import chisel3._
import chisel3.util._

import ansercore.mmu.MMUBundle

class PMAReq extends MMUBundle{
  val addr = UInt(paddrWidth.W)
}

class PMAResp extends MMUBundle {
  val ld     = Bool()
  val st     = Bool()
  val instr  = Bool()
  val cacheable = Bool()
  val memory    = Bool()
}
