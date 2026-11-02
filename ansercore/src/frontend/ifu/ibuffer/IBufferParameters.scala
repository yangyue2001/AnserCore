package ansercore.frontend.ifu.ibuffer

import chisel3._
import chisel3.util._

import ansercore.frontend.ifu.HasIFUParameters

case class IBufferParameters(
  ibufferSize: Int = 48, // 能容纳48*2B 的指令
) {
}


trait HasIBufferParameters extends HasIFUParameters{
  def ibufferParameters = frontendParameters.ifuParameters.ibuffer

  def IBufferSize = ibufferParameters.ibufferSize
  def IBufferCountWidth = log2Ceil(IBufferSize+1)

  def NumWriteBanks = FetchBlockNumSlots
  def NumReadBanks = DecodeWidth*2
  def WriteBankDepth = IBufferSize / NumWriteBanks
  def ReadBankDepth = IBufferSize / NumReadBanks

  require( IBufferSize % NumReadBanks == 0)
  require( IBufferSize % NumWriteBanks == 0)
}
