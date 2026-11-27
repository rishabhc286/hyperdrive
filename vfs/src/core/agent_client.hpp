// SPDX-License-Identifier: GPL-3.0-only
// Typed, strictly validated calls to the agent's capability-scoped VFS routes.
#pragma once

#include <cstdint>
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
    // Batch 9 (content reads)
    VersionChanged,     // the file changed remotely / its pinned revision is gone
    Stale,              // the agent no longer knows the read handle (mount changed)
    Cancelled,          // the agent cancelled the read (close, unmount, disconnect)
    Unsupported,        // not readable through the drive (kind, unknown size/revision)
    TooManyOpen,        // the agent's bound on open read handles
    Unmounting,         // this drive is being unmounted
};

const char* FaultCode(Fault f);

template <class T>
struct Result {
    Fault fault = Fault::None;
    T value{};
    bool ok() const { return fault == Fault::None; }
};

constexpr std::size_t kMaxListingEntries = 10000;
// The agent's public per-request content limit (4 MiB). Larger Windows reads are split.
constexpr std::uint32_t kMaxAgentRead = 4u * 1024u * 1024u;

// An agent-owned read context. The handle is opaque and authorizes only reads of this one file
// within this mount; it is never a provider token.
struct OpenedContent {
    std::string handle;
    std::uint64_t size = 0;
    std::string consistency;  // "dropbox-revision-pinned" | "google-unpinned"
};

struct RangeRead {
    Fault fault = Fault::None;
    bool eof = false;
};

class AgentClient {
  public:
    explicit AgentClient(AgentTransport& transport, bool fixtureMode = false)
        : transport_(transport), fixtureMode_(fixtureMode) {}

    // Proves the capability is valid; returns the mount generation.
    Result<long long> Session();
    Result<std::vector<RemoteAccount>> Accounts();
    Result<std::vector<RemoteEntry>> List(const std::string& accountId, const std::optional<std::string>& locator);

    // Batch 9 content: open an agent read context (the agent validates the file with the backend and
    // pins its size/revision), read exactly `length` bytes at `offset` into `destination`, close it.
    Result<OpenedContent> OpenRead(const std::string& accountId, const std::string& fileId, std::uint64_t expectedSize);
    RangeRead ReadRange(const std::string& handle, std::uint64_t offset, std::uint32_t length, void* destination);
    void CloseRead(const std::string& handle);
    // Unmount: ask the agent to cancel every in-flight read of this mount.
    void CancelReads();

  private:
    AgentTransport& transport_;
    bool fixtureMode_;
};

// Exposed for tests: maps a transport result + agent status/code to a Fault.
Fault FaultFor(const HttpResult& r);
Fault FaultForStatus(int status, const std::string& code);
// Exposed for tests: strict validation of one binary read answer (status, type, declared and actual
// lengths, echoed offset). Anything inconsistent is Fault::Invalid; never a partial success.
RangeRead ValidateRange(const BinaryResult& r, std::uint64_t offset, std::uint32_t length);

}  // namespace hd
