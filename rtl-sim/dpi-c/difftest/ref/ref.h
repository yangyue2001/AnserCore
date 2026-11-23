#ifndef SIM_DPI_DIFFTEST_REF_H
#define SIM_DPI_DIFFTEST_REF_H

#include <cstddef>
#include <cstdint>
#include <string>

#include "dpi-c/isa_state.h"

// ABI a reference shared library must export. Field order is part of that ABI.
enum { DIFFTEST_TO_DUT = 0, DIFFTEST_TO_REF = 1 };

// A constructed library has been dlopen'd and difftest_init has returned.
class RefLibrary {
public:
  explicit RefLibrary(const std::string &path);
  ~RefLibrary();

  RefLibrary(const RefLibrary &) = delete;
  RefLibrary &operator=(const RefLibrary &) = delete;

  void memcpy(uint64_t addr, void *buf, size_t n, bool direction);
  void regcpy(IsaState *state, bool direction);
  void exec(uint64_t n);
  void raise_trap(uint64_t cause, uint64_t tval);

private:
  void *handle_;
  void (*memcpy_)(uint64_t, void *, size_t, bool);
  void (*regcpy_)(void *, bool);
  void (*exec_)(uint64_t);
  void (*raise_trap_)(uint64_t, uint64_t);
};

#endif
