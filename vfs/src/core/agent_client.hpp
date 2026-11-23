// SPDX-License-Identifier: GPL-3.0-only
// Typed, strictly validated calls to the agent's capability-scoped VFS routes.
#pragma once

#include <optional>
#include <string>
#include <vector>

#include "snapshot.hpp"
#include "transport.hpp"

namespace hd {

enum class Fault {
    None,
    NotFound,           // account or folder no longer exists / not visible
    AuthorizationLost,  // mount capability revoked, or the backend grant was rejected/expired
    Forbidden,
    NeedsReconnect,
    Busy,               // agent or backend rate/concurrency limit
    Timeout,
    Unavailable,        // agent or backend unreachable
    TooLarge,           // directory exceeds the supported listing limits
    Invalid,            // malformed response
};

const char* FaultCode(Fault f);

template <class T>
struct Result {
    Fault fault = Fault::None;
    T value{};
    bool ok() const { return fault == Fault::None; }
};

constexpr std::size_t kMaxListingEntries = 10000;

class AgentClient {
  public:
    explicit AgentClient(AgentTransport& transport, bool fixtureMode = false)
        : transport_(transport), fixtureMode_(fixtureMode) {}

    // Proves the capability is valid; returns the mount generation.
    Result<long long> Session();
    Result<std::vector<RemoteAccount>> Accounts();
    Result<std::vector<RemoteEntry>> List(const std::string& accountId, const std::optional<std::string>& locator);

  private:
    AgentTransport& transport_;
    bool fixtureMode_;
};

// Exposed for tests: maps a transport result + agent status/code to a Fault.
Fault FaultFor(const HttpResult& r);

}  // namespace hd
