package ansercore.frontend.ifu

import chisel3._
import chisel3.util._

abstract class IFUBundle  extends Bundle with HasIFUParameters

abstract class IFUModule  extends Module with HasIFUParameters

