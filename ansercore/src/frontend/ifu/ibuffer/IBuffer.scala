package ansercore.frontend.ifu.ibuffer

import chisel3._
import chisel3.util._
import utils.Mux1H
import ansercore.frontend.ftq.FTQPtr
import ansercore.frontend.FrontendExcept
import utils.Rota

class IBuffer extends IBufferModule with IBufferHelper {
  class IBufferIO extends IBufferBundle {
    val flush = Input(Bool())
    val enq = Flipped(Decoupled(new IBufferReq))
    val deq = Vec(DecodeWidth, Decoupled(new InstPacket))
  }
  val io = IO(new IBufferIO)

  class AssembleResult extends Bundle {
    val packet = Vec(DecodeWidth, new InstPacket)
    val valid = Vec(DecodeWidth, Bool())
    val popCount = UInt(log2Ceil(NumReadBanks + 1).W)
    val nextFTQ = new FTQPtr
    val nextOffset = UInt(FetchBlockOffsetWidth.W)
  }


  def assemble(
      slots: Vec[UInt],
      instEnd: Vec[Bool],
      isLast: Vec[Bool],
      slotValid: Vec[Bool],
      baseFTQ: FTQPtr,
      baseOffset: UInt,
  ): AssembleResult = {
    val window = NumReadBanks
    require(window == DecodeWidth * 2)
    require(slots.length == window && instEnd.length == window)
    require(isLast.length == window && slotValid.length == window)
    require(baseOffset.getWidth == FetchBlockOffsetWidth)

    val result = Wire(new AssembleResult)
    val countWidth = log2Ceil(window + 1)
    // slotOffset(i) 是窗口第 i 个槽在“它所在的取指块”里的下标，不是窗口下标。
    // 上一槽是块尾时，本槽换到下一块，下标从 0 重新计。
    val slotOffset = Wire(Vec(window, UInt(FetchBlockOffsetWidth.W)))
    slotOffset(0) := baseOffset
    for (slotIdx <- 1 until window) {
      slotOffset(slotIdx) := Mux(isLast(slotIdx - 1), 0.U, slotOffset(slotIdx - 1) + 1.U)
    }

    // slotCursor(i)：第 i 条指令的低半字在窗口中的起点。前面每条占 1 个或 2 个槽。
    // endedBlocks(i)：第 i 条开始之前，已经跨过的取指块尾个数。第 0 条从 baseFTQ 开始。
    // 多出来的最后一项给弹出后的下一拍使用。
    val slotCursor = Wire(Vec(DecodeWidth + 1, UInt(countWidth.W)))
    val endedBlocks = Wire(Vec(DecodeWidth + 1, UInt(countWidth.W)))
    slotCursor(0) := 0.U
    endedBlocks(0) := 0.U
    for (instIdx <- 0 until DecodeWidth) {
      val start = slotCursor(instIdx)
      // 前面每条最少 1 槽、最多 2 槽，所以第 i 条的起点只可能落在 i .. 2*i。
      // 第 0 条只能是 slot 0。
      // 第 1 条只能是 slot 1 或 2：start=1 时低/高是 slot1/slot2，start=2 时是 slot2/slot3。
      // 第 2 条是 slot 2..4，第 3 条是 slot 3..6。高半字再向后一个槽。
      val choices = (instIdx to instIdx * 2).toSeq
      val chosen = choices.map(pos => start === pos.U)

      // 按 start 命中的候选槽取值。低半字直接传槽号，高半字传下一个槽。没命中时为 0。
      def choose[T <: Data](valueAt: Int => T): T = {
        val values = choices.map(valueAt)
        MuxCase(0.U.asTypeOf(values.head), chosen.zip(values))
      }

      val lowValid = choose(slotValid(_))
      val lowEnd = choose(instEnd(_))
      val lowSlot = choose(slots(_))
      val lowLast = choose(isLast(_))
      val lowOffset = choose(slotOffset(_))
      val highValid = choose(pos => slotValid(pos + 1))
      val highEnd = choose(pos => instEnd(pos + 1))
      val highSlot = choose(pos => slots(pos + 1))
      val highLast = choose(pos => isLast(pos + 1))
      val highOffset = choose(pos => slotOffset(pos + 1))
      // instEnd=1 表示这个 16 位槽是一条指令的结尾。
      // 16 位：低半字自己就是结尾。32 位：低半字不是结尾，紧挨的高半字必须是结尾。
      val isRvc = lowValid && lowEnd
      val isRvi = lowValid && !lowEnd && highValid && highEnd
      // 本条实际吃掉的块尾数。半截指令记 0，避免把还没拼完的槽算进下一条。
      // 16 位最多吃掉自己这一个块尾。32 位低半字、高半字如果带块尾，各算一个。
      val passedBlocks = Mux(isRvc, lowLast.asUInt, Mux(isRvi, lowLast.asUInt + highLast.asUInt, 0.U))

      result.valid(instIdx) := isRvc || isRvi
      // 16 位高 16 位补 0。32 位是高地址槽在前、低地址槽在后。
      result.packet(instIdx).inst := Cat(highSlot, lowSlot)
      // 指令的块尾看结尾槽：16 位看低半字，32 位看高半字。
      result.packet(instIdx).isLastInFetchBlock := Mux(isRvi, highLast, lowLast)
      // ftqPtr 数的是“结尾槽之前”出现过的块尾，结尾槽自己的块尾不算。
      // blocksBeforeStart：本条低半字之前，前面的完整指令已经跨过几个块。
      // blocksInsideBeforeEnd：32 位的结尾在高半字，低半字上的块尾位于结尾之前，要加进来。
      // 16 位的结尾就是低半字自己，没有更早的半字，所以加 0；它自己的块尾由 passedBlocks 交给下一条。
      // 正常取指块不会断在 32 位指令中间，lowLast 应为 0，这时 ftqPtr 就是 baseFTQ + blocksBeforeStart。
      // 例：baseFTQ=5。slot0 是 16 位且 isLast=1，slot1 是下一块的 16 位。
      //     第 0 条 blocksBeforeStart=0，ftqPtr=5，passedBlocks=1。
      //     第 1 条 blocksBeforeStart=1，ftqPtr=6。
      val blocksBeforeStart = endedBlocks(instIdx)
      val blocksInsideBeforeEnd = Mux(isRvi, lowLast.asUInt, 0.U)
      result.packet(instIdx).ftqPtr := baseFTQ + (blocksBeforeStart + blocksInsideBeforeEnd)
      // endOffset 也取结尾槽：16 位取低半字，32 位取高半字。
      result.packet(instIdx).endOffset := Mux(isRvi, highOffset, lowOffset)
      result.packet(instIdx).except := FrontendExcept.none
      // 拼不成完整指令就停在 start。后面的指令不再往后看，半截槽本拍也不弹出。
      // 16 位只前进 1 个槽，32 位前进 2 个槽。
      slotCursor(instIdx + 1) := Mux(result.valid(instIdx), start + Mux(isRvc, 1.U, 2.U), start)
      endedBlocks(instIdx + 1) := endedBlocks(instIdx) + passedBlocks
    }

    // 游标停住的位置就是本拍弹出的槽数。窗口里更后面的完整指令留到下一拍。
    val popCount = slotCursor(DecodeWidth)
    result.popCount := popCount
    // 下一条将要读的指令属于哪个 FTQ，等于本拍拼完的指令一共跨过的块尾数。
    result.nextFTQ := baseFTQ + endedBlocks(DecodeWidth)
    // 下一拍第一个槽的块内下标。8 个槽都弹出时没有 slotOffset(8)，改由最后一槽加 1。
    // 最后一槽自己是块尾时，下一块从 0 开始。
    result.nextOffset := Mux(
      popCount === window.U,
      Mux(isLast(window - 1), 0.U, slotOffset(window - 1) + 1.U),
      slotOffset(popCount(log2Ceil(window) - 1, 0)),
    )
    result
  }


