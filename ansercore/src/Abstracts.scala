package ansercore

import chisel3._
import chisel3.util._

abstract class AnserBundle extends Bundle with HasAnserParameters
abstract class AnserModule extends Module with HasAnserParameters
