package ansercore.frontend.bpu.ittage

import chisel3._
import chisel3.util._
import chisel3.util.random.LFSR

import utils.ReplacementPolicy
import utils.SaturateCounter
import utils.Mux1H
import utils.LowPriorityOH
import utils.HighMask

class ITTAGE extends ITTAGEModule with ITTAGEHelper {
  class ITTAGEIO extends ITTAGEBundle {
    val resetDown = Output(Bool())
    val predictReq = Flipped(Decoupled(new ITTAGEPredReq))
    val s2Fire = Input(Bool())
    val s3PHR = Input(new ITTAGEAllFoldedHistories)
    val s3PC = Input(UInt(pcWidth.W))
    val meta = Output(new ITTAGEMeta)
    val prediction = Output(new ITTAGEPrediction)
    val ittageTrain = Flipped(Decoupled(new ITTAGETrain))
  }

  val io = IO(new ITTAGEIO)

  val predict = io.predictReq.bits
  val s2Fire = io.s2Fire
  val s3PC = io.s3PC
  val s3PHR = io.s3PHR
  val trainValid = io.ittageTrain.valid
  val train = io.ittageTrain.bits

  val tables = TableInfos.map { tableInfo =>
    implicit val info = tableInfo
    Module(new ITTAGETable)
  }
  // 复位 0。最高位为 1 才改用 alt。
  val useAltOnNaVec = RegInit(VecInit(Seq.fill(NumUseAltOnNa) {
    0.U(UseAltOnNaWidth.W).asTypeOf(new SaturateCounter(UseAltOnNaWidth))
  }))
  val regions = RegInit(VecInit.fill(RegionNums) {
    0.U.asTypeOf(new RegionEntry)
  })

  val regionReplacer = ReplacementPolicy(RegionReplacement, RegionNums)
  require(NumTables > 0 && NumTables <= 15, "allocation LFSR mask is 15 bit")

  private class ResolvedTables(
    val tags: Seq[UInt],
    val tableWayHit: Seq[Seq[Bool]],
    val wayOH: Option[Seq[UInt]],
    val providerOH: UInt,
    val providerValid: Bool,
    val providerEntry: ITTAGEEntry,
    val altValid: Bool,
    val altEntry: ITTAGEEntry,
  )

  private def seqToUInt(bits: Seq[Bool]): UInt = {
    val packed = VecInit(bits).asUInt
    require(packed.getWidth == bits.length)
    packed
  }

  private def lowestWayOH(wayHit: Seq[Bool]): UInt = {
    require(wayHit.length > 1, "1 way does not need a way select")
    seqToUInt(LowPriorityOH(wayHit))
  }

  private def highestTableOH(tableHit: Seq[Bool]): UInt = {
    seqToUInt(LowPriorityOH(tableHit.reverse).reverse)
  }


  private def resolve(
    resps: Seq[ITTAGETableResp],
    pc: UInt,
    phr: ITTAGEAllFoldedHistories,
  ): ResolvedTables = {
    val tags = TableInfos.zipWithIndex.map { case (tableInfo, tableIdx) =>
      implicit val info = tableInfo
      val tag = getTag(pc, phr.hist(tableIdx))
      require(tag.getWidth == TagWidth)
      tag
    }
    // tag 相等即命中。 不存在多way 命中
    val tableWayHit = tags.zip(resps).map { case (tag, resp) =>
      val entries = if (NumWays == 1) Seq(resp.entry.get) else resp.entries.get
      entries.map { entry =>
        require(entry.tag.getWidth == tag.getWidth)
        entry.tag === tag
      }
    }
    val wayOH = if (NumWays == 1) None else Some(tableWayHit.map(seqToUInt))
    val tableHit = tableWayHit.map { hit =>
      if (NumWays == 1) hit.head else hit.reduce(_ || _)
    }

    def pick(tableOH: UInt): ITTAGEEntry = {
      if (NumWays == 1) {
        Mux1H(tableOH, resps.map(_.entry.get))
      } else {
        val waySel = Mux1H(tableOH, wayOH.get)
        val ways = Mux1H(tableOH, resps.map(_.entries.get))
        Mux1H(waySel, ways)
      }
    }

    val providerOH = highestTableOH(tableHit)
    val providerValid = tableHit.reduce(_ || _)
    val altTableHit = tableHit.zip(providerOH.asBools).map { case (hit, isProvider) =>
      hit && !isProvider
    }
    new ResolvedTables(
      tags,
      tableWayHit,
      wayOH,
      providerOH,
      providerValid,
      pick(providerOH),
      altTableHit.reduce(_ || _),
      pick(highestTableOH(altTableHit)),
    )
  }

