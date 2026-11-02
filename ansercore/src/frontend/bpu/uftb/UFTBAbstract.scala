package ansercore.frontend.bpu.uftb

import chisel3._
import chisel3.util._

abstract class UFTBBundle extends Bundle with HasUFTBParameters

abstract class UFTBModule extends Module with HasUFTBParameters