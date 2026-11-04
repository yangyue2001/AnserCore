package ansercore.backend.softBackend

import chisel3._
import chisel3.util._
import ansercore.BackendToFrontendIO
import ansercore.FrontendToBackendIO
import ansercore.HasAnserParameters
import ansercore.PrivMode
import ansercore.frontend.CFIAttribute
import ansercore.frontend.CFITypte
import ansercore.frontend.FrontendExcept
import ansercore.frontend.RASAction
import ansercore.frontend.ftq.FTQPosition
import ansercore.mmu.MMUCtr

// 指令在时钟上升沿执行一次。组合调用会在同拍求值多次，把同一条指令做重复。
// ideal_backend_resp 回传整份 GPR/FPR、CSR，以及这一条指令的 MMIO 访问。
class SoftRetire extends Bundle {
  val valid = Bool()
  val pc    = UInt(64.W)
  val inst  = UInt(32.W)
  val rfwen = Bool()
  val wdest = UInt(8.W)
  val wdata = UInt(64.W)
  val skip  = Bool()
}

class SoftBackendDpi extends chisel3.experimental.ExtModule {
  override def desiredName = "SoftBackendDpi"

  val clock        = IO(Input(Clock()))
  val reset        = IO(Input(Bool()))
  val step_valid   = IO(Input(Bool()))
  val pc           = IO(Input(UInt(64.W)))
  val inst         = IO(Input(UInt(32.W)))
  val page_fault   = IO(Input(UInt(8.W)))
  val access_fault = IO(Input(UInt(8.W)))
  val gpr = Seq.tabulate(32) { i =>
    val port = IO(Output(UInt(64.W)))
    port.suggestName(s"gpr$i")
    port
  }
  val csr_pc     = IO(Output(UInt(64.W)))
  val csr_priv   = IO(Output(UInt(8.W)))
  val csr_satp   = IO(Output(UInt(64.W)))
  val csr_mstatus = IO(Output(UInt(64.W)))
  val branch     = IO(Output(UInt(8.W)))
  val taken      = IO(Output(UInt(8.W)))
  val target     = IO(Output(UInt(64.W)))
  val mmio       = IO(Output(UInt(8.W)))
  val mmio_store = IO(Output(UInt(8.W)))
  val mmio_size  = IO(Output(UInt(8.W)))
  val mmio_addr  = IO(Output(UInt(64.W)))
  val mmio_data  = IO(Output(UInt(64.W)))

  private val gprPorts = (0 until 32).map(i => s"  output reg [63:0] gpr$i").mkString(",\n")
  private val gprArgs = (0 until 32).map(i => s"    input longint unsigned v_gpr$i").mkString(",\n")
  private val fprArgs = (0 until 32).map(i => s"    input longint unsigned v_fpr$i").mkString(",\n")
  private val gprCapture = (0 until 32).map(i => s"    gpr_w[$i] = v_gpr$i;").mkString("\n")
  private val fprCapture = (0 until 32).map(i => s"    fpr_w[$i] = v_fpr$i;").mkString("\n")
  private val gprReset = (0 until 32).map(i => s"      gpr$i <= 64'h0;").mkString("\n")
  private val gprLoad = (0 until 32).map(i => s"      gpr$i <= gpr_w[$i];").mkString("\n")

