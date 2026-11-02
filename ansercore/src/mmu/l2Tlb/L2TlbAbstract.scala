package ansercore.mmu.l2Tlb


import chisel3._
import chisel3.util._
import ansercore.mmu.MmuConstant._
import ansercore.mmu.Pbmt

abstract class L2TlbBundle extends Bundle with HasL2TlbParameters
abstract class L2TlbModule extends Module with HasL2TlbParameters


abstract class PteCacheEntry(implicit info: PteCacheParams) extends L2TlbBundle {
  val valid = Bool()
  val vTag = UInt(vTagWidth.W)
  val napot = Bool()
  val pbmt = Pbmt()
  val asid = UInt(SatpAsidWidth.W)
  val d = Bool()
  val g = Bool()
  val u = Bool()
  val x = Bool()
  val w = Bool()
  val r = Bool()
  val accessFault = Bool()
}

