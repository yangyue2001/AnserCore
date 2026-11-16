package ansercore.frontend.ifu.icache

import chisel3._
import chisel3.util._

trait ICacheHelper{
  this: HasICacheParameters =>

  def getTagBankIndex(setIdx: UInt): UInt = {
    require(setIdx.getWidth == SetIdxWidth)
    setIdx(TagBankIdxWidth-1, 0)
  }

  def getTag(pAddr: UInt): UInt = {
    require(pAddr.getWidth == paddrWidth)
    pAddr(paddrWidth-1, SetIdxWidth)
  }

  def getSetIndex(vAddr: UInt): UInt = {
    require(vAddr.getWidth == vaddrWidth)
    vAddr(SetIdxWidth+OffsetWidth-1, OffsetWidth)
  }

  def getTagSetIndex(setIdx: UInt): UInt = {
    require(setIdx.getWidth == SetIdxWidth)

    setIdx(SetIdxWidth-1, TagBankIdxWidth)
  }

  def isCrossCacheline(vAddr: UInt): Bool = {
    require(vAddr.getWidth == vaddrWidth)
    val cachelineOffset = vAddr(OffsetWidth-1,1)
    cachelineOffset > ((CachelineBytes - FrontendBlockBytes)/MinInstBytes).U
  }

  def getBeatVaddr(vAddr: UInt): UInt = {
    require(vAddr.getWidth == vaddrWidth)
    vAddr(vaddrWidth - 1, ICacheToL2AddrOffsetWidth)
  }

  // cacheline 偏移里按 L2 beat 对齐的高位。64B line、32B 传输时是 vAddr[5]，1 位。
  def getBeatOffset(vAddr: UInt): UInt = {
    require(vAddr.getWidth == vaddrWidth)
    val beat = vAddr(OffsetWidth - 1, ICacheToL2AddrOffsetWidth)
    require(beat.getWidth == ICacheToL2BeatWidth)
    beat
  }

  // 页内偏移只留 set 的页内部分和 beat 位，beat 以下补 0。
  def beatPageOffset(setIdx: UInt, beatOff: UInt): UInt = {
    require(setIdx.getWidth == SetIdxWidth)
    require(beatOff.getWidth == ICacheToL2BeatWidth)
    val setInPage = if (HasVaddrAlias) {
      setIdx(SetIdxWidth - VaddrAliasWidth - 1, 0)
    } else {
      setIdx
    }
    val offset = Cat(setInPage, beatOff, 0.U(ICacheToL2AddrOffsetWidth.W))
    require(offset.getWidth == PageOffsetWidth)
    offset
  }

  def getVPN(vAddr: UInt): UInt = {
    require(vAddr.getWidth == vaddrWidth)
    vAddr(vaddrWidth - 1, PageOffsetWidth)
  }

  def lineAddr(beatPaddr: UInt): UInt = {
    require(beatPaddr.getWidth == ICacheToL2BeatAddrWidth)
    beatPaddr(ICacheToL2BeatAddrWidth - 1, ICacheToL2BeatWidth)
  }
}