  setInline(
    s"$desiredName.sv",
    s"""import "DPI-C" context function void ideal_backend_inst(
       |  input longint unsigned pc,
       |  input int unsigned inst,
       |  input byte unsigned page_fault,
       |  input byte unsigned access_fault
       |);
       |module $desiredName(
       |  input clock,
       |  input reset,
       |  input step_valid,
       |  input [63:0] pc,
       |  input [31:0] inst,
       |  input [7:0] page_fault,
       |  input [7:0] access_fault,
       |$gprPorts,
       |  output reg [63:0] csr_pc,
       |  output reg [7:0] csr_priv,
       |  output reg [63:0] csr_satp,
       |  output reg [63:0] csr_mstatus,
       |  output reg [7:0] branch,
       |  output reg [7:0] taken,
       |  output reg [63:0] target,
       |  output reg [7:0] mmio,
       |  output reg [7:0] mmio_store,
       |  output reg [7:0] mmio_size,
       |  output reg [63:0] mmio_addr,
       |  output reg [63:0] mmio_data
       |);
       |  longint unsigned gpr_w[32];
       |  longint unsigned fpr_w[32];
       |  longint unsigned csr_pc_w;
       |  byte unsigned csr_priv_w;
       |  longint unsigned csr_satp_w;
       |  longint unsigned csr_mstatus_w;
       |  longint unsigned mepc_w;
       |  longint unsigned mtvec_w;
       |  longint unsigned mcause_w;
       |  longint unsigned mtval_w;
       |  byte unsigned branch_w;
       |  byte unsigned taken_w;
       |  longint unsigned target_w;
       |  byte unsigned mmio_w;
       |  byte unsigned mmio_store_w;
       |  byte unsigned mmio_size_w;
       |  longint unsigned mmio_addr_w;
       |  longint unsigned mmio_data_w;
       |  export "DPI-C" function ideal_backend_anchor;
       |  function void ideal_backend_anchor();
       |  endfunction
       |  export "DPI-C" function ideal_backend_resp;
       |  function void ideal_backend_resp(
       |$gprArgs,
       |$fprArgs,
       |    input longint unsigned v_pc,
       |    input byte unsigned v_priv,
       |    input longint unsigned v_satp,
       |    input longint unsigned v_mstatus,
       |    input longint unsigned v_mepc,
       |    input longint unsigned v_mtvec,
       |    input longint unsigned v_mcause,
       |    input longint unsigned v_mtval,
       |    input byte unsigned v_branch,
       |    input byte unsigned v_taken,
       |    input longint unsigned v_target,
       |    input byte unsigned v_mmio,
       |    input byte unsigned v_mmio_store,
       |    input byte unsigned v_mmio_size,
       |    input longint unsigned v_mmio_addr,
       |    input longint unsigned v_mmio_data
       |  );
       |$gprCapture
       |$fprCapture
       |    csr_pc_w = v_pc;
       |    csr_priv_w = v_priv;
       |    csr_satp_w = v_satp;
       |    csr_mstatus_w = v_mstatus;
       |    mepc_w = v_mepc;
       |    mtvec_w = v_mtvec;
       |    mcause_w = v_mcause;
       |    mtval_w = v_mtval;
       |    branch_w = v_branch;
       |    taken_w = v_taken;
       |    target_w = v_target;
       |    mmio_w = v_mmio;
       |    mmio_store_w = v_mmio_store;
       |    mmio_size_w = v_mmio_size;
       |    mmio_addr_w = v_mmio_addr;
       |    mmio_data_w = v_mmio_data;
       |  endfunction
       |
       |  // 结果用非阻塞打入寄存器。同一沿上的指针更新看不到这次执行结果。
       |  always @(posedge clock) begin
       |    if (reset) begin
       |$gprReset
       |      csr_pc <= 64'h0;
       |      csr_priv <= 8'h0;
       |      csr_satp <= 64'h0;
       |      csr_mstatus <= 64'h0;
       |      branch <= 8'h0;
       |      taken <= 8'h0;
       |      target <= 64'h0;
       |      mmio <= 8'h0;
       |      mmio_store <= 8'h0;
       |      mmio_size <= 8'h0;
       |      mmio_addr <= 64'h0;
       |      mmio_data <= 64'h0;
       |    end else if (step_valid) begin
       |      ideal_backend_inst(pc, inst, page_fault, access_fault);
       |$gprLoad
       |      csr_pc <= csr_pc_w;
       |      csr_priv <= csr_priv_w;
       |      csr_satp <= csr_satp_w;
       |      csr_mstatus <= csr_mstatus_w;
       |      branch <= branch_w;
       |      taken <= taken_w;
       |      target <= target_w;
       |      mmio <= mmio_w;
       |      mmio_store <= mmio_store_w;
       |      mmio_size <= mmio_size_w;
       |      mmio_addr <= mmio_addr_w;
       |      mmio_data <= mmio_data_w;
       |    end
       |  end
       |endmodule
       |""".stripMargin,
  )
}

class SoftBackend extends Module with HasAnserParameters {
  class SoftBackendIO extends Bundle {
    val resetPc = Input(UInt(64.W))
    val retire = Output(new SoftRetire)
    val mmuCtr = Output(new MMUCtr)
    val fromFrontend = Flipped(new FrontendToBackendIO)
    val toFrontend = new BackendToFrontendIO
  }
  val io = IO(new SoftBackendIO)

