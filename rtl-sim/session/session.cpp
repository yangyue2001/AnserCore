#include "session/session.h"

#include <cinttypes>
#include <cstdio>

#include "session/options.h"

namespace {

Session* current_session = nullptr;
uint32_t commit_timeout = 0;

}  // namespace

void register_session_options(OptionSet& options) {
  options.add("commit-timeout", &commit_timeout,
              "cycles without a commit before failing; 0 disables");
}

Session::Session(int argc, char** argv)
    : state_(SessionState::kNormal),
      services_(create_services()),
      dpi_(create_dpi()),
      platform_(create_platform(argc, argv)),
      commit_timeout_(commit_timeout),
      idle_(0) {
  current_session = this;
}

Session::~Session() { current_session = nullptr; }

void session_fail() {
  if (current_session != nullptr) {
    current_session->state_ = SessionState::kError;
  }
}

void session_progress() {
  if (current_session != nullptr) {
    current_session->idle_ = 0;
  }
}

void Session::run() {
  state_ = SessionState::kRunning;
  while (!platform_->get_finsh() && state_ == SessionState::kRunning) {
    if (commit_timeout_ != 0) {
      idle_ += 1;
    }
    platform_->tick();
    for (const std::unique_ptr<SimService>& service : services_) {
      if (!service->on_posedge()) {
        state_ = SessionState::kError;
        break;
      }
    }
    if (commit_timeout_ != 0 && idle_ >= commit_timeout_) {
      std::fprintf(stderr, "no commit for %" PRIu64 " cycles\n", idle_);
      state_ = SessionState::kError;
    }
  }
  if (platform_->get_error()) {
    state_ = SessionState::kError;
  } else if (state_ == SessionState::kRunning) {
    state_ = SessionState::kNormal;
  }
}

void Session::finish() {
  platform_->finish();
  bool error = state_ == SessionState::kError;
  for (const std::unique_ptr<SimService>& service : services_) {
    service->finish(error);
  }
  switch (state_) {
    case SessionState::kError:
      break;
    case SessionState::kNormal:
      break;
    case SessionState::kRunning:
      break;
  }
}
