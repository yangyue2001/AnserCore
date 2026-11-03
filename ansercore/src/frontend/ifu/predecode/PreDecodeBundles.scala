package ansercore.frontend.ifu.predecode

import chisel3._
import chisel3.util._

import ansercore.frontend.ifu.IFUBundle
import ansercore.frontend.CFIAttribute
import ansercore.frontend.FetchBlock

class PredecodeInfo extends IFUBundle{
  val redirectValid = Bool()
  val endOffset = UInt(FetchBlockOffsetWidth.W)
  val target = UInt(pcWidth.W)
  val taken = Bool()
  val cfiAttribute = new CFIAttribute
  val isRVC = Vec(FetchBlockNumSlots, Bool())
}

class PreDecodeCFIAttribute extends IFUBundle{
  val branch = Bool()
  val jal = Bool()
  val jalr = Bool()
  val call = Bool()
  val ret = Bool()
}

class PreDecodeReq extends IFUBundle{
  val inst = Vec(FetchBlockNumSlots, UInt(16.W))
  val predTaken = Bool()
  val predEndOffset = UInt(FetchBlockOffsetWidth.W)
  val startPC = UInt(pcWidth.W)
  val rasTopAddr = UInt(pcWidth.W)
  val predTarget = UInt(pcWidth.W)
}