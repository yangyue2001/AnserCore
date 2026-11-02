package ansercore.frontend.ftq

import chisel3._
import chisel3.util._



abstract class FTQBundle  extends Bundle with HasFTQParameters
abstract class FTQModule  extends Module with HasFTQParameters