  //-----------------------alias------------------------------
  val flush = io.flush
  val enqValid = io.enq.valid
  val enq = io.enq.bits

  //-----------------------memory-----------------------------
  val ibuffer = Reg(Vec(IBufferSize, new IBufferEntry))
  val enqBankPtr = RegInit(1.U(NumWriteBanks.W))
  val enqRowPtr = RegInit(1.U(WriteBankDepth.W))
  val deqBankPtr = RegInit(1.U(NumReadBanks.W))
  val deqRowPtr = RegInit(1.U(ReadBankDepth.W))
  val numEntries = RegInit(0.U(IBufferCountWidth.W))
  val firstFTQPtr = Reg(new FTQPtr)
  val endOffset = RegInit(0.U(FetchBlockOffsetWidth.W))
  val instPacket = Reg(Vec(DecodeWidth, new InstPacket))
  val instPacketValid = RegInit(VecInit.fill(DecodeWidth)(false.B))

  val ibufferEmpty = numEntries === 0.U
  val enqLinear = linearOf(enqBankPtr, enqRowPtr, NumWriteBanks)
  val deqLinear = linearOf(deqBankPtr, deqRowPtr, NumReadBanks)

  //-----------------------read window------------------------
  val readBank = Wire(Vec(ReadBankDepth, Vec(NumReadBanks, new IBufferEntry)))
  for (row <- 0 until ReadBankDepth) {
    for (bank <- 0 until NumReadBanks) {
      readBank(row)(bank) := ibuffer(row * NumReadBanks + bank)
    }
  }
  val readRow = Mux1H(deqRowPtr, readBank)
  val readRowNext = Mux1H(deqRowPtr, Rota(readBank, 1, Rota.Left))
  val ibufferRead = Wire(Vec(NumReadBanks, new IBufferEntry))
  val ibufferReadValid = Wire(Vec(NumReadBanks, Bool()))
  //优雅
  for (pos <- 0 until NumReadBanks) {
    val aligned = VecInit(readRow.toSeq.drop(pos) ++ readRowNext.toSeq.take(pos))
    ibufferRead(pos) := Mux1H(deqBankPtr, aligned)
    ibufferReadValid(pos) := numEntries > pos.U
  }