  val instPort = io.fromFrontend.fromIFU.inst
  val pred = io.fromFrontend.fromFTQ.predQueryResp
  val slotValid = VecInit(instPort.map(_.valid))
  val validCount = PopCount(slotValid)
  val hasInst = slotValid.asUInt.orR
  val countWidth = log2Ceil(DecodeWidth + 1)

  // 一组里已经发出的条数。valid 保持到整组 ready，或被重定向冲掉。
  val cursor = RegInit(0.U(countWidth.W))
  val waitFlush = RegInit(false.B)
  val seen = Wire(Vec(DecodeWidth + 1, UInt(countWidth.W)))
  val pick = Wire(Vec(DecodeWidth, Bool()))
  seen(0) := 0.U
  for (idx <- 0 until DecodeWidth) {
    pick(idx) := slotValid(idx) && seen(idx) === cursor
    seen(idx + 1) := (seen(idx) + slotValid(idx).asUInt)(countWidth - 1, 0)
  }
  val packet = Wire(chiselTypeOf(instPort.head.bits))
  packet.inst := Mux1H(pick.asUInt, instPort.map(_.bits.inst))
  packet.isLastInFetchBlock := Mux1H(pick.asUInt, instPort.map(_.bits.isLastInFetchBlock))
  packet.ftqPtr := Mux1H(pick.asUInt, instPort.map(_.bits.ftqPtr))
  packet.endOffset := Mux1H(pick.asUInt, instPort.map(_.bits.endOffset))
  packet.except := FrontendExcept.none
  for (idx <- 0 until DecodeWidth) {
    when(pick(idx)) {
      packet.except := instPort(idx).bits.except
    }
  }
  val issuePos = Wire(new FTQPosition)
  issuePos.ptr := packet.ftqPtr
  issuePos.offset := packet.endOffset

  // stepping 要看上一拍的控制流结果，先占位，算出重定向再接上。
  val stepping = Wire(Bool())
  val issuedSeq = RegEnable(pred.nextPC, 0.U(pcWidth.W), stepping)
  val issuedPredNext = RegEnable(
    Mux(packet.isLastInFetchBlock && pred.taken, pred.target, pred.nextPC),
    0.U(pcWidth.W),
    stepping,
  )
  val issuedPos = RegEnable(issuePos, 0.U.asTypeOf(new FTQPosition), stepping)
  val issuedInst = RegEnable(packet.inst, 0.U(32.W), stepping)
  val stepped = RegNext(stepping, false.B)
  val seenStep = RegInit(false.B)

  val dpi = Module(new SoftBackendDpi)
  val bytePc = Cat(pred.pc, 0.U(InstOffsetWidth.W))
  val pageFault = packet.except === FrontendExcept.pageFault
  val accessFault = packet.except === FrontendExcept.accessFault
  dpi.clock := clock
  dpi.reset := reset.asBool
  dpi.step_valid := stepping
  dpi.pc := Cat(0.U((64 - bytePc.getWidth).W), bytePc)
  dpi.inst := packet.inst
  dpi.page_fault := Cat(0.U(7.W), pageFault)
  dpi.access_fault := Cat(0.U(7.W), accessFault)

  // 下一条架构 PC 和前端即将取出的 PC 不同就要重定向。
  // jal/jalr、异常、xret 和中断都走这里，不只是条件分支。
  val sentPc = RegEnable(dpi.pc, 0.U(64.W), stepping)
  val archPc = Reg(UInt(64.W))
  val archHalf = dpi.csr_pc(pcWidth, InstOffsetWidth)
  val onPath = stepped && sentPc === archPc && !reset.asBool
  val redirectNow = onPath && archHalf =/= issuedPredNext
  // 上一拍的结果已经离开预测路径，这一拍不能再发下一条。
  stepping := hasInst && cursor < validCount && !waitFlush && !redirectNow && !reset.asBool
  val last = stepping && cursor === (validCount - 1.U)
  assert(PopCount(pick) <= 1.U, "soft backend picked more than one instruction")
  when(stepping) {
    assert(PopCount(pick) === 1.U, "soft backend lost the instruction it should step")
  }

