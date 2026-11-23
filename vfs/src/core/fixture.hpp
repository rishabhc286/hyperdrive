// SPDX-License-Identifier: GPL-3.0-only
// FIXTURE MODE (development/testing only): an in-process stand-in for the agent's VFS routes with
// fixed, clearly labeled test data. Only enabled by the explicit `--fixture-mount <letter>` command
// line flag; the desktop never passes it. It is not cloud integration and is labeled as such in
// the volume label ("HyperDrive FIXTURE") and in the account directory name.
#pragma once

#include <memory>

#include "transport.hpp"

namespace hd {

std::unique_ptr<AgentTransport> MakeFixtureTransport();

}  // namespace hd
