package ansercore

import chisel3._
import chisel3.util._

import ansercore.CoreConstant
import ansercore.frontend.FrontendParameters
import ansercore.mmu.MmuParameters
import ansercore.mmu.MmuConstant

case class Parameters(
  paddrBits: Int = 56,
  hartIdWidth: Int = 1,
  frontendBlockBytes: Int = 24,

  frontendParameters: FrontendParameters = FrontendParameters(),
  mmuParameters: MmuParameters = MmuParameters(),
)


trait HasYQHParameters{
  implicit val p : Parameters = Parameters()

  def coreParams = p
  def hartIdWidth = p.hartIdWidth
  def paddrWidth = p.paddrBits
  def InstOffsetWidth = CoreConstant.InstOffsetWidth
  def pcWidth = vaddrWidth - InstOffsetWidth

  def FrontendBlockBytes = p.frontendParameters.frontendBlockBytes
  def MinInstBytes = CoreConstant.MinInstBytes
  def FetchBlockNumSlots = FrontendBlockBytes / MinInstBytes
  def FetchBlockOffsetWidth = log2Ceil(FetchBlockNumSlots)


  def DecodeWidth = 4
  def RobIdxWidth = 32

  //MMU
  def PageOffsetWidth = MmuConstant.PageOffsetWidth
  def vpnWidth = p.mmuParameters.svMode.Levels * MmuConstant.LevelVpnWidth
  def vaddrWidth = vpnWidth + PageOffsetWidth
  def ppnWidth = paddrWidth - PageOffsetWidth
  //Cache
  def CachelineBytes = CoreConstant.CachelineBytes
  def CachelineBits = CoreConstant.CachelineBits
   //L1 To L2
  def ICacheToL2DataWidth = 256
  def ICacheToL2DataBytes = ICacheToL2DataWidth / 8
  def ICacheToL2AddrOffsetWidth = log2Ceil(ICacheToL2DataBytes)
  def ICacheToL2BeatAddrWidth = paddrWidth - ICacheToL2AddrOffsetWidth
  def ICacheToL2BeatsPerLine = CachelineBits / ICacheToL2DataWidth
  def ICacheToL2BeatWidth = log2Ceil(ICacheToL2BeatsPerLine)
}