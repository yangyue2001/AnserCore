#ifndef SIM_SESSION_SESSION_H
#define SIM_SESSION_SESSION_H

#include <cstdint>
#include <memory>
#include <vector>

#include "dpi-c/dpi.h"
#include "platform/platform.h"
#include "service/service.h"

enum class SessionState {
  kRunning,
  kNormal,
  kError,
};

class Session {
 public:
  explicit Session(int argc, char** argv);
  ~Session();

  void run();
  void finish();
  SessionState state() const { return state_; }

  friend void session_fail();
  friend void session_progress();

 private:
  SessionState state_;
  // Memory must exist before difftest copies the loaded image into the ref.
  std::vector<std::unique_ptr<SimService>> services_;
  std::vector<std::unique_ptr<DpiModule>> dpi_;
  std::unique_ptr<Platform> platform_;
  uint32_t commit_timeout_;
  uint64_t idle_;
};

class OptionSet;
void register_session_options(OptionSet& options);
void session_fail();
void session_progress();

#endif
