package ansercore.mmu.pmp

import chisel3._
import chisel3.util._

abstract class PmpBundle extends Bundle with HasPmpParameters
abstract class PmpModule extends Module with HasPmpParameters