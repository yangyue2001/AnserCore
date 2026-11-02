package ansercore.frontend.ifu.predecode

import chisel3._
import chisel3.util._
import utils.SignExt

import ansercore.frontend.ifu.HasIFUParameters

trait PreDecodeHelper extends HasIFUParameters {
  def isRVC(inst: UInt): Bool = inst(1, 0) =/= 3.U

  def getJalOffset(inst: UInt, isRvc: Bool): UInt = {
    val rvcOffset = Cat(inst(12), inst(8), inst(10, 9), inst(6), inst(7), inst(2), inst(11), inst(5, 3)) // 11 width imm
    val rviOffset = Cat(inst(31), inst(19, 12), inst(20), inst(30, 21)) // 20 width imm

    require(rvcOffset.getWidth == 11)
    require(rviOffset.getWidth == 20)
    SignExt(Mux(isRvc, SignExt(rvcOffset, 20), rviOffset), pcWidth)
  }

  def getBrOffset(inst: UInt, isRvc: Bool): UInt = {
    val rvcOffset = Cat(inst(12), inst(6, 5), inst(2), inst(11, 10), inst(4, 3)) // 8 width imm
    val rviOffset = Cat(inst(31), inst(7), inst(30, 25), inst(11, 8)) // 12 width imm

    require(rvcOffset.getWidth == 8 )
    require(rviOffset.getWidth == 12)
    SignExt(Mux(isRvc, SignExt(rvcOffset, 12), rviOffset), pcWidth)
  }

  def getCFIType(inst: UInt, isRvc: Bool): PreDecodeCFIAttribute = {
    //rvi
    def isLink(reg: UInt): Bool = reg === 1.U || reg === 5.U

    val rviOpcode = inst(6, 2)
    val rviFunct3 = inst(14,12)
    val rviRd = inst(11, 7)
    val rviRs1 = inst(19, 15)
    val rviConditional = (rviOpcode === "b11000".U(5.W)) && (rviFunct3 =/= "b010".U && rviFunct3 =/= "b011".U)
    val rviDirect = rviOpcode === "b11011".U(5.W)
    val rviIndirect = (rviOpcode === "b11001".U(5.W)) && (rviFunct3 === "b000".U(3.W))

    val rviCall = (rviIndirect || rviDirect) && isLink(rviRd)
    val rviRet = rviIndirect && isLink(rviRs1) && (rviRd =/= rviRs1)
    //rvc
    val rvcOpcode = inst(1, 0)
    //cj
    val rvcCJFunct3 = inst(15, 13)
    val rvcDirect = (rvcOpcode === "b01".U(2.W)) && (rvcCJFunct3 === "b101".U(3.W))
    // cr
    val rvcCRFunct4 = inst(15, 12)
    val rvcCRRs1 = inst(11, 7)
    val rvcCRRs2 = inst(6, 2)
    val rvcIndirect = (rvcOpcode === "b10".U(2.W)) && (rvcCRFunct4(3,1) === "b100".U(3.W)) &&
    rvcCRRs1 =/= 0.U(5.W) && rvcCRRs2 === 0.U(5.W)
    // cb
    val rvcCBFunct3 = inst(15, 13)
    val rvcConditional = (rvcOpcode === "b01".U(2.W)) && (rvcCBFunct3(2,1) === "b11".U(2.W))

    val rvcCall = rvcIndirect && rvcCRFunct4(0)
    val rvcRet = rvcIndirect && (rvcCRFunct4(0) && rvcCRRs1 === 5.U || !rvcCRFunct4(0) && isLink(rvcCRRs1) )


    val cfiAttribute = Wire(new PreDecodeCFIAttribute)
    cfiAttribute.branch := Mux(isRvc, rvcConditional, rviConditional)
    cfiAttribute.jal    := Mux(isRvc, rvcDirect, rviDirect)
    cfiAttribute.jalr   := Mux(isRvc, rvcIndirect, rviIndirect)
    cfiAttribute.call   := Mux(isRvc, rvcCall, rviCall)
    cfiAttribute.ret    := Mux(isRvc, rvcRet, rviRet)

    cfiAttribute
  }
}