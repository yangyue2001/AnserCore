package ansercore.mmu.pmp

import chisel3._
import chisel3.util._

import ansercore.mmu.HasMmuParameters
import ansercore.mmu.MmuConstant

case class PmpParameters(
  val isaNumEntrys: Int = 64,   // only  0 , 16 ,64  isa 规定的
  val realNumEntrys: Int = 32,
){
  require(realNumEntrys <= isaNumEntrys)
}

trait HasPmpParameters extends HasMmuParameters{
  val pmpParameters = mmuParameters.pmpParameters

  def realNumEntrys = pmpParameters.realNumEntrys
  def pmpAlignWidth = log2Ceil(MmuConstant.AlignmentGranule)
  def pmpAddrWidth = paddrWidth - pmpAlignWidth
}