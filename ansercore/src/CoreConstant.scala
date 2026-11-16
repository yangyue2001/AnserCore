package ansercore

import chisel3._
import chisel3.util._

object CoreConstant{
  val XLEN: Int = 64
  val MinInstBytes: Int = 2
  val InstOffsetWidth: Int = log2Ceil(MinInstBytes)
  val CachelineBytes: Int = 64
  val CachelineBits: Int = CachelineBytes * 8

}