  val bufferAsm = assemble(
    VecInit(ibufferRead.map(_.slot)),
    VecInit(ibufferRead.map(_.instEnd)),
    VecInit(ibufferRead.map(_.isLastInFetchBlock)),
    ibufferReadValid,
    firstFTQPtr,
    endOffset,
  )

  val deqHas = instPacketValid.asUInt.orR
  val deqReady = VecInit(io.deq.zip(instPacketValid).map { case (port, hold) =>
    !hold || port.ready
  }).asUInt.andR
  val deqFire = deqHas && deqReady && !flush
  val deqCanLoad = !flush && (!deqHas || deqFire)
  val bufferPopFire = deqCanLoad && !ibufferEmpty && bufferAsm.popCount =/= 0.U

  // 空队列且输出寄存器可装载时，请求直接进 deq，装不下的槽再写入 FIFO。
  val bypassSlot = Wire(Vec(NumReadBanks, UInt(16.W)))
  val bypassEnd = Wire(Vec(NumReadBanks, Bool()))
  val bypassLast = Wire(Vec(NumReadBanks, Bool()))
  val bypassValid = Wire(Vec(NumReadBanks, Bool()))
  for (slotIdx <- 0 until NumReadBanks) {
    bypassSlot(slotIdx) := enq.slots(slotIdx)
    bypassEnd(slotIdx) := enq.slotInstEnd(slotIdx)
    bypassLast(slotIdx) := slotIdx.U === enq.lastSlotOffset
    bypassValid(slotIdx) := slotIdx.U <= enq.lastSlotOffset
  }
  val bypassAsm = assemble(
    bypassSlot,
    bypassEnd,
    bypassLast,
    bypassValid,
    enq.ftqPtr,
    0.U(FetchBlockOffsetWidth.W),
  )
  val wantBypass = ibufferEmpty && deqCanLoad && enqValid
  val bypassPopUsed = Mux(wantBypass, bypassAsm.popCount, 0.U)
  val enqCount = enq.lastSlotOffset + 1.U
  val storedCount = enqCount - bypassPopUsed
  val freeing = Mux(bufferPopFire, bufferAsm.popCount, 0.U)
  val incoming = Mux(enqValid, storedCount, 0.U)
  val enqReady = !flush && (IBufferSize.U - numEntries +& freeing) >= incoming
  val enqFire = enqValid && enqReady
  val loadBypass = wantBypass && enqFire && bypassAsm.popCount =/= 0.U
  val storing = enqFire && storedCount =/= 0.U
  val bufferDrained = bufferPopFire && (bufferAsm.popCount === numEntries)
  val storedFTQ = Mux(wantBypass, bypassAsm.nextFTQ, enq.ftqPtr)
  val storedOffset = Mux(wantBypass, bypassAsm.nextOffset, 0.U)

