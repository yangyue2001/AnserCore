package ansercore.frontend.bpu.ras

import chisel3._
import chisel3.util._
import ansercore.frontend.bpu._


abstract class RASBundle  extends BPUBundle with HasRASParameters

abstract class RASModule  extends BPUModule with HasRASParameters