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
  // 24B 窗口落在哪些 8B bank 上。跨页时截到本条 line 的结尾，不再看下一条。
  def fetchWindow(vAddr: UInt, crossPage: Bool): ICacheFetchWindow = {
    require(vAddr.getWidth == vaddrWidth)
    val out = Wire(new ICacheFetchWindow)
    val lineOff = vAddr(OffsetWidth - 1, 0)
    val wide = OffsetWidth + 1
    val off = Cat(0.U(1.W), lineOff)
    val need = FrontendBlockBytes.U(wide.W)
    val lineEnd = CachelineBytes.U(wide.W)
    val lineLeft = lineEnd - off
    val cross = !crossPage && (off + need) > lineEnd
    val bytes0 = Mux(cross || crossPage, lineLeft, need)
    val bytes1 = Mux(cross, off + need - lineEnd, 0.U(wide.W))

    def bankMask(start: UInt, nbytes: UInt): UInt = {
      val last = (start +& nbytes - 1.U)(wide - 1, 0)
      val firstBank = start(OffsetWidth - 1, DataBankOffWidth)
      val lastBank = last(OffsetWidth - 1, DataBankOffWidth)
      VecInit((0 until DataNumBanks).map { i =>
        val bank = i.U(DataBankIdxWidth.W)
        nbytes =/= 0.U && bank >= firstBank && bank <= lastBank
      }).asUInt
    }

    val mask0 = bankMask(off, bytes0)
    val mask1 = bankMask(0.U(wide.W), bytes1)
    val banks = PopCount(mask0) + PopCount(mask1)
    when (!crossPage) {
      assert(
        banks === FetchBankCount.U || banks === FetchBankCountMax.U,
        "完整 fetch 窗口应该落在 3 或 4 个 bank 上",
      )
    }.otherwise {
      assert(banks >= 1.U && banks <= FetchBankCountMax.U, "跨页 fetch 的 bank 数不对")
    }
    assert((mask0 & mask1) === 0.U, "两条 line 的 data bank 不能重叠")

    val got = bytes0 +& bytes1
    out.crossCacheline := cross
    out.bankMask(0) := mask0
    out.bankMask(1) := mask1
    out.lineBytes(0) := bytes0(FetchByteWidth - 1, 0)
    out.lineBytes(1) := bytes1(FetchByteWidth - 1, 0)
    out.lineOff := lineOff
    out.endOffset := (got(wide - 1, InstOffsetWidth) - 1.U)(FetchBlockOffsetWidth - 1, 0)
    out
  }
}
