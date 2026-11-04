#include "VSoftMmuProbe.h"

#include <cstdint>
#include <cstdio>
#include <cstring>

#include "service/mmu/mmu.h"
#include "verilated.h"

namespace {

constexpr uint64_t kRamBase = 0x80000000ull;
constexpr uint64_t kRamBytes = 64ull * 1024ull;
constexpr unsigned char kFetch = 0;

uint64_t ram[kRamBytes / 8];
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

uint64_t pte(uint64_t ppn, uint64_t flags) { return (ppn << 10) | flags; }

void store(uint64_t pa, uint64_t value) {
  uint64_t offset = pa - kRamBase;
  ram[offset / 8] = value;
}

// Sv48 map of VA 0x80002000 -> PA 0x80004000, fetch-only, supervisor page.
void install_page_table() {
  std::memset(ram, 0, sizeof(ram));
  store(0x80000000ull, pte(0x80001, 1));
  store(0x80001000ull + 2 * 8, pte(0x80002, 1));
  store(0x80002000ull, pte(0x80003, 1));
  store(0x80003000ull + 2 * 8, pte(0x80004, 0x49));
}

MmuReq fetch_req(unsigned char priv, uint64_t satp, uint64_t vpn36) {
  MmuReq req{};
  uint64_t va = vpn36 << 12;
  req.priv = priv;
  req.va_hi = static_cast<unsigned>((va >> 57) & 0x7f);
  req.vpn4 = static_cast<unsigned>((va >> 48) & 0x1ff);
  req.vpn3 = static_cast<unsigned>((va >> 39) & 0x1ff);
  req.vpn2 = static_cast<unsigned>((va >> 30) & 0x1ff);
  req.vpn1 = static_cast<unsigned>((va >> 21) & 0x1ff);
  req.vpn0 = static_cast<unsigned>((va >> 12) & 0x1ff);
  req.page_off = 0;
  req.len = 1;
  req.satp = satp;
  req.kind = kFetch;
  req.pmpcfg0 = 0x1f;
  req.pmpaddr[0] = (1ull << 53) - 1;
  return req;
}

void check_ref(IdealMmu& mmu, const char* name, unsigned char priv, uint64_t satp,
               uint64_t vpn, uint64_t ppn, int page_fault) {
  MmuResp resp = mmu.translate(fetch_req(priv, satp, vpn));
  expect(name, resp.ppn == ppn && resp.page_fault == page_fault &&
                    resp.access_fault == 0,
         resp.ppn | (static_cast<uint64_t>(resp.page_fault) << 56) |
             (static_cast<uint64_t>(resp.access_fault) << 57),
         ppn | (static_cast<uint64_t>(page_fault) << 56));
}

struct RtlResp {
  int hit;
  int page_fault;
  int access_fault;
  uint64_t ppn;
};

RtlResp drive(VSoftMmuProbe* top, int reset, int valid, int priv, uint64_t satp,
              uint64_t vpn) {
  top->reset = reset;
  top->io_reqValid = valid;
  top->io_priv = priv;
  top->io_satp = satp;
  top->io_sum = 0;
  top->io_mxr = 0;
  top->io_vpn = vpn;
  top->clock = 0;
  top->eval();
  RtlResp resp;
  resp.hit = top->io_hit;
  resp.page_fault = top->io_pageFault;
  resp.access_fault = top->io_accessFault;
  resp.ppn = top->io_ppn;
  return resp;
}

void check_rtl(VSoftMmuProbe* top, const char* name, int reset, int valid, int priv,
               uint64_t satp, uint64_t vpn, int hit, uint64_t ppn, int page_fault,
               int access_fault) {
  RtlResp resp = drive(top, reset, valid, priv, satp, vpn);
  uint64_t actual = resp.ppn | (static_cast<uint64_t>(resp.hit) << 60) |
                    (static_cast<uint64_t>(resp.page_fault) << 61) |
                    (static_cast<uint64_t>(resp.access_fault) << 62);
  uint64_t wanted = ppn | (static_cast<uint64_t>(hit) << 60) |
                    (static_cast<uint64_t>(page_fault) << 61) |
                    (static_cast<uint64_t>(access_fault) << 62);
  expect(name, resp.hit == hit && resp.ppn == ppn && resp.page_fault == page_fault &&
                    resp.access_fault == access_fault,
         actual, wanted);
}

}  // namespace

