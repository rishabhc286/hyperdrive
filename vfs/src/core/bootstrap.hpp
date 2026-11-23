// SPDX-License-Identifier: GPL-3.0-only
// The single bounded JSON line Electron writes to the VFS's stdin. Secrets never travel on the
// command line, in the environment or in files.
#pragma once

#include <string>

namespace hd {

constexpr int kVfsProtocolVersion = 1;
constexpr std::size_t kMaxBootstrapBytes = 4096;

struct Bootstrap {
    int agentPort = 0;
    std::string capability;  // hdv_... (never logged)
    wchar_t driveLetter = L'\0';
};

// Strict: exact keys, types and ranges. On failure returns false and a stable error code.
bool ParseBootstrap(const std::string& line, Bootstrap& out, std::string& errorCode);
bool IsAllowedDriveLetter(wchar_t letter);

}  // namespace hd