  private def targetOf(entry: ITTAGEEntry, pc: UInt): UInt = {
    val pcRegion = getRegion(pc)
    require(entry.offset.getWidth == TargetOffsetWidth)
    require(entry.pointer.getWidth == RegionIdxWidth)
    // usePCRegion 为 1 时 region 取 pc 高位，否则用 pointer 读 region 表，再拼在 offset 上面。
    val region = Wire(UInt(RegionWidth.W))
    region := Mux(entry.usePCRegion, pcRegion, regions(entry.pointer).region)
    val target = Wire(UInt(pcWidth.W))
    target := Cat(region, entry.offset)
    target
  }

// 预测要看训练是否占着同一个 bank。
  val trainWriteValid = Wire(Bool())
  val trainWriteBank = if (NumBanks == 1) None else Some(Wire(UInt(NumBanks.W)))
  val trainReadValid = Wire(Bool())
  val trainReadBank = Wire(UInt(NumBanks.W))

//----------------------predict s0------------------------------------//
  val predictBank = getBankMask(predict.pc)
  val trainWriteBlocksPredict = trainWriteBank match {
    case None => trainWriteValid
    case Some(writeBank) => trainWriteValid && (writeBank & predictBank).orR
  }
  val trainReadBlocksPredict = trainWriteBank match {
    case None => trainReadValid
    case Some(_) => trainReadValid && (trainReadBank & predictBank).orR
  }
  val resetDown = tables.map(_.io.resetDown).reduce(_ && _)
  val predictFire = io.predictReq.fire

  tables.zipWithIndex.foreach { case (table, tableIdx) =>
    implicit val info = TableInfos(tableIdx)
    table.io.readReq.valid := predictFire
    table.io.readReq.bits.bankMask := predictBank
    table.io.readReq.bits.setIdx := getSetIndex(predict.pc, predict.predPHR.hist(tableIdx))

    table.io.trainReadReq.valid := trainReadValid
    table.io.trainReadReq.bits.bankMask := trainReadBank
    table.io.trainReadReq.bits.setIdx := getSetIndex(train.trainPC, train.trainPHR.hist(tableIdx))
  }

//----------------------predict s1------------------------------------//
  // s1 是 SRAM 读出。s2Fire 才把这拍读数打进 s2。
  val s1Table = tables.map(_.io.readResp)

//----------------------predict s2------------------------------------//
  val s2Table = s1Table.map { table =>
    RegEnable(table, s2Fire)
  }
  private[this] val predictResolved = resolve(s2Table, s3PC, s3PHR)

  val useAltOnNaIdx = s3PC(UseAltOnNaIdxWidth - 1, 0)
  require(useAltOnNaIdx.getWidth == UseAltOnNaIdxWidth)
  val useAltOnNa = useAltOnNaVec(useAltOnNaIdx)
  val useAltOnNaSelectAlt = useAltOnNa.isPositive
  val useAlt = predictResolved.providerEntry.ctr.isSaturateNegative && useAltOnNaSelectAlt

  val prediction = Wire(new ITTAGEPrediction)
  prediction.taken := predictResolved.providerValid && (!useAlt || predictResolved.altValid)
  prediction.target := Mux(
    useAlt,
    targetOf(predictResolved.altEntry, s3PC),
    targetOf(predictResolved.providerEntry, s3PC),
  )

  // 到顶才算饱和。两个都到顶时，预测正确不再打开训练。没有 provider 则还要更新。
  val meta = Wire(new ITTAGEMeta)
  meta.needUpdate := predictResolved.providerValid && (
    !predictResolved.providerEntry.ctr.isSaturatePositive ||
    !predictResolved.providerEntry.useful.isSaturatePositive )


//----------------------train s0--------------------------------------//
  // 只有间接跳转且不是 return 才训练。预测错一定开，预测对看 meta。
  val needITTAGE = train.endCFIAttribute.needITTAGE
  val openTrain = train.mispredict || train.meta.needUpdate
  val trainReadWant = trainValid && needITTAGE && openTrain
  trainReadBank := getBankMask(train.trainPC)
  val trainReadBlocked = trainWriteBank match {
    case None => trainWriteValid
    case Some(writeBank) => trainWriteValid && (writeBank & trainReadBank).orR
  }
  trainReadValid := trainReadWant && !trainReadBlocked


//----------------------train s1--------------------------------------//
  val s1TrainValid = RegNext(trainReadValid, false.B)
  val s1TrainPC = RegEnable(train.trainPC, trainReadValid)
  val s1TrainPHR = RegEnable(train.trainPHR, trainReadValid)
  val s1Mispredict = RegEnable(train.mispredict, trainReadValid)
  val s1Target = RegEnable(train.target, trainReadValid)
  val s1TrainResp = tables.map(_.io.trainReadResp)

//----------------------train s2--------------------------------------//
  val s2TrainValid = RegNext(s1TrainValid, false.B)
  val s2TrainPC = RegEnable(s1TrainPC, s1TrainValid)
  val s2TrainPHR = RegEnable(s1TrainPHR, s1TrainValid)
  val s2Mispredict = RegEnable(s1Mispredict, s1TrainValid)
  val s2Target = RegEnable(s1Target, s1TrainValid)
  val s2TrainResp = s1TrainResp.map { resp =>
    RegEnable(resp, s1TrainValid)
  }
  private[this] val trainResolved = resolve(s2TrainResp, s2TrainPC, s2TrainPHR)

