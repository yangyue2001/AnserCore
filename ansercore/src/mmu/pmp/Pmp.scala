package ansercore.mmu.pmp

import chisel3._
import chisel3.util._
import utils.Mux1H

class Pmp(
  numCheckPorts: Int = 1
) extends PmpModule with PmpHelpers{
  class PmpIO extends PmpBundle{
    val checkReq = Input(Vec(numCheckPorts, new PMPReq))
    val pmpResp = Output(Vec(numCheckPorts, new PMPResp))
  }

  val io = IO(new PmpIO)
  val checkReq = io.checkReq

  val pmpCfg = Seq.fill(realNumEntrys){RegInit(0.U.asTypeOf(new PmpCfgEntry))}
  val pmpAddr = Seq.fill(realNumEntrys){RegInit(0.U.asTypeOf(new PmpAddrEntry))}

  val pmpResp = Wire(Vec(numCheckPorts, new PMPResp))
  for(req <- (0 until numCheckPorts)){
    val reqAddr = checkReq(req).pAddr
    val reqPriv = checkReq(req).privMode
    val matchVec = Wire(Vec(realNumEntrys, Bool()))
    for(entry <- (0 until realNumEntrys)){
      val cfg = pmpCfg(entry)
      val addr = pmpAddr(entry).addr
      val prevAddr = if(entry == 0) 0.U else pmpAddr(entry-1).addr
      val mask = napotMask(addr)
      val napotMatch = isNapotMatch(reqAddr, addr, mask)
      val torMatch = isTorMatch(reqAddr, prevAddr, addr)
      matchVec(entry):= cfg.isNapot && napotMatch || cfg.isTor && torMatch

      assert(!cfg.isNa4, "cfg don't support na4")
    }

    val lowestHitIdxOH = PriorityEncoderOH(matchVec)
    val matchHIt = matchVec.reduce(_ || _)
    val hitPmp = Mux1H(lowestHitIdxOH, pmpCfg)
    val defaultResp = Wire(new PMPResp)
    defaultResp.allowFetch := true.B
    defaultResp.allowLoad := true.B
    defaultResp.allowStore := true.B
    //pmpResp(req):= Mux(matchHIt && (!reqPriv === MMode.U || hitPmp.l) ,new PMPResp, ??? )
  }

  io.pmpResp := pmpResp


}