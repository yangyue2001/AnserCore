package ansercore.mmu.softMMU

import chisel3._
import chisel3.util._
import ansercore.FrontendToMMUIO
import ansercore.MMUToFrontendIO
import ansercore.mmu.MMUBundle
import ansercore.mmu.MMUCtr
import ansercore.mmu.MMUModule

// rtl-sim 的 mmu_req 在求值时调用，output 当拍写回。必须当拍返回。
// 参数顺序跟 rtl-sim/dpi-c/softMMU/mmu.cpp 的 mmu_req 一致。最后一个输出是字节物理地址。
class SoftMmuDpi extends chisel3.experimental.ExtModule {
  override def desiredName = "SoftMmuDpi"

  val req_valid = IO(Input(Bool()))
  val priv = IO(Input(UInt(8.W)))
  val mpp = IO(Input(UInt(8.W)))
  val mprv = IO(Input(UInt(8.W)))
  val sum = IO(Input(UInt(8.W)))
  val mxr = IO(Input(UInt(8.W)))
  val kind = IO(Input(UInt(8.W)))
  val satp = IO(Input(UInt(64.W)))
  val vaddr = IO(Input(UInt(64.W)))
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
  val paddr = IO(Output(UInt(64.W)))
  val page_fault = IO(Output(UInt(8.W)))
  val access_fault = IO(Output(UInt(8.W)))

  // 结果走 mmu_req 的 output。命中分支不能再给 paddr_w 赋值，否则会盖住 DPI 回写。
  setInline(
    s"$desiredName.sv",
    s"""import "DPI-C" context function void mmu_req(
       |  input byte unsigned priv,
       |  input byte unsigned mpp,
       |  input byte unsigned mprv,
       |  input byte unsigned sum,
       |  input byte unsigned mxr,
       |  input byte unsigned kind,
       |  input longint unsigned satp,
       |  input longint unsigned vaddr,
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
       |  output byte unsigned page_fault,
       |  output byte unsigned access_fault,
       |  output longint unsigned paddr
       |);
       |module $desiredName(
       |  input req_valid,
       |  input [7:0] priv,
       |  input [7:0] mpp,
       |  input [7:0] mprv,
       |  input [7:0] sum,
       |  input [7:0] mxr,
       |  input [7:0] kind,
       |  input [63:0] satp,
       |  input [63:0] vaddr,
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
       |  output [63:0] paddr,
       |  output [7:0] page_fault,
       |  output [7:0] access_fault
       |);
       |  longint unsigned paddr_w;
       |  byte unsigned page_fault_w;
       |  byte unsigned access_fault_w;
       |
       |  always @(*) begin
       |    if (req_valid) begin
       |      mmu_req(
       |        priv, mpp, mprv, sum, mxr, kind, satp, vaddr, pmpcfg0, pmpcfg2,
       |        pmpaddr0, pmpaddr1, pmpaddr2, pmpaddr3, pmpaddr4, pmpaddr5, pmpaddr6, pmpaddr7,
       |        pmpaddr8, pmpaddr9, pmpaddr10, pmpaddr11, pmpaddr12, pmpaddr13, pmpaddr14, pmpaddr15,
       |        page_fault_w, access_fault_w, paddr_w
       |      );
       |    end else begin
       |      page_fault_w = 8'h0;
       |      access_fault_w = 8'h0;
       |      paddr_w = 64'h0;
       |    end
       |  end
       |
       |  assign paddr = paddr_w;
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
  val va = Cat(itlbReq.bits, 0.U(PageOffsetWidth.W))
  val va64 = Cat(Fill(64 - vaddrWidth, va(vaddrWidth - 1)), va)

  dpi.req_valid := translate
  dpi.priv := dpiUInt(mmuCtr.privMode.asUInt, 8)
  dpi.mpp := dpiUInt(mmuCtr.mstatusMPP.asUInt, 8)
  dpi.mprv := dpiUInt(mmuCtr.mstatusMPRV.asUInt, 8)
  dpi.sum := dpiUInt(mmuCtr.mstatusSUM.asUInt, 8)
  dpi.mxr := dpiUInt(mmuCtr.mstatusMXR.asUInt, 8)
  // AccessType：取指 0，load 1，store 2。itlb 只翻译取指。
  dpi.kind := 0.U(8.W)
  dpi.satp := mmuCtr.satp
  dpi.vaddr := va64
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

  // mmu_req 回的是字节物理地址。itlb 要页号，页内偏移在低 PageOffsetWidth 位。
  toFrontend.itlbResp.ppn := Mux(translate, dpi.paddr(ppnWidth + PageOffsetWidth - 1, PageOffsetWidth), 0.U)
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
