package ansercore.frontend.bpu.phr

import chisel3._


abstract class PHRBundle extends Bundle with HasPHRParameters

abstract class PHRModule extends Module with HasPHRParameters
