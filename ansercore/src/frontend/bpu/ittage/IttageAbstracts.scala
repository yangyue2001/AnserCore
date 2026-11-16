package ansercore.frontend.bpu.ittage

import chisel3._
import chisel3.util._


abstract class ITTAGEBundle  extends Bundle with HasITTAGEParameters

abstract class ITTAGEModule  extends Module with HasITTAGEParameters
