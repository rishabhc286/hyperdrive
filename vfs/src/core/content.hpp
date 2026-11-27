// SPDX-License-Identifier: GPL-3.0-only
// File content through agent-owned read handles (Batch 9, ADR 0005). No provider code, no cache,
// no credentials: the VFS only asks the local agent for byte ranges of files it opened there.
//
// * The agent handle is opened lazily on the first Read of an open (Explorer opens files for
//   metadata far more often than it reads them) and closed in Close, never in Cleanup.
// * A Windows read is clamped to the file size and split into <= 4 MiB agent reads written straight
//   into the WinFsp buffer. Any failed piece fails the whole read with 0 bytes transferred.
// * No read-ahead, no zero-fill, no retries inside a read. Permanent failures are remembered per
//   open and briefly per file, so repeated probes don't become request storms.
// * At most kMaxContentRequests agent requests at once (bounded wait for a slot); no lock is held
//   across network I/O except the per-open mutex that serializes that open's lazy OpenRead.
#pragma once

#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <map>
#include <mutex>
#include <semaphore>
#include <string>

#include "agent_client.hpp"
#include "snapshot.hpp"
#include "status.hpp"

namespace hd {

constexpr int kMaxContentRequests = 4;
constexpr int kContentSlotWaitMs = 10000;
constexpr std::int64_t kNegativeOpenTtlMs = 10000;
constexpr std::int64_t kTransientOpenRetryMs = 1000;

// One piece: read exactly `length` bytes at `offset` into `destination`.
using RangeFetcher = std::function<Fault(std::uint64_t offset, std::uint32_t length, std::uint8_t* destination)>;

// The pure read algorithm (unit-tested without WinFsp): EOF, clamping, splitting, all-or-nothing.
// `transferred` is set to the full clamped length on success and to 0 on any failure.
NTSTATUS ServeRead(std::uint64_t fileSize, std::uint64_t offset, std::uint32_t length, std::uint8_t* buffer,
                   std::uint32_t& transferred, const RangeFetcher& fetch, Fault* failure = nullptr);

// Whether a fault means "this open will never succeed" (remembered; no retries).
bool IsPermanentContentFault(Fault fault);

class ContentService {
  public:
    using NowMs = std::function<std::int64_t()>;

    // Per-open state, owned by the filesystem's open context (destroyed in Close).
    struct OpenFile {
        std::mutex mutex;
        std::string handle;              // agent read handle; empty until opened
        Fault sticky = Fault::None;      // permanent failure of this open
        Fault lastTransient = Fault::None;
        std::int64_t retryAtMs = 0;      // after a transient open failure
        ~OpenFile();                     // wipes the handle (best effort)
    };

    explicit ContentService(AgentClient& client, NowMs now = DefaultNow);

    NTSTATUS Read(OpenFile& file, const Node& node, std::uint64_t offset, std::uint32_t length, void* buffer,
                  std::uint32_t& transferred);
    // Close: ends the agent read context (best effort, skipped while unmounting: the agent drops
    // every context of the mount when its capability is revoked).
    void Close(OpenFile& file);
    // Unmount: new reads fail with STATUS_VOLUME_DISMOUNTED and in-flight ones are cancelled.
    void BeginUnmount();
    bool Unmounting() const { return unmounting_.load(); }

    static std::int64_t DefaultNow();

  private:
    Fault EnsureOpen(OpenFile& file, const Node& node, std::string& handle);
    std::string NegativeKey(const Node& node) const;

    AgentClient& client_;
    NowMs now_;
    std::atomic<bool> unmounting_{false};
    std::counting_semaphore<kMaxContentRequests> slots_{kMaxContentRequests};
    std::mutex negativeMutex_;
    std::map<std::string, std::pair<Fault, std::int64_t>> negative_;  // key -> (fault, expiresAt)
};

}  // namespace hd