  val providerTarget = targetOf(trainResolved.providerEntry, s2TrainPC)
  val altTarget = targetOf(trainResolved.altEntry, s2TrainPC)

  val s2Provider = trainResolved.providerValid
  val s2Alt = trainResolved.altValid
  val providerRight = providerTarget === s2Target
  val providerWrong = providerTarget =/= s2Target
  val altRight = altTarget === s2Target

  val oldCtr = trainResolved.providerEntry.ctr
  val oldUseful = trainResolved.providerEntry.useful
  val ctrNext = Mux(providerRight, oldCtr + 1, oldCtr - 1)
  val usefulUp = providerRight &&  !(s2Alt && altRight)
  val usefulDown = providerWrong && (s2Alt && altRight) && oldCtr.isSaturateNegative
  val usefulNext = Mux(usefulUp, oldUseful + 1, oldUseful - 1)
  // 加到顶或减到 0 的字段不改。整条都没变就不发写。
  val ctrWrite =Mux(
    providerRight,
    !oldCtr.isSaturatePositive,
    !oldCtr.isSaturateNegative,
  )
  val usefulWrite = Mux(
    usefulUp,
    !oldUseful.isSaturatePositive,
    usefulDown && !oldUseful.isSaturateNegative,
  )
  val targetWrite = providerWrong && oldCtr.isSaturateNegative
  val providerWriteEn = s2TrainValid && s2Provider && (ctrWrite || usefulWrite || targetWrite )

  val targetRegion = getRegion(s2Target)
  val trainPCRegion =  getRegion(s2TrainPC)
  val usePCRegionMatch = targetRegion === trainPCRegion
  val targetOffset = getOffset(s2Target)

  val allocLfsr = LFSR(15)
  val lfsrMask = Wire(UInt(NumTables.W))
  lfsrMask := allocLfsr(NumTables - 1, 0)

  //找到可以分配的Table。 还有具体的way
  val allocTable = s2TrainResp.zipWithIndex.map { case (resp, tableIdx) =>
    val entries = if (NumWays == 1) Seq(resp.entry.get) else resp.entries.get
    val usefulFree = entries.map(_.useful.isSaturateNegative) // 多way 的
    if (NumWays == 1) {
      // 1 way 时，tag 命中且 useful 不是 0 就是这一路不空。条件收成 useful 为 0。
      (usefulFree.head, None: Option[UInt])
    } else {
      val tagHit = trainResolved.tableWayHit(tableIdx)    // 多way 的hit
      val cancel = tagHit.zip(usefulFree).map { case (hit, free) =>
        hit && !free
      }.reduce(_ || _)  // 有tag 命中， 但是不可以被替换。

      val tagFree = tagHit.zip(usefulFree).map { case (hit, free) =>
        hit && free
      } //有tag 命中, 并且是可以被替换的

      val hasTagFree = tagFree.reduce(_ || _)

      val way = Mux(hasTagFree, seqToUInt(tagFree), lowestWayOH(usefulFree))
      (!cancel && usefulFree.reduce(_ || _), Some(way))
    }
  }

  val allocCandidate = Wire(UInt(NumTables.W))
  val rawCandidate = seqToUInt(allocTable.map(_._1))
  val tableMask = Mux(
    trainResolved.providerValid,
    HighMask(trainResolved.providerOH),
    (~0.U(NumTables.W)),
  )
  allocCandidate := rawCandidate & tableMask // 原始可选的table
  val maskedCandidate = Wire(UInt(NumTables.W))
  maskedCandidate := allocCandidate & lfsrMask
  // LFSR 低位当掩码，再取最低表号。掩码没命中时退回最低候选，所以小号表更容易被选中。
  val allocOH = Mux(
    maskedCandidate.orR,
    LowPriorityOH(maskedCandidate),
    LowPriorityOH(allocCandidate),
  )
  require(allocOH.getWidth == NumTables)
  // 没有 provider 视为目标和真实目标不同。provider 其实正确就不分配。
  val doAlloc = s2TrainValid && s2Mispredict && !(s2Provider && providerRight) && allocCandidate.orR

  val chosenOH = Wire(UInt(RegionNums.W))
  val regionHit = regions.map { entry =>
    entry.valid && entry.region === targetRegion
  }
  val regionEmpty = regions.map { entry =>
    !entry.valid
  }
  val regionHitOH = lowestWayOH(regionHit)
  val regionEmptyOH = lowestWayOH(regionEmpty)