  // 最后一条发出的当拍松开。错预测要等执行结果，来不及挡住这一拍的 ready。
  instPort.zip(slotValid).foreach { case (port, valid) =>
    port.ready := last && valid
  }

  when(reset.asBool || !hasInst) {
    cursor := 0.U
    waitFlush := false.B
  }.elsewhen(redirectNow) {
    cursor := 0.U
    waitFlush := true.B
  }.elsewhen(stepping && !last) {
    cursor := cursor + 1.U
  }.elsewhen(last) {
    cursor := 0.U
  }
  when(stepping) {
    seenStep := true.B
  }

  // 理想后端只在取指 PC 等于架构 PC 时退休。错路径取指不改 GPR，也不提交。
  val gprNow = VecInit(dpi.gpr)
  val gprShadow = RegInit(VecInit(Seq.fill(32)(0.U(64.W))))
  val sentInst = RegEnable(packet.inst, 0.U(32.W), stepping)
  val changed = Wire(Vec(32, Bool()))
  for (i <- 0 until 32) {
    changed(i) := gprNow(i) =/= gprShadow(i)
  }
  val rfwen = changed.asUInt.orR
  val wdest = PriorityEncoder(changed)
  when(reset.asBool) {
    archPc := io.resetPc
  }.elsewhen(onPath) {
    archPc := dpi.csr_pc
    gprShadow := gprNow
  }

  io.retire.valid := onPath
  io.retire.pc := sentPc
  io.retire.inst := sentInst
  io.retire.rfwen := rfwen
  io.retire.wdest := wdest.pad(8)
  io.retire.wdata := Mux(rfwen, gprNow(wdest), 0.U)
  io.retire.skip := dpi.mmio(0)

  io.toFrontend.predQuery := Mux(stepping, issuePos, 0.U.asTypeOf(new FTQPosition))
  io.toFrontend.commit.valid := stepped
  io.toFrontend.commit.bits := issuedPos

  val redirect = io.toFrontend.redirect
  redirect.valid := redirectNow
  redirect.bits.redirectPosition := issuedPos
  redirect.bits.target := archHalf
  redirect.bits.taken := archHalf =/= issuedSeq
  redirect.bits.mispredict := redirectNow
  redirect.bits.cfiAttribute := cfiOf(issuedInst)

  val privBits = Mux(seenStep, dpi.csr_priv(1, 0), PrivMode.mMode.asUInt)
  val satpBits = Mux(seenStep, dpi.csr_satp, 0.U(64.W))
  val mstatusBits = Mux(seenStep, dpi.csr_mstatus, 0.U(64.W))
  io.mmuCtr.privMode := toPriv(privBits)
  io.mmuCtr.satp := satpBits
  io.mmuCtr.mstatusMPRV := mstatusBits(17)
  io.mmuCtr.mstatusMPP := toPriv(mstatusBits(12, 11))
  io.mmuCtr.mstatusSUM := mstatusBits(18)
  io.mmuCtr.mstatusMXR := mstatusBits(19)

  def toPriv(code: UInt): PrivMode.Type = {
    MuxLookup(code, PrivMode.mMode)(
      Seq(
        0.U(2.W) -> PrivMode.uMode,
        1.U(2.W) -> PrivMode.sMode,
        3.U(2.W) -> PrivMode.mMode,
      )
    )
  }

  def cfiOf(inst: UInt): CFIAttribute = {
    val opcode = inst(6, 0)
    val rd = inst(11, 7)
    val rs1 = inst(19, 15)
    val isCond = opcode === "b1100011".U
    val isJal = opcode === "b1101111".U
    val isJalr = opcode === "b1100111".U
    val linkRd = rd === 1.U || rd === 5.U
    val linkRs1 = rs1 === 1.U || rs1 === 5.U
    val cfi = Wire(new CFIAttribute)
    cfi.cfiType := Mux(
      isCond,
      CFITypte.conditional,
      Mux(isJal, CFITypte.direct, Mux(isJalr, CFITypte.indirect, CFITypte.none)),
    )
    cfi.rasAction := Mux(
      isJal && linkRd,
      RASAction.push,
      Mux(
        isJalr && linkRd && linkRs1,
        RASAction.pushAndPop,
        Mux(isJalr && linkRs1 && !linkRd, RASAction.pop, RASAction.none),
      ),
    )
    cfi
  }
}
