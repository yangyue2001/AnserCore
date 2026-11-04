#include "VSoftBackendProbe.h"

#include <cstdint>
#include <cstdio>

#include "service/ideal/ideal.h"
#include "verilated.h"

namespace {

int failures = 0;

void expect(const char* name, bool cond, uint64_t actual, uint64_t wanted) {
  if (cond) {
    std::printf("PASS %s\n", name);
    return;
  }
  std::printf("FAIL %s actual=0x%llx wanted=0x%llx\n", name,
              static_cast<unsigned long long>(actual),
              static_cast<unsigned long long>(wanted));
  failures++;
}

uint64_t half(uint64_t byte_pc) { return byte_pc >> 1; }

struct Slot {
  bool valid;
  uint32_t inst;
  uint32_t ftq;
  uint32_t offset;
  uint64_t pc;
  uint64_t next_pc;
  bool taken;
  uint64_t target;
};

Slot empty_slot() { return Slot{}; }

Slot make_slot(uint32_t inst, uint32_t ftq, uint32_t offset, uint64_t pc, bool taken,
               uint64_t target) {
  Slot slot{};
  slot.valid = true;
  slot.inst = inst;
  slot.ftq = ftq;
  slot.offset = offset;
  slot.pc = half(pc);
  slot.next_pc = half(pc + 4);
  slot.taken = taken;
  slot.target = half(target);
  return slot;
}

void drive(VSoftBackendProbe* top, const Slot group[4]) {
  top->io_instValid_0 = group[0].valid;
  top->io_instValid_1 = group[1].valid;
  top->io_instValid_2 = group[2].valid;
  top->io_instValid_3 = group[3].valid;
  top->io_inst_0 = group[0].inst;
  top->io_inst_1 = group[1].inst;
  top->io_inst_2 = group[2].inst;
  top->io_inst_3 = group[3].inst;
  top->io_ftqValue_0 = group[0].ftq;
  top->io_ftqValue_1 = group[1].ftq;
  top->io_ftqValue_2 = group[2].ftq;
  top->io_ftqValue_3 = group[3].ftq;
  top->io_ftqFlag_0 = 0;
  top->io_ftqFlag_1 = 0;
  top->io_ftqFlag_2 = 0;
  top->io_ftqFlag_3 = 0;
  top->io_endOffset_0 = group[0].offset;
  top->io_endOffset_1 = group[1].offset;
  top->io_endOffset_2 = group[2].offset;
  top->io_endOffset_3 = group[3].offset;
  top->io_predPc_0 = group[0].pc;
  top->io_predPc_1 = group[1].pc;
  top->io_predPc_2 = group[2].pc;
  top->io_predPc_3 = group[3].pc;
  top->io_predNext_0 = group[0].next_pc;
  top->io_predNext_1 = group[1].next_pc;
  top->io_predNext_2 = group[2].next_pc;
  top->io_predNext_3 = group[3].next_pc;
  top->io_predTaken_0 = group[0].taken;
  top->io_predTaken_1 = group[1].taken;
  top->io_predTaken_2 = group[2].taken;
  top->io_predTaken_3 = group[3].taken;
  top->io_predTarget_0 = group[0].target;
  top->io_predTarget_1 = group[1].target;
  top->io_predTarget_2 = group[2].target;
  top->io_predTarget_3 = group[3].target;
}

void idle(VSoftBackendProbe* top) {
  Slot group[4] = {empty_slot(), empty_slot(), empty_slot(), empty_slot()};
  drive(top, group);
}

void settle(VSoftBackendProbe* top) {
  top->clock = 0;
  top->eval();
}

void edge(VSoftBackendProbe* top) {
  top->clock = 1;
  top->eval();
  top->clock = 0;
  top->eval();
}

int ready_of(const VSoftBackendProbe* top, int idx) {
  if (idx == 0) return top->io_ready_0;
  if (idx == 1) return top->io_ready_1;
  if (idx == 2) return top->io_ready_2;
  return top->io_ready_3;
}

void expect_ready(const VSoftBackendProbe* top, const char* name, int r0, int r1, int r2,
                  int r3) {
  int actual = ready_of(top, 0) | (ready_of(top, 1) << 1) | (ready_of(top, 2) << 2) |
               (ready_of(top, 3) << 3);
  int wanted = r0 | (r1 << 1) | (r2 << 2) | (r3 << 3);
  expect(name, actual == wanted, actual, wanted);
}

void expect_commit(const VSoftBackendProbe* top, const char* name, int valid, uint32_t ftq,
                   uint32_t offset) {
  if (!valid) {
    expect(name, top->io_commitValid == 0, top->io_commitValid, 0);
    return;
  }
  uint64_t actual = (static_cast<uint64_t>(top->io_commitValue) << 8) | top->io_commitOffset;
  uint64_t wanted = (static_cast<uint64_t>(ftq) << 8) | offset;
  expect(name,
         top->io_commitValid == 1 && top->io_commitFlag == 0 && top->io_commitValue == ftq &&
             top->io_commitOffset == offset,
         actual, wanted);
}

void expect_query(const VSoftBackendProbe* top, const char* name, int valid, uint32_t ftq,
                  uint32_t offset) {
  if (!valid) {
    expect(name, top->io_queryValue == 0 && top->io_queryOffset == 0 && top->io_queryFlag == 0,
           (static_cast<uint64_t>(top->io_queryValue) << 8) | top->io_queryOffset, 0);
    return;
  }
  expect(name, top->io_queryFlag == 0 && top->io_queryValue == ftq && top->io_queryOffset == offset,
         (static_cast<uint64_t>(top->io_queryValue) << 8) | top->io_queryOffset,
         (static_cast<uint64_t>(ftq) << 8) | offset);
}

// 丢掉当前这组，并清掉上一拍的提交和重定向。
void flush_group(VSoftBackendProbe* top) {
  idle(top);
  settle(top);
  edge(top);
  settle(top);
}

}  // namespace