  io.enq.ready := enqReady
  assert(
    !enqFire || enq.slotInstEnd(enq.lastSlotOffset),
    "IBuffer last slot of a fetch block must end an instruction",
  )

  //-----------------------write------------------------------
  val writeValid = Wire(Vec(FetchBlockNumSlots, Bool()))
  val writeData = Wire(Vec(FetchBlockNumSlots, new IBufferEntry))
  val writeDest = Wire(Vec(FetchBlockNumSlots, UInt(log2Ceil(IBufferSize).W)))
  for (slotIdx <- 0 until FetchBlockNumSlots) {
    val slot = slotIdx.U
    writeValid(slotIdx) := enqFire && (slot >= bypassPopUsed) && (slot <= enq.lastSlotOffset)
    writeDest(slotIdx) := wrapOffset(enqLinear, slot - bypassPopUsed)
    writeData(slotIdx).slot := enq.slots(slotIdx)
    writeData(slotIdx).instEnd := enq.slotInstEnd(slotIdx)
    writeData(slotIdx).isLastInFetchBlock := slot === enq.lastSlotOffset
  }
  for (entryIdx <- 0 until IBufferSize) {
    val hit = VecInit(writeValid.zip(writeDest).map { case (valid, dest) =>
      valid && dest === entryIdx.U
    })
    when(hit.asUInt.orR) {
      ibuffer(entryIdx) := Mux1H(hit, writeData)
    }
  }

  val storedFire = Mux(enqFire, storedCount, 0.U)
  val (enqBankNext, enqRowNext) = oneHotBankRow(
    wrapOffset(enqLinear, storedFire),
    NumWriteBanks,
    WriteBankDepth,
  )
  val (deqBankNext, deqRowNext) = oneHotBankRow(
    wrapOffset(deqLinear, freeing),
    NumReadBanks,
    ReadBankDepth,
  )

  when(flush) {
    enqBankPtr := 1.U(NumWriteBanks.W)
    enqRowPtr := 1.U(WriteBankDepth.W)
    deqBankPtr := 1.U(NumReadBanks.W)
    deqRowPtr := 1.U(ReadBankDepth.W)
    numEntries := 0.U
  }.otherwise {
    enqBankPtr := enqBankNext
    enqRowPtr := enqRowNext
    deqBankPtr := deqBankNext
    deqRowPtr := deqRowNext
    val nextEntries = (numEntries +& storedFire) - freeing
    numEntries := nextEntries(IBufferCountWidth - 1, 0)
  }

  when(flush) {
    instPacketValid := 0.U.asTypeOf(instPacketValid)
  }.elsewhen(bufferPopFire) {
    instPacket := bufferAsm.packet
    instPacketValid := bufferAsm.valid
  }.elsewhen(loadBypass) {
    instPacket := bypassAsm.packet
    instPacketValid := bypassAsm.valid
  }.elsewhen(deqFire) {
    instPacketValid := 0.U.asTypeOf(instPacketValid)
  }

  when(flush) {
    endOffset := 0.U
  }.elsewhen(storing && (ibufferEmpty || bufferDrained)) {
    firstFTQPtr := Mux(ibufferEmpty, storedFTQ, enq.ftqPtr)
    endOffset := Mux(ibufferEmpty, storedOffset, 0.U)
  }.elsewhen(bufferPopFire) {
    firstFTQPtr := bufferAsm.nextFTQ
    endOffset := bufferAsm.nextOffset
  }

  //-----------------------output-----------------------------
  io.deq.zipWithIndex.foreach { case (deq, idx) =>
    deq.valid := instPacketValid(idx) && !flush
    deq.bits := instPacket(idx)
  }
}
