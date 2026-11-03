package ansercore.frontend.bpu.tage

import chisel3._
import chisel3.util._
import chisel3.util.random.LFSR

import utils.LowPriorityOH
import utils.SaturateCounter
import utils.Mux1H
import utils.HighMask

class TAGE extends TAGEModule with TAGEHelper {
  class TAGEIO extends TAGEBundle {
    val tageTrain = Flipped(Decoupled(new TAGETrain))
    val predictReq = Flipped(Decoupled(new TAGEPredReq))
    val s1Fire = Input(Bool())
    val s2PHR = Input(new TAGEAllFoldedHistories)
    val s2PC = Input(UInt(pcWidth.W))
    val s2Offset = Input(Vec(NumBr, UInt(FetchBlockOffsetWidth.W)))
    val meta = Output(Vec(NumBr, new TAGEMeta))
    val prediction = Output(Vec(NumBr, Bool()))
    val resetDown = Output(Bool())
  }

  val io = IO(new TAGEIO)

  val predictFire = io.predictReq.fire
  val predict = io.predictReq.bits
  val s1Fire = io.s1Fire
  val s2PC = io.s2PC
  val s2Hist = io.s2PHR
  val s2Offset = io.s2Offset

  val tables = TableInfos.map { tableInfo =>
    implicit val info = tableInfo
    Module(new TAGETable)
  }
  val baseTable = Module(new TAGEBase)
  val resetDown = tables.map(_.io.resetDown).reduce(_ && _) && baseTable.io.resetDown

  // 复位 0。最高位为 1 才改用 alt。
  val useAltOnNaVec = RegInit(VecInit(Seq.fill(NumUseAltOnNa) {
    0.U(UseAltOnNaWidth.W).asTypeOf(new SaturateCounter(UseAltOnNaWidth))
  }))

//----------------------predict s0------------------------------------//
  val readBankMask = getBankMask(predict.pc)

  tables.zipWithIndex.foreach { case (table, tableIdx) =>
    implicit val info = TableInfos(tableIdx)
    table.io.readReq.valid := predictFire
    table.io.readReq.bits.bankMask := readBankMask
    table.io.readReq.bits.setIdx := getSetIndex(predict.pc, predict.predPHR.hist(tableIdx))
  }
  baseTable.io.readReq.valid := predictFire
  baseTable.io.readReq.bits.bankMask := readBankMask
  baseTable.io.readReq.bits.setIdx := getBaseSetIndex(predict.pc)

//----------------------predict s1------------------------------------//
  // s1 是 SRAM 读出。s1Fire 才把这拍读数打进 s2。
  val s1Table = tables.map(_.io.readResp)
  val s1Base = baseTable.io.readResp.entries

//----------------------predict s2------------------------------------//
  val s2Table = s1Table.map { table =>
    RegEnable(table, s1Fire)
  }
  val s2Base = RegEnable(s1Base, s1Fire)

  class ResolvedTables(
    val tableWayHit: Seq[Seq[Bool]],
    val wayOH: Option[Seq[UInt]],
    val providerOH: UInt,
    val providerValid: Bool,
    val providerEntry: TAGETableEntry,
    val altValid: Bool,
    val altEntry: TAGETableEntry,
  )

  private def seqToUInt(bits: Seq[Bool]): UInt = {
    val packed = VecInit(bits).asUInt
    require(packed.getWidth == bits.length)
    packed
  }

  private def highestTableOH(tableHit: Seq[Bool]): UInt = {
    seqToUInt(LowPriorityOH(tableHit.reverse).reverse)
  }

  private def lowestWayOH(wayHit: Seq[Bool]): UInt = {
    require(wayHit.length > 1, "1 way does not pick a way")
    seqToUInt(LowPriorityOH(wayHit))
  }