extern "C" unsigned char memory_covers(uint64_t, uint64_t) { return 0; }

extern "C" uint64_t memory_read(uint64_t) { return 0; }

extern "C" void memory_write(uint64_t, uint64_t, unsigned char) {}

int main() {
  IdealBackend backend;
  SimContext context{};
  if (!backend.init(context)) {
    std::printf("FAIL ideal init\n");
    return 1;
  }

  auto* top = new VSoftBackendProbe;
  idle(top);
  top->reset = 1;
  settle(top);
  edge(top);
  edge(top);

  constexpr uint32_t kAddi1 = 0x00100093u;
  constexpr uint32_t kBeq = 0x00000463u;
  constexpr uint32_t kAddi2 = 0x00200113u;
  constexpr uint32_t kAddi3 = 0x00300193u;
  constexpr uint32_t kAddi4 = 0x00400213u;
  constexpr uint32_t kAddi5 = 0x00500293u;
  constexpr uint32_t kCsrrwSatp = 0x18009073u;
  constexpr uint32_t kAddi6 = 0x00100313u;
  constexpr uint32_t kSlli = 0x01231313u;
  constexpr uint32_t kCsrrs = 0x30032073u;
  constexpr uint32_t kAddi7 = 0x00700393u;

  Slot blocked[4] = {empty_slot(), empty_slot(), empty_slot(), empty_slot()};
  blocked[0] = make_slot(kAddi1, 1, 0, 0x80000000ull, false, 0x80000004ull);
  drive(top, blocked);
  settle(top);
  expect_commit(top, "reset blocks commit", 0, 0, 0);
  expect_ready(top, "reset blocks ready", 0, 0, 0, 0);
  expect("reset no redirect", top->io_redirectValid == 0, top->io_redirectValid, 0);
  edge(top);
  expect_commit(top, "reset edge does not retire", 0, 0, 0);

  top->reset = 0;
  settle(top);
  expect_query(top, "addi query", 1, 1, 0);
  expect_ready(top, "addi ready", 1, 0, 0, 0);
  expect_commit(top, "addi not retired yet", 0, 0, 0);
  expect("addi no redirect yet", top->io_redirectValid == 0, top->io_redirectValid, 0);
  expect("reset priv", top->io_priv == 3, top->io_priv, 3);
  edge(top);
  expect_commit(top, "addi commit", 1, 1, 0);
  expect("addi no redirect", top->io_redirectValid == 0, top->io_redirectValid, 0);
  expect("addi priv", top->io_priv == 3, top->io_priv, 3);
  expect("addi satp", top->io_satp == 0, top->io_satp, 0);
  expect("addi sum", top->io_sum == 0, top->io_sum, 0);
  expect("addi mxr", top->io_mxr == 0, top->io_mxr, 0);
  expect("addi mprv", top->io_mprv == 0, top->io_mprv, 0);
  flush_group(top);
  expect_commit(top, "idle after addi", 0, 0, 0);
  expect("held priv", top->io_priv == 3, top->io_priv, 3);
  expect("held satp", top->io_satp == 0, top->io_satp, 0);

  Slot branch[4] = {empty_slot(), empty_slot(), empty_slot(), empty_slot()};
  branch[0] = make_slot(kBeq, 2, 1, 0x80000004ull, false, 0x80000008ull);
  drive(top, branch);
  settle(top);
  expect_query(top, "beq query", 1, 2, 1);
  expect_ready(top, "beq is the only instruction", 1, 0, 0, 0);
  expect("beq redirect waits for the edge", top->io_redirectValid == 0, top->io_redirectValid, 0);
  edge(top);
  expect_commit(top, "beq commit", 1, 2, 1);
  expect("beq redirect", top->io_redirectValid == 1, top->io_redirectValid, 1);
  expect("beq taken", top->io_redirectTaken == 1, top->io_redirectTaken, 1);
  expect("beq mispredict", top->io_redirectMispredict == 1, top->io_redirectMispredict, 1);
  expect("beq conditional", top->io_redirectCond == 1, top->io_redirectCond, 1);
  expect("beq target", top->io_redirectTarget == half(0x8000000cull), top->io_redirectTarget,
         half(0x8000000cull));
  expect("beq position",
         top->io_redirectValue == 2 && top->io_redirectOffset == 1 && top->io_redirectFlag == 0,
         (static_cast<uint64_t>(top->io_redirectValue) << 8) | top->io_redirectOffset,
         (2ull << 8) | 1ull);
  flush_group(top);
  expect("redirect is one result cycle", top->io_redirectValid == 0, top->io_redirectValid, 0);

  Slot group[4];
  group[0] = make_slot(kAddi2, 3, 0, 0x8000000cull, false, 0x80000010ull);
  group[1] = make_slot(kAddi3, 3, 1, 0x80000010ull, false, 0x80000014ull);
  group[2] = make_slot(kAddi4, 3, 2, 0x80000014ull, false, 0x80000018ull);
  group[3] = make_slot(kAddi5, 3, 3, 0x80000018ull, false, 0x8000001cull);
  drive(top, group);
  for (int step = 0; step < 4; ++step) {
    settle(top);
    char query_name[32];
    char ready_name[32];
    char commit_name[32];
    std::snprintf(query_name, sizeof(query_name), "group query %d", step);
    std::snprintf(ready_name, sizeof(ready_name), "group ready %d", step);
    std::snprintf(commit_name, sizeof(commit_name), "group commit %d", step);
    expect_query(top, query_name, 1, 3, step);
    if (step < 3) {
      expect_ready(top, ready_name, 0, 0, 0, 0);
    } else {
      expect_ready(top, ready_name, 1, 1, 1, 1);
    }
    expect("group no redirect", top->io_redirectValid == 0, top->io_redirectValid, 0);
    edge(top);
    expect_commit(top, commit_name, 1, 3, step);
  }
  flush_group(top);
  expect_ready(top, "group released", 0, 0, 0, 0);
  expect_commit(top, "group idle", 0, 0, 0);

  Slot satp[4] = {empty_slot(), empty_slot(), empty_slot(), empty_slot()};
  satp[0] = make_slot(kCsrrwSatp, 4, 0, 0x8000001cull, false, 0x80000020ull);
  drive(top, satp);
  settle(top);
  expect_ready(top, "satp ready", 1, 0, 0, 0);
  edge(top);
  expect_commit(top, "satp commit", 1, 4, 0);
  expect("satp write", top->io_satp == 1, top->io_satp, 1);
  expect("satp priv", top->io_priv == 3, top->io_priv, 3);
  flush_group(top);
  expect("satp holds", top->io_satp == 1, top->io_satp, 1);

  Slot one[4] = {empty_slot(), empty_slot(), empty_slot(), empty_slot()};
  one[0] = make_slot(kAddi6, 5, 0, 0x80000020ull, false, 0x80000024ull);
  drive(top, one);
  settle(top);
  edge(top);
  expect_commit(top, "sum addi", 1, 5, 0);
  flush_group(top);

  one[0] = make_slot(kSlli, 6, 0, 0x80000024ull, false, 0x80000028ull);
  drive(top, one);
  settle(top);
  edge(top);
  expect_commit(top, "sum slli", 1, 6, 0);
  flush_group(top);

  one[0] = make_slot(kCsrrs, 7, 2, 0x80000028ull, false, 0x8000002cull);
  drive(top, one);
  settle(top);
  edge(top);
  expect_commit(top, "sum csrrs", 1, 7, 2);
  expect("mstatus sum", top->io_sum == 1, top->io_sum, 1);
  expect("mstatus mxr", top->io_mxr == 0, top->io_mxr, 0);
  expect("mstatus mprv", top->io_mprv == 0, top->io_mprv, 0);
  expect("mstatus mpp", top->io_mpp == 0, top->io_mpp, 0);
  expect("mstatus satp", top->io_satp == 1, top->io_satp, 1);
  expect("mstatus priv", top->io_priv == 3, top->io_priv, 3);
  flush_group(top);

  Slot split[4] = {empty_slot(), empty_slot(), empty_slot(), empty_slot()};
  split[0] = make_slot(kBeq, 8, 0, 0x8000002cull, false, 0x80000030ull);
  split[1] = make_slot(kAddi7, 8, 1, 0x80000030ull, false, 0x80000034ull);
  drive(top, split);
  settle(top);
  expect_query(top, "split issues the branch first", 1, 8, 0);
  expect_ready(top, "split blocks the younger instruction", 0, 0, 0, 0);
  edge(top);
  expect_commit(top, "split commits the branch", 1, 8, 0);
  expect_query(top, "split does not issue the younger instruction", 0, 0, 0);
  expect_ready(top, "split stays blocked", 0, 0, 0, 0);
  expect("split redirect", top->io_redirectValid == 1, top->io_redirectValid, 1);
  expect("split target", top->io_redirectTarget == half(0x80000034ull), top->io_redirectTarget,
         half(0x80000034ull));
  flush_group(top);
  expect("split redirect drops", top->io_redirectValid == 0, top->io_redirectValid, 0);

  top->final();
  delete top;
  backend.finish(0);
  if (failures != 0) {
    std::printf("SOFTBACKEND_FAIL %d\n", failures);
    return 1;
  }
  std::printf("SOFTBACKEND_OK\n");
  return 0;
}
