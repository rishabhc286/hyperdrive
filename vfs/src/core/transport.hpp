// SPDX-License-Identifier: GPL-3.0-only
// Narrow transport between the VFS and the local agent. Today: authenticated loopback HTTP via
// WinHTTP (ADR 0004). Kept behind this interface so a later batch can change it without touching
// the filesystem logic.
#pragma once

#include <memory>
#include <string>

namespace hd {

struct HttpResult {
    enum class Kind { Ok, Timeout, Unreachable, TooLarge, Failed };
    Kind kind = Kind::Failed;
    int status = 0;
    std::string body;
};

class AgentTransport {
  public:
    virtual ~AgentTransport() = default;
    // POST a JSON body to a fixed agent route (e.g. L"/local/v1/vfs/list").
    virtual HttpResult Post(const std::wstring& path, const std::string& jsonBody) = 0;
};

struct TransportLimits {
    int resolveMs = 2000;
    int connectMs = 2000;
    int sendMs = 5000;
    int receiveMs = 35000;  // the agent's listing deadline is 30 s
    std::size_t maxResponseBytes = 8 * 1024 * 1024;
};

// Always http://127.0.0.1:<port>; no proxy, no redirects. The capability is sent as a bearer token
// and is never logged.
std::unique_ptr<AgentTransport> MakeWinHttpTransport(int port, const std::string& capability,
                                                     const TransportLimits& limits = {});

}  // namespace hd
