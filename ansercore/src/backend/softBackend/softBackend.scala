package ansercore.backend.softBackend

import chisel3._
import chisel3.util._
import ansercore.BackendToFrontendIO
import ansercore.FrontendToBackendIO
import ansercore.HasAnserParameters
import ansercore.PrivMode
import ansercore.frontend.CFIAttribute
import ansercore.frontend.FrontendExcept
import ansercore.frontend.ftq.FTQPosition
import ansercore.mmu.MMUCtr
import ansercore.BackendRedirect
import difftest.DiffArchCommit
import difftest.DifftestModule

// ideal_backend_inst returns taken/target plus the architectural state.
// gpr/fpr are flat ports because the DPI call itself uses arrays.
class IdealBackendDpi extends ExtModule {
  override def desiredName = "IdealBackendDpi"

  val enable = IO(Input(Bool()))
  val pc = IO(Input(UInt(64.W)))
  val inst = IO(Input(UInt(32.W)))
  val page_fault = IO(Input(UInt(8.W)))
  val access_fault = IO(Input(UInt(8.W)))
  val taken = IO(Output(UInt(8.W)))
  val target = IO(Output(UInt(64.W)))
  val mmio = IO(Output(UInt(8.W)))
  val gpr = Seq.tabulate(32) { i =>
    IO(Output(UInt(64.W))).suggestName(s"gpr_$i")
  }
  val fpr = Seq.tabulate(32) { i =>
    IO(Output(UInt(64.W))).suggestName(s"fpr_$i")
  }
  val csr = IdealBackendDpi.csrNames.map { name =>
    IO(Output(UInt(64.W))).suggestName(name)
  }
  val arch_pc = IO(Output(UInt(64.W)))

  private val csrIndex = IdealBackendDpi.csrNames.indices
    .map(i => s"  assign ${IdealBackendDpi.csrNames(i)} = csr[$i];")
    .mkString("\n")
  private val gprIndex = (0 until 32)
    .map(i => s"  assign gpr_$i = gpr[$i];")
    .mkString("\n")
  private val fprIndex = (0 until 32)
    .map(i => s"  assign fpr_$i = fpr[$i];")
    .mkString("\n")
  private val gprPorts = (0 until 32).map(i => s"output [63:0] gpr_$i")
  private val fprPorts = (0 until 32).map(i => s"output [63:0] fpr_$i")
  private val csrPorts = IdealBackendDpi.csrNames.map(name => s"output [63:0] $name")
  private val ports = Seq(
    "input enable",
    "input [63:0] pc",
    "input [31:0] inst",
    "input [7:0] page_fault",
    "input [7:0] access_fault",
    "output [7:0] taken",
    "output [63:0] target",
    "output [7:0] mmio",
  ) ++ gprPorts ++ fprPorts ++ csrPorts ++ Seq("output [63:0] arch_pc")

  setInline(
    s"$desiredName.sv",
    s"""import "DPI-C" function void ideal_backend_inst(
       |  input longint unsigned pc,
       |  input int unsigned inst,
       |  input byte unsigned page_fault,
       |  input byte unsigned access_fault,
       |  output byte unsigned taken,
       |  output longint unsigned target,
       |  output longint unsigned gpr[32],
       |  output longint unsigned fpr[32],
       |  output longint unsigned csr[19],
       |  output longint unsigned state_pc,
       |  output byte unsigned mmio
       |);
       |module $desiredName(
       |  ${ports.mkString(",\n  ")}
       |);
       |  byte unsigned taken_w;
       |  byte unsigned mmio_w;
       |  longint unsigned target_w;
       |  longint unsigned arch_pc_w;
       |  longint unsigned gpr[32];
       |  longint unsigned fpr[32];
       |  longint unsigned csr[19];
       |  integer i;
       |
       |  always @(*) begin
       |    if (enable) begin
       |      ideal_backend_inst(
       |        pc, inst, page_fault, access_fault, taken_w, target_w, gpr, fpr,
       |        csr, arch_pc_w, mmio_w
       |      );
       |    end else begin
       |      taken_w = 8'h0;
       |      target_w = 64'h0;
       |      mmio_w = 8'h0;
       |      arch_pc_w = 64'h0;
       |      for (i = 0; i < 32; i = i + 1) begin
       |        gpr[i] = 64'h0;
       |        fpr[i] = 64'h0;
       |      end
       |      for (i = 0; i < 19; i = i + 1) begin
       |        csr[i] = 64'h0;
       |      end
       |    end
       |  end
       |
       |  assign taken = taken_w;
       |  assign target = target_w;
       |  assign mmio = mmio_w;
       |  assign arch_pc = arch_pc_w;
       |$gprIndex
       |$fprIndex
       |$csrIndex
       |endmodule
       |""".stripMargin,
  )
}

object IdealBackendDpi {
  // Same order as IsaState scalars and DiffCSR.
  val csrNames = Seq(
    "privilege",
    "mstatus",
    "sstatus",
    "mepc",
    "sepc",
    "mtval",
    "stval",
    "mtvec",
    "stvec",
    "mcause",
    "scause",
    "satp",
    "mip",
    "mie",
    "mscratch",
    "sscratch",
    "mideleg",
    "medeleg",
    "fcsr",
  )
  val privilege = 0
  val mstatus = 1
  val satp = 11
}

class SoftBackend extends Module with HasAnserParameters {
  class SoftBackendIO extends Bundle {
    val mmuCtr = Output(new MMUCtr)
    val fromFrontend = Flipped(new FrontendToBackendIO)
    val toFrontend = new BackendToFrontendIO
  }
  val io = IO(new SoftBackendIO)

