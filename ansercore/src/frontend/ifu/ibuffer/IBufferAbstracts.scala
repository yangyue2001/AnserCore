package ansercore.frontend.ifu.ibuffer


import chisel3._
import chisel3.util._


abstract class IBufferBundle  extends Bundle with HasIBufferParameters
abstract class IBufferModule  extends Module with HasIBufferParameters