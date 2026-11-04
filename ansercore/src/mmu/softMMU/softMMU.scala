package ansercore.mmu.softMMU

import chisel3._
import chisel3.util._
import ansercore.FrontendToMMUIO
import ansercore.MMUToFrontendIO
import ansercore.mmu.MMUBundle
import ansercore.mmu.MMUCtr
import ansercore.mmu.MMUModule

// sim_verilator 的 mmu_req 在求值时调用，mmu_resp 把结果写回。必须当拍返回。
class SoftMmuDpi extends chisel3.experimental.ExtModule {
  override def desiredName = "SoftMmuDpi"

  val req_valid = IO(Input(Bool()))
  val priv = IO(Input(UInt(8.W)))
  val va_hi = IO(Input(UInt(32.W)))
  val vpn4 = IO(Input(UInt(32.W)))
  val vpn3 = IO(Input(UInt(32.W)))
  val vpn2 = IO(Input(UInt(32.W)))
  val vpn1 = IO(Input(UInt(32.W)))
  val vpn0 = IO(Input(UInt(32.W)))
  val page_off = IO(Input(UInt(32.W)))
  val len = IO(Input(UInt(32.W)))
  val satp = IO(Input(UInt(64.W)))
  val sum = IO(Input(UInt(8.W)))
  val mxr = IO(Input(UInt(8.W)))
  val pmpcfg0 = IO(Input(UInt(64.W)))
  val pmpcfg2 = IO(Input(UInt(64.W)))
  val pmpaddr0 = IO(Input(UInt(64.W)))
  val pmpaddr1 = IO(Input(UInt(64.W)))
  val pmpaddr2 = IO(Input(UInt(64.W)))
  val pmpaddr3 = IO(Input(UInt(64.W)))
  val pmpaddr4 = IO(Input(UInt(64.W)))
  val pmpaddr5 = IO(Input(UInt(64.W)))
  val pmpaddr6 = IO(Input(UInt(64.W)))
  val pmpaddr7 = IO(Input(UInt(64.W)))
  val pmpaddr8 = IO(Input(UInt(64.W)))
  val pmpaddr9 = IO(Input(UInt(64.W)))
  val pmpaddr10 = IO(Input(UInt(64.W)))
  val pmpaddr11 = IO(Input(UInt(64.W)))
  val pmpaddr12 = IO(Input(UInt(64.W)))
  val pmpaddr13 = IO(Input(UInt(64.W)))
  val pmpaddr14 = IO(Input(UInt(64.W)))
  val pmpaddr15 = IO(Input(UInt(64.W)))
  val kind = IO(Input(UInt(8.W)))
  val ppn = IO(Output(UInt(64.W)))
  val page_fault = IO(Output(UInt(8.W)))
  val access_fault = IO(Output(UInt(8.W)))

  // 命中分支不能先把 ppn_w 清零。Verilator 会因此丢掉 mmu_resp 的回写。
  setInline(
    s"$desiredName.sv",
    s"""import "DPI-C" context function void mmu_req(
       |  input byte unsigned priv,
       |  input int unsigned va_hi,
       |  input int unsigned vpn4,
       |  input int unsigned vpn3,
       |  input int unsigned vpn2,
       |  input int unsigned vpn1,
       |  input int unsigned vpn0,
       |  input int unsigned page_off,
       |  input int unsigned len,
       |  input longint unsigned satp,
       |  input byte unsigned sum,
       |  input byte unsigned mxr,
       |  input longint unsigned pmpcfg0,
       |  input longint unsigned pmpcfg2,
       |  input longint unsigned pmpaddr0,
       |  input longint unsigned pmpaddr1,
       |  input longint unsigned pmpaddr2,
       |  input longint unsigned pmpaddr3,
       |  input longint unsigned pmpaddr4,
       |  input longint unsigned pmpaddr5,
       |  input longint unsigned pmpaddr6,
       |  input longint unsigned pmpaddr7,
       |  input longint unsigned pmpaddr8,
       |  input longint unsigned pmpaddr9,
       |  input longint unsigned pmpaddr10,
       |  input longint unsigned pmpaddr11,
       |  input longint unsigned pmpaddr12,
       |  input longint unsigned pmpaddr13,
       |  input longint unsigned pmpaddr14,
       |  input longint unsigned pmpaddr15,
       |  input byte unsigned kind
       |);
       |module $desiredName(
       |  input req_valid,
       |  input [7:0] priv,
       |  input [31:0] va_hi,
       |  input [31:0] vpn4,
       |  input [31:0] vpn3,
       |  input [31:0] vpn2,
       |  input [31:0] vpn1,
       |  input [31:0] vpn0,
       |  input [31:0] page_off,
       |  input [31:0] len,
       |  input [63:0] satp,
       |  input [7:0] sum,
       |  input [7:0] mxr,
       |  input [63:0] pmpcfg0,
       |  input [63:0] pmpcfg2,
       |  input [63:0] pmpaddr0,
       |  input [63:0] pmpaddr1,
       |  input [63:0] pmpaddr2,
       |  input [63:0] pmpaddr3,
       |  input [63:0] pmpaddr4,
       |  input [63:0] pmpaddr5,
       |  input [63:0] pmpaddr6,
       |  input [63:0] pmpaddr7,
       |  input [63:0] pmpaddr8,
       |  input [63:0] pmpaddr9,
       |  input [63:0] pmpaddr10,
       |  input [63:0] pmpaddr11,
       |  input [63:0] pmpaddr12,
       |  input [63:0] pmpaddr13,
       |  input [63:0] pmpaddr14,
       |  input [63:0] pmpaddr15,
       |  input [7:0] kind,
       |  output [63:0] ppn,
       |  output [7:0] page_fault,
       |  output [7:0] access_fault
       |);
       |  export "DPI-C" function mmu_resp;
       |  longint unsigned ppn_w;
       |  byte unsigned page_fault_w;
       |  byte unsigned access_fault_w;
       |
       |  function void mmu_resp(
       |    input longint unsigned ppn,
       |    input byte unsigned page_fault,
       |    input byte unsigned access_fault
       |  );
       |    ppn_w = ppn;
       |    page_fault_w = page_fault;
       |    access_fault_w = access_fault;
       |  endfunction
       |
       |  // verilator lint_off LATCH
       |  always @(*) begin
       |    if (req_valid) begin
       |      mmu_req(
       |        priv, va_hi, vpn4, vpn3, vpn2, vpn1, vpn0, page_off, len, satp, sum, mxr, pmpcfg0, pmpcfg2,
       |        pmpaddr0, pmpaddr1, pmpaddr2, pmpaddr3, pmpaddr4, pmpaddr5, pmpaddr6, pmpaddr7,
       |        pmpaddr8, pmpaddr9, pmpaddr10, pmpaddr11, pmpaddr12, pmpaddr13, pmpaddr14, pmpaddr15, kind
       |      );
       |    end else begin
       |      ppn_w = 64'h0;
       |      page_fault_w = 8'h0;
       |      access_fault_w = 8'h0;
       |    end
       |  end
       |  // verilator lint_on LATCH
       |
       |  assign ppn = ppn_w;
       |  assign page_fault = page_fault_w;
       |  assign access_fault = access_fault_w;
       |endmodule
       |""".stripMargin,
  )
}

