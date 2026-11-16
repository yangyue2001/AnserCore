package ansercore

import chisel3._
import chisel3.util._

import ansercore.CoreConstant
import ansercore.frontend.FrontendParameters
import ansercore.mmu.MMUParameters
import ansercore.mmu.MMUConstant

case class Parameters(
  paddrBits: Int = 56,
  hartIdWidth: Int = 1,
  frontendBlockBytes: Int = 24,

  //backend
  decodeWidth: Int = 4,
  numROBEntrys: Int = 32,
  //mmu
  isaPMPNum: Int = 64,
  impPMPNum: Int = 32,
  pmaNum: Int = 32,
  granularity: Int = 0,
  //
  frontendParameters: FrontendParameters = FrontendParameters(),
  mmuParameters: MMUParameters = MMUParameters(),
){
  require(granularity >= 0)
  require(isaPMPNum == 0 || isaPMPNum == 16 || isaPMPNum == 64 )
  require(pmaNum >= 0)
  require(impPMPNum >= 0)
}


trait HasAnserParameters{
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

  //BackEnd
  def DecodeWidth = p.decodeWidth
  def RobIdxWidth = log2Ceil(p.numROBEntrys)

  //MMU
  def PageOffsetWidth = MMUConstant.PageOffsetWidth
  def vpnWidth = p.mmuParameters.svMode.Levels * MMUConstant.LevelVpnWidth
  def vaddrWidth = vpnWidth + PageOffsetWidth
  def ppnWidth = paddrWidth - PageOffsetWidth
  def Granularity = p.granularity  // 平台支持的颗粒度（pma pmp）对齐字节为2^(G+2)
  def GranularityBytes = 1 << (Granularity + 2)
  def GranularityWidth = Granularity + 2
  def AlignedPaddrWidth = paddrWidth - GranularityWidth
  def PMPCFGNum = (p.impPMPNum + 8 - 1) / 8
  def PMPAddrNum = p.impPMPNum
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