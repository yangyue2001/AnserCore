package ansercore.frontend.bpu.ittage

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import utils.Mux1H
import utils.SinglePortSRAMTemplate
import ansercore.frontend.bpu.tage.TAGETableInfo

class ITTAGETable(implicit info: TAGETableInfo) extends ITTAGEModule with ITTAGEHelper {
  class ITTAGETableIO extends ITTAGEBundle {
    val readReq = Flipped(Valid(new ITTAGETableReadReq))
    val trainReadReq = Flipped(Valid(new ITTAGETableReadReq))
    val trainWriteReq = Flipped(Valid(new ITTAGETableWriteReq))
    val readResp = Output(new ITTAGETableResp)
    val trainReadResp = Output(new ITTAGETableResp)
    val resetDown = Output(Bool())
  }

  val io = IO(new ITTAGETableIO)

  val predictReadReqValid = io.readReq.valid
  val predictReadReq = io.readReq.bits
  val trainReadReqValid = io.trainReadReq.valid
  val trainReadReq = io.trainReadReq.bits
  val trainWriteReqValid = io.trainWriteReq.valid
  val trainWriteReq = io.trainWriteReq.bits

  val resetEntry = (new ITTAGEEntry).Lit(
    _.ctr.value -> 0.U(ConfidenceCtrWidth.W),
    _.tag -> 0.U(TagWidth.W),
    _.pointer -> 0.U(RegionIdxWidth.W),
    _.useful.value -> 0.U(UsefulCtrWidth.W),
    _.offset -> 0.U(TargetOffsetWidth.W),
    _.usePCRegion -> false.B,
  )

  val entrySram = Seq.tabulate(NumBanks, NumWays) { (_, _) =>
    Module(new SinglePortSRAMTemplate(
      gen = new ITTAGEEntry,
      set = NumSets,
      shouldReset = true,
      holdRead = true,
      resetValue = Some(resetEntry),
    ))
  }

  // 写优先于训练读，训练读优先于预测读。进 ITTAGETable 之前先做成互斥。
  (0 until NumBanks).foreach { bankIdx =>
    val trainWriteHit = bankSelected(trainWriteReqValid, trainWriteReq.bankMask, bankIdx)
    val trainRead = bankSelected(trainReadReqValid, trainReadReq.bankMask, bankIdx)
    val predictRead = bankSelected(predictReadReqValid, predictReadReq.bankMask, bankIdx)

    (0 until NumWays).foreach { wayIdx =>
      val entry = entrySram(bankIdx)(wayIdx)
      val writeValid = if (NumWays == 1) trainWriteHit else trainWriteHit && trainWriteReq.wayOH.get(wayIdx)
      entry.io.en := writeValid || trainRead || predictRead
      entry.io.wen := writeValid
      entry.io.addr := Mux1H(Seq(
        predictRead -> predictReadReq.setIdx,
        trainRead -> trainReadReq.setIdx,
        writeValid -> trainWriteReq.setIdx,
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