class SoftMMU extends MMUModule {
  class SoftMMUIO extends MMUBundle {
    val mmuCtr = Input(new MMUCtr)
    val fromFrontend = Flipped(new FrontendToMMUIO)
    val toFrontend = new MMUToFrontendIO
  }
  val io = IO(new SoftMMUIO)

  val mmuCtr = io.mmuCtr
  val itlbReq = io.fromFrontend.itlbReq
  val toFrontend = io.toFrontend

  val dpi = Module(new SoftMmuDpi)
  // Bool 也是 UInt。pad 会按原类型改宽度，Bool 不能变成 8 位。
  def dpiUInt(value: UInt, width: Int): UInt = {
    val valueWidth = value.getWidth
    require(valueWidth > 0 && valueWidth <= width)
    if (valueWidth == width) value else Cat(0.U((width - valueWidth).W), value)
  }
  val translate = itlbReq.valid && !reset.asBool
  // itlb 只有 VPN。页内偏移不影响 4KB 页的 PPN，超页的低位 VPN 已经在 vpn 里。
  val va = Cat(itlbReq.bits.vpn, 0.U(PageOffsetWidth.W))
  val va64 = Cat(Fill(64 - vaddrWidth, va(vaddrWidth - 1)), va)

  dpi.req_valid := translate
  dpi.priv := dpiUInt(mmuCtr.privMode.asUInt, 8)
  dpi.va_hi := dpiUInt(va64(63, 57), 32)
  dpi.vpn4 := dpiUInt(va64(56, 48), 32)
  dpi.vpn3 := dpiUInt(va64(47, 39), 32)
  dpi.vpn2 := dpiUInt(va64(38, 30), 32)
  dpi.vpn1 := dpiUInt(va64(29, 21), 32)
  dpi.vpn0 := dpiUInt(va64(20, 12), 32)
  dpi.page_off := 0.U(32.W)
  dpi.len := 1.U(32.W)
  dpi.satp := mmuCtr.satp
  dpi.sum := dpiUInt(mmuCtr.mstatusSUM, 8)
  dpi.mxr := dpiUInt(mmuCtr.mstatusMXR, 8)
  // PMP CSR 还没进 mmuCtr。全关时 S 态读页表会被拒绝，所以先放一项覆盖全部物理地址的 RWX。
  dpi.pmpcfg0 := "h1f".U(64.W)
  dpi.pmpcfg2 := 0.U(64.W)
  dpi.pmpaddr0 := ((BigInt(1) << 53) - 1).U(64.W)
  dpi.pmpaddr1 := 0.U(64.W)
  dpi.pmpaddr2 := 0.U(64.W)
  dpi.pmpaddr3 := 0.U(64.W)
  dpi.pmpaddr4 := 0.U(64.W)
  dpi.pmpaddr5 := 0.U(64.W)
  dpi.pmpaddr6 := 0.U(64.W)
  dpi.pmpaddr7 := 0.U(64.W)
  dpi.pmpaddr8 := 0.U(64.W)
  dpi.pmpaddr9 := 0.U(64.W)
  dpi.pmpaddr10 := 0.U(64.W)
  dpi.pmpaddr11 := 0.U(64.W)
  dpi.pmpaddr12 := 0.U(64.W)
  dpi.pmpaddr13 := 0.U(64.W)
  dpi.pmpaddr14 := 0.U(64.W)
  dpi.pmpaddr15 := 0.U(64.W)
  dpi.kind := 0.U(8.W)

  toFrontend.itlbResp.ppn := Mux(translate, dpi.ppn(ppnWidth - 1, 0), 0.U)
  toFrontend.itlbResp.hit := translate
  toFrontend.itlbResp.pageFault := translate && dpi.page_fault(0)
  toFrontend.itlbResp.accessFault := translate && dpi.access_fault(0)
  toFrontend.pmpResp.allowFetch := true.B
  toFrontend.pmpResp.allowLoad := true.B
  toFrontend.pmpResp.allowStore := true.B
  toFrontend.pmaResp.ld := true.B
  toFrontend.pmaResp.st := true.B
  toFrontend.pmaResp.instr := true.B
  toFrontend.pmaResp.cacheable := true.B
  toFrontend.pmaResp.memory := true.B
}
