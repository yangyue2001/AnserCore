package ansercore.frontend

import chisel3._
import chisel3.util._


abstract class FrontendBundle  extends Bundle with HasFrontendParameters
abstract class FrontendModule  extends Module with HasFrontendParameters