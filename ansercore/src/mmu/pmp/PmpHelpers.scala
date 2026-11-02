package ansercore.mmu.pmp

import chisel3._
import chisel3.util._

trait PmpHelpers{
  this: HasPmpParameters =>

  def napotMask(pmpAddr: UInt): UInt = {
    require(pmpAddr.getWidth == pmpAddrWidth)
    pmpAddr ^ (pmpAddr + 1.U)
  }

  def isNapotMatch(paddr: UInt, pmpAddr: UInt, mask: UInt): Bool ={
    require(paddr.getWidth == paddrWidth)
    require(pmpAddr.getWidth == pmpAddrWidth)
    val addr = paddr(paddrWidth, pmpAlignWidth)
    val napotMatch = ((addr ^ pmpAddr) & ~mask ) === 0.U
    napotMatch
  } // 可以提前算好mask 做时序优化

  def isTorMatch(paddr: UInt, prevPmpAddr: UInt, pmpAddr: UInt): Bool={
    val addr = paddr(paddrWidth, pmpAlignWidth)
    val torMatch = (prevPmpAddr <= addr ) && (addr < pmpAddr)
    torMatch
  }
}