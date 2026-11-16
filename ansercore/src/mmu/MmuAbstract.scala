package ansercore.mmu

import chisel3._
import chisel3.util._


abstract class MMUBundle extends Bundle with HasMMUParameters
abstract class MMUModule extends Module with HasMMUParameters

abstract class TlbEntry extends MMUBundle{
  val ppn = UInt(ppnWidth.W)
  val flags = {
    val d = Bool()
    val a = Bool()
    val g = Bool()
    val u = Bool()
    val x = Bool()
    val w = Bool()
    val r = Bool()
  }
}