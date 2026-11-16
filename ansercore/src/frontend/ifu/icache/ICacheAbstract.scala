package ansercore.frontend.ifu.icache

import chisel3._
import chisel3.util._

abstract class ICacheBundle extends Bundle with HasICacheParameters
abstract class ICacheModule extends Module with HasICacheParameters