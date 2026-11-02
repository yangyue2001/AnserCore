package ansercore.mmu.pmp

import chisel3._
import chisel3.util._
import ansercore.PrivMode

class PmpAddrEntry extends PmpBundle{
  val addr = UInt(pmpAddrWidth.W)
}

class PmpCfgEntry extends PmpBundle{
  val r = Bool()
  val w = Bool()
  val x = Bool()
  val a = PmpCfgA()
  val l = Bool()

  def isOff = this.a === PmpCfgA.off
  def isTor = this.a === PmpCfgA.tor
  def isNa4 = this.a === PmpCfgA.na4
  def isNapot = this.a === PmpCfgA.napot
}

class PMPReq extends PmpBundle{
  val pAddr = UInt(paddrWidth.W)
  val privMode = PrivMode()
}

class PMPResp extends PmpBundle{
  val allowFetch = Bool()
  val allowStore = Bool()
  val allowLoad = Bool()
}


object PmpCfgA extends ChiselEnum{
  val off, tor, na4 , napot = Value
}