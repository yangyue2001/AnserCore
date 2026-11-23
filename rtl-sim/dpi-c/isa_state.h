#ifndef SIM_DPI_ISA_STATE_H
#define SIM_DPI_ISA_STATE_H

#include <cstdint>

// Architectural snapshot shared by DPI clients. Field order is part of the ABI.
struct IsaState {
  uint64_t gpr[32];
  uint64_t fpr[32];
  uint64_t pc;
  uint64_t priv;
  uint64_t mstatus;
  uint64_t sstatus;
  uint64_t mepc;
  uint64_t sepc;
  uint64_t mtval;
  uint64_t stval;
  uint64_t mtvec;
  uint64_t stvec;
  uint64_t mcause;
  uint64_t scause;
  uint64_t satp;
  uint64_t mip;
  uint64_t mie;
  uint64_t mscratch;
  uint64_t sscratch;
  uint64_t mideleg;
  uint64_t medeleg;
  uint64_t fcsr;
};

#endif
