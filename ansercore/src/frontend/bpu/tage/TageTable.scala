package ansercore.frontend.bpu.tage

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import utils.Mux1H
import utils.SinglePortSRAMTemplate
import utils.SaturateCounter

class TAGETable(implicit info: TAGETableInfo) extends TAGEModule with TAGEHelper {
  class TAGETableIO extends TAGEBundle {
    val readReq = Flipped(Valid(new TAGETableReadReq))
    val trainReadReq = Flipped(Valid(new TAGETableReadReq))
    val trainWriteReq = Flipped(Valid(new TAGETableWriteReq))
    val readResp = Output(new TAGETableResp)
    val trainReadResp = Output(new TAGETableResp)
    val resetDown = Output(Bool())
  }

  val io = IO(new TAGETableIO)

  val predictReadReqValid = io.readReq.valid
  val predictReadReq = io.readReq.bits
  val trainReadReqValid = io.trainReadReq.valid
  val trainReadReq = io.trainReadReq.bits
  val trainWriteReqValid = io.trainWriteReq.valid
  val trainWriteReq = io.trainWriteReq.bits

  val resetEntry = (new TAGETableEntry).Lit(
    _.ctr.value -> SaturateCounter.WeakPositive(TakenCtrWidth).U(TakenCtrWidth.W),
    _.tag -> 0.U(TagWidth.W),
    _.useful.value -> 0.U(UsefulCtrWidth.W),
  )

  val entrySram = Seq.tabulate(NumBanks, NumWays) { (_, _) =>
    Module(new SinglePortSRAMTemplate(
      gen = new TAGETableEntry,
      set = NumSets,
      shouldReset = true,
      holdRead = true,
      resetValue = Some(resetEntry),
    ))
  }

  // 写优先于训练读，训练读优先于预测读。外面已经按 bank 错开。
  (0 until NumBanks).foreach { bankIdx =>
    val trainWriteHit = bankSelected(trainWriteReqValid, trainWriteReq.bankMask, bankIdx)
    val trainReadHit = bankSelected(trainReadReqValid, trainReadReq.bankMask, bankIdx)
    val predictReadHit = bankSelected(predictReadReqValid, predictReadReq.bankMask, bankIdx)
    assert(!(trainWriteHit && trainReadHit), "tage train write and train read hit one bank")
    assert(!((trainWriteHit || trainReadHit) && predictReadHit), "tage train and predict hit one bank")

    (0 until NumWays).foreach { wayIdx =>
      val entry = entrySram(bankIdx)(wayIdx)
      val writeWay = if (NumWays == 1) trainWriteHit else trainWriteHit && trainWriteReq.wayOH.get(wayIdx)
      entry.io.en := writeWay || trainReadHit || predictReadHit
      entry.io.wen := writeWay
      entry.io.addr := utils.Mux1H(Seq(
        predictReadHit -> predictReadReq.setIdx,
        trainReadHit -> trainReadReq.setIdx,
        writeWay -> trainWriteReq.setIdx,
      ))
      entry.io.wdata := trainWriteReq.entry
    }
  }

  val readData = entrySram.map { bank =>
    bank.map(_.io.rdata)
  }

  if (NumWays == 1) {
    assignBankRead(Seq(io.readResp.entry.get), readData, predictReadReq.bankMask, predictReadReqValid)
    assignBankRead(Seq(io.trainReadResp.entry.get), readData, trainReadReq.bankMask, trainReadReqValid)
  } else {
    assignBankRead(io.readResp.entries.get, readData, predictReadReq.bankMask, predictReadReqValid)
    assignBankRead(io.trainReadResp.entries.get, readData, trainReadReq.bankMask, trainReadReqValid)
  }

  io.resetDown := entrySram(0)(0).io.resetDown.get
}

class TAGEBase extends TAGEModule with TAGEHelper {
  class TAGEBaseIO extends TAGEBundle {
    val readReq = Flipped(Valid(new BaseTableReadReq))
    val trainReadReq = Flipped(Valid(new BaseTableReadReq))
    val trainWriteReq = Flipped(Valid(new BaseTableWriteReq))
    val readResp = Output(new BaseTableResp)
    val trainReadResp = Output(new BaseTableResp)
    val resetDown = Output(Bool())
  }

  val io = IO(new TAGEBaseIO)

  val predictReadReqValid = io.readReq.valid
  val predictReadReq = io.readReq.bits
  val trainReadReqValid = io.trainReadReq.valid
  val trainReadReq = io.trainReadReq.bits
  val trainWriteReqValid = io.trainWriteReq.valid
  val trainWriteReq = io.trainWriteReq.bits

  val baseSram = Seq.tabulate(NumBanks, NumBr) { (_, _) =>
    Module(new SinglePortSRAMTemplate(
      gen = new SaturateCounter(BaseTakenCtrWidth),
      set = BaseTableSets,
      shouldReset = true,
      holdRead = true,
    ))
  }

  (0 until NumBanks).foreach { bankIdx =>
    val trainWriteHit = bankSelected(trainWriteReqValid, trainWriteReq.bankMask, bankIdx)
    val trainReadHit = bankSelected(trainReadReqValid, trainReadReq.bankMask, bankIdx)
    val predictReadHit = bankSelected(predictReadReqValid, predictReadReq.bankMask, bankIdx)
    assert(!(trainWriteHit && trainReadHit), "tage base write and train read hit one bank")
    assert(!((trainWriteHit || trainReadHit) && predictReadHit), "tage base train and predict hit one bank")

    (0 until NumBr).foreach { brIdx =>
      val base = baseSram(bankIdx)(brIdx)
      val writeBr = trainWriteHit && trainWriteReq.brMask(brIdx)
      base.io.en := writeBr || trainReadHit || predictReadHit
      base.io.wen := writeBr
      base.io.addr := utils.Mux1H(Seq(
        predictReadHit -> predictReadReq.setIdx,
        trainReadHit -> trainReadReq.setIdx,
        writeBr -> trainWriteReq.setIdx,
      ))
      base.io.wdata := trainWriteReq.entry(brIdx)
    }
  }

  val readData = baseSram.map { bank =>
    bank.map(_.io.rdata)
  }
  assignBankRead(io.readResp.entries, readData, predictReadReq.bankMask, predictReadReqValid)
  assignBankRead(io.trainReadResp.entries, readData, trainReadReq.bankMask, trainReadReqValid)
  io.resetDown := baseSram(0)(0).io.resetDown.get
}