extern "C" unsigned char memory_covers(uint64_t offset, uint64_t len) {
  if (len == 0 || offset > kRamBytes || len > kRamBytes - offset) {
    return 0;
  }
  return 1;
}

extern "C" uint64_t memory_read(uint64_t offset) {
  if ((offset & 7ull) != 0 || offset >= kRamBytes) {
    return 0;
  }
  return ram[offset / 8];
}

int main() {
  install_page_table();
  IdealMmu mmu;
  SimContext context{};
  if (!mmu.init(context)) {
    std::printf("FAIL mmu init\n");
    return 1;
  }

  constexpr uint64_t kBare = 0;
  constexpr uint64_t kSv48 = (9ull << 60) | 0x80000ull;
  constexpr uint64_t kIdentVpn = 0x80001ull;
  constexpr uint64_t kMappedVpn = 0x80002ull;
  constexpr uint64_t kMissVpn = 0x80002ull | (1ull << 27);

  check_ref(mmu, "ref M-mode identity", 3, kSv48, kIdentVpn, kIdentVpn, 0);
  check_ref(mmu, "ref bare identity", 1, kBare, kIdentVpn, kIdentVpn, 0);
  check_ref(mmu, "ref Sv48 fetch", 1, kSv48, kMappedVpn, 0x80004ull, 0);
  check_ref(mmu, "ref Sv48 miss", 1, kSv48, kMissVpn, 0, 1);

  auto* top = new VSoftMmuProbe;
  top->io_sum = 0;
  top->io_mxr = 0;
  check_rtl(top, "rtl reset blocks", 1, 1, 3, kSv48, kIdentVpn, 0, 0, 0, 0);
  check_rtl(top, "rtl idle", 0, 0, 3, kSv48, kIdentVpn, 0, 0, 0, 0);
  check_rtl(top, "rtl M-mode identity", 0, 1, 3, kSv48, kIdentVpn, 1, kIdentVpn, 0, 0);
  check_rtl(top, "rtl bare identity", 0, 1, 1, kBare, kIdentVpn, 1, kIdentVpn, 0, 0);
  check_rtl(top, "rtl Sv48 fetch", 0, 1, 1, kSv48, kMappedVpn, 1, 0x80004ull, 0, 0);
  check_rtl(top, "rtl Sv48 miss", 0, 1, 1, kSv48, kMissVpn, 1, 0, 1, 0);

  expect("rtl pmp fetch", top->io_allowFetch == 1, top->io_allowFetch, 1);
  expect("rtl pmp load", top->io_allowLoad == 1, top->io_allowLoad, 1);
  expect("rtl pmp store", top->io_allowStore == 1, top->io_allowStore, 1);
  expect("rtl pma ld", top->io_pmaLd == 1, top->io_pmaLd, 1);
  expect("rtl pma st", top->io_pmaSt == 1, top->io_pmaSt, 1);
  expect("rtl pma instr", top->io_pmaInstr == 1, top->io_pmaInstr, 1);
  expect("rtl pma cacheable", top->io_pmaCacheable == 1, top->io_pmaCacheable, 1);
  expect("rtl pma memory", top->io_pmaMemory == 1, top->io_pmaMemory, 1);

  top->final();
  delete top;
  mmu.finish(0);
  if (failures != 0) {
    std::printf("SOFTMMU_FAIL %d\n", failures);
    return 1;
  }
  std::printf("SOFTMMU_OK\n");
  return 0;
}
