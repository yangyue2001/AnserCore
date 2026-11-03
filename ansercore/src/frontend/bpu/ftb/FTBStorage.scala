package ansercore.frontend.bpu.ftb

import chisel3._
import chisel3.util._
import utils.Mux1H
import utils.SinglePortSRAMTemplate

class FTBStorage extends FTBModule with FTBHelper {
  class FTBStorageIO extends FTBBundle {
    val readReq = Flipped(Valid(new FTBReadReq))
    val trainReadReq = Flipped(Valid(new FTBReadReq))
    val writeReq = Flipped(Valid(new FTBWriteReq))
    val readResp = Output(new FTBStorageResp)
    val trainReadResp = Output(new FTBStorageResp)
    val resetDown = Output(Bool())
  }

  val io = IO(new FTBStorageIO)

  val readValid = io.readReq.valid
  val readReq = io.readReq.bits
  val trainReadValid = io.trainReadReq.valid
  val trainReadReq = io.trainReadReq.bits
  val writeValid = io.writeReq.valid
  val writeReq = io.writeReq.bits

  val entrySram = Seq.tabulate(NumBanks, NumWays) { (_, _) =>
    Module(new SinglePortSRAMTemplate(
      gen = new FTBEntry,
      set = NumSets,
      shouldReset = true,
      holdRead = true,
    ))
  }

  // 同一块 SRAM：写优先于训练读，训练读优先于预测读。不同 bank 可以同时进行。
  (0 until NumBanks).foreach { bankIdx =>
    val writeBank = bankSelected(writeValid, writeReq.bankMask, bankIdx)
    val trainBank = bankSelected(trainReadValid, trainReadReq.bankMask, bankIdx)
    val predictBank = bankSelected(readValid, readReq.bankMask, bankIdx)
    val setIdx = Mux1H(Seq(
      writeBank -> writeReq.setIdx,
      trainBank -> trainReadReq.setIdx,
      predictBank -> readReq.setIdx
    ))

    (0 until NumWays).foreach { wayIdx =>
      val entry = entrySram(bankIdx)(wayIdx)
      val writeWay = if (NumWays == 1) writeBank else writeBank && writeReq.wayOH.get(wayIdx)
      entry.io.en := writeWay || trainBank || predictBank
      entry.io.wen := writeWay
      entry.io.addr := setIdx
      entry.io.wdata := writeReq.entry
    }
  }

  val readData = entrySram.map { bank =>
    bank.map(_.io.rdata)
  }

  if (NumWays == 1) {
    val predictEntries = readData.map(bank => Seq(bank.head))
    assignBankRead(Seq(io.readResp.entry.get), predictEntries, readReq.bankMask, readValid)
    assignBankRead(Seq(io.trainReadResp.entry.get), predictEntries, trainReadReq.bankMask, trainReadValid)
  } else {
    assignBankRead(io.readResp.entries.get, readData, readReq.bankMask, readValid)
    assignBankRead(io.trainReadResp.entries.get, readData, trainReadReq.bankMask, trainReadValid)
  }

  io.resetDown := entrySram(0)(0).io.resetDown.get
}