  // 和 ITTAGE 同一套。两个槽只是 tag 多异或自己的 offset，provider 和 alt 只选一次。
  private def resolve(
    resps: Seq[TAGETableResp],
    pc: UInt,
    phr: TAGEAllFoldedHistories,
    offset: UInt,
  ): ResolvedTables = {
    // tag 相等即命中，复位后的 tag 0 也算命中。
    val tableWayHit = TableInfos.zipWithIndex.map { case (tableInfo, tableIdx) =>
      implicit val info = tableInfo
      val tag = getTag(pc, phr.hist(tableIdx), offset)
      require(tag.getWidth == TagWidth)
      val entries = if (NumWays == 1) Seq(resps(tableIdx).entry.get) else resps(tableIdx).entries.get
      entries.map { entry =>
        require(entry.tag.getWidth == tag.getWidth)
        entry.tag === tag
      }
    }
    val wayOH = if (NumWays == 1) None else Some(tableWayHit.map(seqToUInt))
    val tableHit = tableWayHit.map { hit =>
      if (NumWays == 1) hit.head else hit.reduce(_ || _)
    }

    def pick(tableOH: UInt): TAGETableEntry = {
      if (NumWays == 1) {
        Mux1H(tableOH, resps.map(_.entry.get))
      } else {
        val waySel = Mux1H(tableOH, wayOH.get)
        val ways = Mux1H(tableOH, resps.map(_.entries.get))
        Mux1H(waySel, ways)
      }
    }

    val providerOH = highestTableOH(tableHit)
    val altTableHit = tableHit.zip(providerOH.asBools).map { case (hit, isProvider) =>
      hit && !isProvider
    }
    new ResolvedTables(
      tableWayHit,
      wayOH,
      providerOH,
      tableHit.reduce(_ || _),
      pick(providerOH),
      altTableHit.reduce(_ || _),
      pick(highestTableOH(altTableHit)),
    )
  }

  private val brResolved = (0 until NumBr).map { brIdx =>
    resolve(s2Table, s2PC, s2Hist, s2Offset(brIdx))
  }

  val useAltOnNaIdx = s2PC(UseAltOnNaIdxWidth - 1, 0)
  require(useAltOnNaIdx.getWidth == UseAltOnNaIdxWidth)
  val useAltOnNa = useAltOnNaVec(useAltOnNaIdx)
  val useAltOnNaSelectAlt = useAltOnNa.isPositive

  val tageMeta = Wire(Vec(NumBr, new TAGEMeta))
  val prediction = Wire(Vec(NumBr, Bool()))

  (0 until NumBr).foreach { brIdx =>
    val resolved = brResolved(brIdx)
    val providerValid = resolved.providerValid
    val providerEntry = resolved.providerEntry
    val providerCtr = providerEntry.ctr
    val useAlt = providerCtr.isWeak && useAltOnNaSelectAlt
    val baseTaken = s2Base(brIdx).isPositive
    val providerTaken = providerCtr.isPositive
    val altTaken = resolved.altEntry.ctr.isPositive

    prediction(brIdx) := Mux(!providerValid, baseTaken,
      Mux(useAlt, Mux(resolved.altValid, altTaken, baseTaken), providerTaken))

    val meta = tageMeta(brIdx)
    meta.providerValid := providerValid
    meta.providerTableIdx := OHToUInt(resolved.providerOH)
    if (NumWays > 1) {
      val providerWayOH = Mux1H(resolved.providerOH, resolved.wayOH.get)
      meta.providerTableWayIdx.get := OHToUInt(providerWayOH)
    }
    meta.providerCtr.value := providerEntry.ctr.value
    meta.providerUseful.value := providerEntry.useful.value
    // 没有 alt 表时，另一侧就是 base。t0 用这个决定 useful 加不加。
    meta.diff := providerValid && (providerTaken =/= Mux(resolved.altValid, altTaken, baseTaken))
    meta.baseCtr.value := s2Base(brIdx).value
  }


  io.prediction := prediction
  io.meta := tageMeta
  io.resetDown := resetDown
  io.predictReq.ready := true.B
  // 训练这次不接，端口保持空闲。
  io.tageTrain.ready := true.B
  tables.foreach { table =>
    table.io.trainReadReq.valid := false.B
    table.io.trainReadReq.bits := 0.U.asTypeOf(table.io.trainReadReq.bits)
    table.io.trainWriteReq.valid := false.B
    table.io.trainWriteReq.bits := 0.U.asTypeOf(table.io.trainWriteReq.bits)
  }
  baseTable.io.trainReadReq.valid := false.B
  baseTable.io.trainReadReq.bits := 0.U.asTypeOf(baseTable.io.trainReadReq.bits)
  baseTable.io.trainWriteReq.valid := false.B
  baseTable.io.trainWriteReq.bits := 0.U.asTypeOf(baseTable.io.trainWriteReq.bits)
}
