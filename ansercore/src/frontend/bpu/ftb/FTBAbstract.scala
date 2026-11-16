package ansercore.frontend.bpu.ftb

import chisel3._
import chisel3.util._


abstract class FTBBundle  extends Bundle with HasFTBParameters
abstract class FTBModule  extends Module with HasFTBParameters