  // alias
  val instPort = io.fromFrontend.fromIFU.inst
  val pred = io.fromFrontend.fromFTQ.predQueryResp
  // 一组指令停在端口上，每拍提交一条。最后一条才把整组还给 IBuffer。
  val slotValid = VecInit(instPort.map(_.valid))
  val hasInst = slotValid.asUInt.orR
  val groupCount = PopCount(slotValid)
  val cursor = RegInit(0.U(log2Ceil(DecodeWidth).W))
  val backendReadyGO = Wire(Bool())
  val ready = Wire(Bool())
  ready := !hasInst || backendReadyGO
  val fire = hasInst && ready
  val lastSlot = (groupCount - 1.U)(cursor.getWidth - 1, 0)
  backendReadyGO := hasInst && cursor === lastSlot
  instPort.foreach(_.ready := ready)

  val commitValid = hasInst && !reset.asBool
  val commitFTQPosition = Wire(new FTQPosition)
  val commitPacket = instPort(cursor).bits
  commitFTQPosition.ptr := commitPacket.ftqPtr
  commitFTQPosition.offset := commitPacket.endOffset

  when(reset.asBool || !hasInst || fire) {
    cursor := 0.U
  }.elsewhen(commitValid) {
    cursor := cursor + 1.U
  }
  when(commitValid) {
    assert(instPort(cursor).valid, "soft backend committed an empty slot")
  }

  def bytePc(half: UInt): UInt = {
    val low = Cat(half, 0.U(InstOffsetWidth.W))
    Cat(0.U((64 - low.getWidth).W), low)
  }
  def privMode(bits: UInt): PrivMode.Type = {
    MuxLookup(bits, PrivMode.mMode)(
      Seq(
        0.U -> PrivMode.uMode,
        1.U -> PrivMode.sMode,
        3.U -> PrivMode.mMode,
      )
    )
  }

  val dpi = Module(new IdealBackendDpi)
  val pageFault = commitPacket.except === FrontendExcept.pageFault
  val accessFault = commitPacket.except === FrontendExcept.accessFault
  dpi.enable := commitValid
  dpi.pc := bytePc(pred.pc)
  dpi.inst := commitPacket.inst
  dpi.page_fault := Mux(pageFault, 1.U(8.W), 0.U(8.W))
  dpi.access_fault := Mux(accessFault, 1.U(8.W), 0.U(8.W))

  val sequential = bytePc(pred.nextPC)
  val predicted = Mux(pred.taken, bytePc(pred.target), sequential)
  val wrongNext = commitValid && dpi.arch_pc =/= predicted
  val redirectNext = Wire(new BackendRedirect)
  redirectNext.redirectPosition := commitFTQPosition
  redirectNext.taken := commitValid && dpi.arch_pc =/= sequential
  redirectNext.target := dpi.arch_pc(vaddrWidth - 1, InstOffsetWidth)
  redirectNext.mispredict := wrongNext
  redirectNext.cfiAttribute := 0.U.asTypeOf(new CFIAttribute)
  // flush clears the instruction valid in this cycle. Sending redirect
  // combinationally would loop back into commitValid.
  val redirectValid = RegNext(wrongNext, false.B)
  val redirect = RegNext(redirectNext)

  io.toFrontend.commit.valid := commitValid
  io.toFrontend.commit.bits := commitFTQPosition
  io.toFrontend.predQuery := commitFTQPosition
  io.toFrontend.redirect.valid := redirectValid
  io.toFrontend.redirect.bits := redirect

  // The instruction already fetched with the old translation. Keep the new
  // privilege and satp for the cycles after this commit.
  val archPriv = RegInit(3.U(2.W))
  val archMstatus = RegInit(0.U(64.W))
  val archSatp = RegInit(0.U(64.W))
  when(commitValid) {
    archPriv := dpi.csr(IdealBackendDpi.privilege)(1, 0)
    archMstatus := dpi.csr(IdealBackendDpi.mstatus)
    archSatp := dpi.csr(IdealBackendDpi.satp)
  }
  io.mmuCtr.privMode := privMode(archPriv)
  io.mmuCtr.satp := archSatp
  io.mmuCtr.mstatusMPRV := archMstatus(17)
  io.mmuCtr.mstatusMPP := privMode(archMstatus(12, 11))
  io.mmuCtr.mstatusSUM := archMstatus(18)
  io.mmuCtr.mstatusMXR := archMstatus(19)
  io.mmuCtr.pmpCFG.foreach(_ := 0.U)
  io.mmuCtr.pmpAddr.foreach(_ := 0.U)

  // delay would shift valid and the payload together. 0 samples this cycle.
  val diff = DifftestModule(new DiffArchCommit)
  diff.valid := commitValid
  diff.commit(0).valid := true.B
  diff.commit(0).skip := dpi.mmio(0)
  diff.xrf.zip(dpi.gpr).foreach { case (dst, src) => dst := src }
  diff.frf.zip(dpi.fpr).foreach { case (dst, src) => dst := src }
  val csrDst = Seq(
    diff.csr.privilege,
    diff.csr.mstatus,
    diff.csr.sstatus,
    diff.csr.mepc,
    diff.csr.sepc,
    diff.csr.mtval,
    diff.csr.stval,
    diff.csr.mtvec,
    diff.csr.stvec,
    diff.csr.mcause,
    diff.csr.scause,
    diff.csr.satp,
    diff.csr.mip,
    diff.csr.mie,
    diff.csr.mscratch,
    diff.csr.sscratch,
    diff.csr.mideleg,
    diff.csr.medeleg,
    diff.csr.fcsr,
  )
  require(csrDst.length == dpi.csr.length)
  csrDst.zip(dpi.csr).foreach { case (dst, src) => dst := src }
  diff.pc := dpi.arch_pc
}
