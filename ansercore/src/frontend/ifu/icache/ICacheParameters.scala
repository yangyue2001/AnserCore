package ansercore.frontend.ifu.icache

import chisel3._
import chisel3.util._

import ansercore.frontend.ifu.HasIFUParameters

case class ICacheParameters(
  //icache
  sizeKB: Int = 32,
  nWays: Int = 4,
  replacement: String = "plru",
  //mshr
  mshrSize: Int = 8,
  //waylookup
  wayLookupSize: Int = 16,
){
  require(nWays > 1)
  require(
    Seq("random", "lru", "plru").contains(replacement),
    "replacement must be random, lru, or plru",
  )
}

trait HasICacheParameters extends HasIFUParameters{
  def icacheParameters = ifuParameters.icache

  def TagNumBanks = ICacheConstant.TagNumBanks
  def DataNumBanks = ICacheConstant.DataNumBanks

  def numWays = icacheParameters.nWays
  def numSets = icacheParameters.sizeKB * 1024 / CachelineBytes / numWays
  def replacement = icacheParameters.replacement
  def perSetReplacement = replacement != "random"

  def SetIdxWidth = log2Ceil(numSets) // cacheline
  def WayIdxWidth = log2Ceil(numWays)
  def OffsetWidth = log2Ceil(CachelineBytes)
  def TagBankIdxWidth = log2Ceil(TagNumBanks)

  def VaddrAliasWidth =  math.max(0, OffsetWidth + SetIdxWidth - PageOffsetWidth)
  def HasVaddrAlias = VaddrAliasWidth > 0
  require(SetIdxWidth > VaddrAliasWidth)
  require(isPow2(numSets), "numSets must be a power of two")
  require(numSets % TagNumBanks == 0, "numSets must be divisible by TagNumBanks")

  def TagSetIdxWidth = SetIdxWidth - TagBankIdxWidth
  def DataSetIdxWidth = SetIdxWidth
  def tagWidth = paddrWidth - SetIdxWidth - OffsetWidth
  def DataWidth = CachelineBits / DataNumBanks
  def DataBankBytes = DataWidth / 8
  def DataBankOffWidth = log2Ceil(DataBankBytes)
  def DataBankIdxWidth = log2Ceil(DataNumBanks)
  def FetchByteWidth = log2Ceil(FrontendBlockBytes + 1)
  // 对齐时 24B 占 3 个 8B bank，不对齐会再多占 1 个。
  def FetchBankCount = (FrontendBlockBytes + DataBankBytes - 1) / DataBankBytes
  def FetchBankCountMax = FetchBankCount + 1

  require(TagNumBanks > 1)
  require(CachelineBits % DataNumBanks == 0, "data banks must cover a cacheline")


  //wayLookup
  def WayLookupSize = icacheParameters.wayLookupSize
  def WayLookupWidth = log2Ceil(WayLookupSize)

  //mshr
  def NumMshrs = icacheParameters.mshrSize
  def MshrIdxWidth = log2Ceil(NumMshrs)

}