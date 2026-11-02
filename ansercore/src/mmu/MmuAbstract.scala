package ansercore.mmu

import chisel3._
import chisel3.util._


abstract class MmuBundle extends Bundle with HasMmuParameters
abstract class MmuModule extends Module with HasMmuParameters

abstract class TlbEntry extends MmuBundle{
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