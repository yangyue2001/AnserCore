package ansercore.mmu.ptw

import chisel3._
import chisel3.util._
import ansercore.mmu.MMUConstant.PpnWidth

trait PtwHelpers {
  this: HasPtwParameters =>

  def isLeafPte(pteFlags: FlagEntry): Bool = {
    pteFlags.r || pteFlags.x
  }

  //pte 中的ppn 超过了物理地址范围
  def isOutOfRangPa(ptePpn: UInt): Bool = {
    require(ptePpn.getWidth == PpnWidth)
    ptePpn(PpnWidth, ppnWidth).orR
  }

  def isMisalignedSuperPage(ptePpn: UInt, nowLevel: UInt): Bool = {
    require(ptePpn.getWidth == PpnWidth)
    val cases = (1 until levels).map{ l =>
      (l-1).U -> ptePpn(9*l -1,0).orR
    }
    MuxLookup(nowLevel, false.B)(cases)
  }

  def isIllegalPte(pteFlags: FlagEntry): Bool = {
    !pteFlags.v || (!pteFlags.r && pteFlags.w)
  }
}