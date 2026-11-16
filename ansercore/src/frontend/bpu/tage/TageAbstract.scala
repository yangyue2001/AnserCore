package ansercore.frontend.bpu.tage

import chisel3._
import chisel3.util._


abstract class TAGEBundle  extends Bundle with HasTAGEParameters
abstract class TAGEModule  extends Module with HasTAGEParameters