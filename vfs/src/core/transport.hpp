// SPDX-License-Identifier: GPL-3.0-only
// Narrow transport between the VFS and the local agent. Today: authenticated loopback HTTP via
// WinHTTP (ADR 0004). Kept behind this interface so a later batch can change it without touching
// the filesystem logic.
#pragma once

#include <cstdint>
#include <memory>
#include <optional>
#include <string>

namespace hd {

struct HttpResult {
    enum class Kind { Ok, Timeout, Unreachable, TooLarge, Failed };
    Kind kind = Kind::Failed;
    int status = 0;
    std::string body;
};

// Batch 9: an answer whose 200 body (file bytes) was written straight into the caller's buffer.
// Non-200 answers keep their small JSON error body in `errorBody` (bounded). Header values are
// captured only for the fixed X-HyperDrive-Read-* names the content client validates.
struct BinaryResult {
    HttpResult::Kind kind = HttpResult::Kind::Failed;
    int status = 0;
    std::uint64_t bytes = 0;  // body bytes written to the destination (status 200 only)
    std::string errorBody;
    std::string contentType;
    std::optional<std::string> contentLength;
    std::optional<std::string> readOffset;
    std::optional<std::string> readLength;
    std::optional<std::string> eof;
};

class AgentTransport {
  public:
    virtual ~AgentTransport() = default;
    // POST a JSON body to a fixed agent route (e.g. L"/local/v1/vfs/list").
    virtual HttpResult Post(const std::wstring& path, const std::string& jsonBody) = 0;
    // POST a JSON body and receive at most `capacity` body bytes into `destination`. A longer body is
    // TooLarge (never truncated silently); `receiveMs` overrides the receive timeout for this request.
    virtual BinaryResult PostForBinary(const std::wstring& path, const std::string& jsonBody, void* destination,
                                       std::size_t capacity, int receiveMs) = 0;
};

struct TransportLimits {
    int resolveMs = 2000;
    int connectMs = 2000;
    int sendMs = 5000;
    int receiveMs = 35000;  // the agent's listing deadline is 30 s
    std::size_t maxResponseBytes = 8 * 1024 * 1024;
};

// Content reads: the agent bounds one read (including a credential renewal wait) to 45 s.
constexpr int kContentReceiveMs = 55000;
constexpr std::size_t kMaxErrorBody = 16 * 1024;

// Always http://127.0.0.1:<port>; no proxy, no redirects. The capability is sent as a bearer token
// and is never logged.
std::unique_ptr<AgentTransport> MakeWinHttpTransport(int port, const std::string& capability,
                                                     const TransportLimits& limits = {});

}  // namespace hd
