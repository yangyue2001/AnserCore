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
  }


  def assemble(
      slots: Vec[UInt],
      instEnd: Vec[Bool],
      isLast: Vec[Bool],
      slotValid: Vec[Bool],
      slotFtq: Vec[FTQPtr],
      slotOff: Vec[UInt],
  ): AssembleResult = {
    val window = NumReadBanks
    require(window == DecodeWidth * 2)
    require(slots.length == window && instEnd.length == window)
    require(isLast.length == window && slotValid.length == window)
    require(slotFtq.length == window && slotOff.length == window)

    val result = Wire(new AssembleResult)
    val countWidth = log2Ceil(window + 1)
    // slotCursor(i)：第 i 条指令的低半字在窗口中的起点。前面每条占 1 个或 2 个槽。
    val slotCursor = Wire(Vec(DecodeWidth + 1, UInt(countWidth.W)))
    slotCursor(0) := 0.U
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
      val lowOffset = choose(slotOff(_))
      val lowFtq = choose(slotFtq(_))
      val highValid = choose(pos => slotValid(pos + 1))
      val highEnd = choose(pos => instEnd(pos + 1))
      val highSlot = choose(pos => slots(pos + 1))
      val highLast = choose(pos => isLast(pos + 1))
      val highOffset = choose(pos => slotOff(pos + 1))
      val highFtq = choose(pos => slotFtq(pos + 1))
      // instEnd=1 表示这个 16 位槽是一条指令的结尾。
      // 16 位：低半字自己就是结尾。32 位：低半字不是结尾，紧挨的高半字必须是结尾。
      val isRvc = lowValid && lowEnd
      val isRvi = lowValid && !lowEnd && highValid && highEnd

      result.valid(instIdx) := isRvc || isRvi
      // 16 位高 16 位补 0。32 位是高地址槽在前、低地址槽在后。
      result.packet(instIdx).inst := Mux(isRvc, Cat(0.U(16.W), lowSlot), Cat(highSlot, lowSlot))
      // 指令的块尾看结尾槽：16 位看低半字，32 位看高半字。
      result.packet(instIdx).isLastInFetchBlock := Mux(isRvi, highLast, lowLast)
      // 结尾槽入队时已经带了自己的 FTQ 和块内下标。
      // 32 位跨块时结尾在下一块的高半字，PC 由那个块的 startAddr 回退一槽。
      result.packet(instIdx).ftqPtr := Mux(isRvi, highFtq, lowFtq)
      result.packet(instIdx).endOffset := Mux(isRvi, highOffset, lowOffset)
      result.packet(instIdx).except := FrontendExcept.none
      // 拼不成完整指令就停在 start。后面的指令不再往后看，半截槽本拍也不弹出。
      // 16 位只前进 1 个槽，32 位前进 2 个槽。
      slotCursor(instIdx + 1) := Mux(result.valid(instIdx), start + Mux(isRvc, 1.U, 2.U), start)
    }

    // 游标停住的位置就是本拍弹出的槽数。窗口里更后面的完整指令留到下一拍。
    val popCount = slotCursor(DecodeWidth)
    result.popCount := popCount
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
    VecInit(ibufferRead.map(_.ftqPtr)),
    VecInit(ibufferRead.map(_.offset)),
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
  val bypassFtq = Wire(Vec(NumReadBanks, new FTQPtr))
  val bypassOff = Wire(Vec(NumReadBanks, UInt(FetchBlockOffsetWidth.W)))
  for (slotIdx <- 0 until NumReadBanks) {
    bypassSlot(slotIdx) := enq.slots(slotIdx)
    bypassEnd(slotIdx) := enq.slotInstEnd(slotIdx)
    bypassLast(slotIdx) := slotIdx.U === enq.lastSlotOffset
    bypassValid(slotIdx) := slotIdx.U <= enq.lastSlotOffset
    bypassFtq(slotIdx) := enq.ftqPtr
    bypassOff(slotIdx) := slotIdx.U
  }
  val bypassAsm = assemble(
    bypassSlot,
    bypassEnd,
    bypassLast,
    bypassValid,
    bypassFtq,
    bypassOff,
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
  io.enq.ready := enqReady
  // 最后一个槽可以是 32 位指令的低半字，instEnd 为 0，等高半字再拼。

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
    writeData(slotIdx).ftqPtr := enq.ftqPtr
    writeData(slotIdx).offset := slot
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

  //-----------------------output-----------------------------
  io.deq.zipWithIndex.foreach { case (deq, idx) =>
    deq.valid := instPacketValid(idx) && !flush
    deq.bits := instPacket(idx)
  }
}
