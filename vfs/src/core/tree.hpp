// SPDX-License-Identifier: GPL-3.0-only
// Path resolution over cached directory snapshots.
//
// * Only the directory being resolved or listed is fetched (never the whole account).
// * Snapshots are cached for kSnapshotTtlMs and at most kMaxCachedDirectories directories.
// * No lock is held across agent calls; at most kMaxConcurrentFetches calls run at once, and a
//   caller waits at most kFetchWaitMs for a slot (then: Busy).
// * Callbacks block while a listing is fetched (bounded by the transport timeouts): this VFS does
//   not use WinFsp's asynchronous STATUS_PENDING completion.
#pragma once

#include <chrono>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <semaphore>
#include <string>

#include "agent_client.hpp"
#include "snapshot.hpp"

namespace hd {

constexpr std::int64_t kSnapshotTtlMs = 30000;
constexpr std::size_t kMaxCachedDirectories = 512;
constexpr int kMaxConcurrentFetches = 4;
constexpr int kFetchWaitMs = 15000;

enum class Lookup { Found, NameNotFound, PathNotFound, Failed };

struct Resolved {
    Lookup lookup = Lookup::Failed;
    Fault fault = Fault::None;  // when lookup == Failed
    Node node;
    Node parent;  // for ".." entries
};

class MetadataTree {
  public:
    using NowMs = std::function<std::int64_t()>;
    MetadataTree(AgentClient& client, std::int64_t volumeCreated, NowMs now = SteadyNowMs);

    Result<std::shared_ptr<const DirectorySnapshot>> Children(const Node& dir);
    Resolved Resolve(const std::wstring& path);
    const Node& RootNode() const { return root_; }
    void Clear();

    static std::int64_t SteadyNowMs();

  private:
    struct Cached {
        std::shared_ptr<const DirectorySnapshot> snapshot;
        std::int64_t fetchedAt;
    };
    static std::string KeyFor(const Node& dir);

    AgentClient& client_;
    NowMs now_;
    Node root_;
    std::mutex mutex_;
    std::map<std::string, Cached> cache_;
    std::counting_semaphore<kMaxConcurrentFetches> slots_{kMaxConcurrentFetches};
};

}  // namespace hd