  chosenOH := Mux(
    seqToUInt(regionHit).orR,
    regionHitOH,
    Mux(seqToUInt(regionEmpty).orR, regionEmptyOH, regionReplacer.getReplaceWayOH),
  ) // 有命中选择命中， 无命中选空的。

  require(chosenOH.getWidth == RegionNums)
  val chosenPointer = OHToUInt(chosenOH)

  val regionWrite = s2TrainValid && !usePCRegionMatch && (s2Provider && targetWrite || doAlloc)

  when(regionWrite) {
    regions(chosenPointer).valid := true.B
    regions(chosenPointer).region := targetRegion
    if (RegionReplacement != "random") {
      regionReplacer.update(chosenOH)
    }
  }

  val allocCtr = SaturateCounter(ConfidenceCtrWidth, SaturateCounter.SaturateNegative(ConfidenceCtrWidth))
  val allocUseful = SaturateCounter(UsefulCtrWidth, SaturateCounter.SaturateNegative(UsefulCtrWidth))
  val updatedCtr = Mux(ctrWrite, ctrNext, oldCtr)
  val updatedUseful = Mux(usefulWrite, usefulNext, oldUseful)
  val updatedOffset = Mux(targetWrite, targetOffset, trainResolved.providerEntry.offset)
  val updatedUsePCRegion = Mux(targetWrite, usePCRegionMatch, trainResolved.providerEntry.usePCRegion)
  val updatedPointer = Mux(
    targetWrite && !usePCRegionMatch,
    chosenPointer,
    trainResolved.providerEntry.pointer,
  )
  val allocPointer = Mux(usePCRegionMatch, 0.U(RegionIdxWidth.W), chosenPointer)
  val trainWriteBankMask = getBankMask(s2TrainPC)
  val providerWayOH = if (NumWays == 1) {
    None
  } else {
    Some(Mux1H(trainResolved.providerOH, trainResolved.wayOH.get))
  }

  tables.zipWithIndex.foreach { case (table, tableIdx) =>
    implicit val info = TableInfos(tableIdx)
    val allocThis = doAlloc && allocOH(tableIdx)
    val providerThis = providerWriteEn && trainResolved.providerOH(tableIdx)
    val trainWrite = Wire(new ITTAGETableWriteReq)

    trainWrite.bankMask := trainWriteBankMask
    trainWrite.setIdx := getSetIndex(s2TrainPC, s2TrainPHR.hist(tableIdx))
    if (NumWays > 1) {
      trainWrite.wayOH.get := Mux(allocThis, allocTable(tableIdx)._2.get, providerWayOH.get)
    }
    trainWrite.entry.ctr := Mux(allocThis, allocCtr, updatedCtr)
    trainWrite.entry.useful := Mux(allocThis, allocUseful, updatedUseful)
    trainWrite.entry.tag := Mux(allocThis, trainResolved.tags(tableIdx), trainResolved.providerEntry.tag)
    trainWrite.entry.offset := Mux(allocThis, targetOffset, updatedOffset)
    trainWrite.entry.usePCRegion := Mux(allocThis, usePCRegionMatch, updatedUsePCRegion)
    trainWrite.entry.pointer := Mux(allocThis, allocPointer, updatedPointer)

    table.io.trainWriteReq.valid := allocThis || providerThis
    table.io.trainWriteReq.bits := trainWrite

    assert(
      !(allocThis && providerThis),
      "ittage provider update and allocate hit the same table",
    )
  }


  trainWriteValid := providerWriteEn || doAlloc
  trainWriteBank.foreach { writeBank =>
    writeBank := trainWriteBankMask
  }

  val trainUseAltIdx = s2TrainPC(UseAltOnNaIdxWidth - 1, 0)
  require(trainUseAltIdx.getWidth == UseAltOnNaIdxWidth)
  // 最高位为 1 才选 alt。alt 更好就加，provider 更好就减。
  val altBetter = s2TrainValid && s2Provider && providerWrong && s2Alt && altRight
  val providerBetter = s2TrainValid && s2Provider && usefulUp
  when(altBetter) {
    useAltOnNaVec(trainUseAltIdx) := useAltOnNaVec(trainUseAltIdx) + 1
  }.elsewhen(providerBetter) {
    useAltOnNaVec(trainUseAltIdx) := useAltOnNaVec(trainUseAltIdx) - 1
  }

//----------------------output----------------------------------------//
  io.predictReq.ready := !trainWriteBlocksPredict && !trainReadBlocksPredict
  io.ittageTrain.ready := !trainReadWant || !trainReadBlocked
  io.resetDown := resetDown
  io.meta := meta
  io.prediction := prediction
}
