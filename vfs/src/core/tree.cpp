// SPDX-License-Identifier: GPL-3.0-only
#include "tree.hpp"

#include <cstdio>
#include <vector>

namespace hd {

std::int64_t MetadataTree::SteadyNowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

MetadataTree::MetadataTree(AgentClient& client, std::int64_t volumeCreated, NowMs now)
    : client_(client), now_(std::move(now)) {
    root_.kind = NodeKind::Root;
    root_.lastWrite = volumeCreated;  // synthetic, stable for the mount's lifetime
    root_.indexNumber = 1;
}

std::string MetadataTree::KeyFor(const Node& dir) {
    if (dir.kind == NodeKind::Root) return "/";
    return dir.accountId + "\x1f" + (dir.locator ? *dir.locator : std::string());
}

void MetadataTree::Clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    cache_.clear();
}

Result<std::shared_ptr<const DirectorySnapshot>> MetadataTree::Children(const Node& dir) {
    Result<std::shared_ptr<const DirectorySnapshot>> out;
    if (!dir.IsDirectory() || (dir.kind == NodeKind::Folder && !dir.locator)) {
        out.fault = Fault::NotFound;
        return out;
    }
    const std::string key = KeyFor(dir);
    {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = cache_.find(key);
        if (it != cache_.end() && now_() - it->second.fetchedAt < kSnapshotTtlMs) {
            out.value = it->second.snapshot;
            return out;
        }
    }
    if (!slots_.try_acquire_for(std::chrono::milliseconds(kFetchWaitMs))) {
        out.fault = Fault::Busy;
        return out;
    }
    std::shared_ptr<const DirectorySnapshot> fresh;
    if (dir.kind == NodeKind::Root) {
        auto accounts = client_.Accounts();
        slots_.release();
        if (!accounts.ok()) {
            out.fault = accounts.fault;
            std::fprintf(stderr, "[hyperdrive-vfs] account listing failed: %s\n", FaultCode(out.fault));
            return out;
        }
        fresh = BuildRootSnapshot(accounts.value);
    } else {
        auto listing = client_.List(dir.accountId, dir.kind == NodeKind::Account ? std::nullopt : dir.locator);
        slots_.release();
        if (!listing.ok()) {
            out.fault = listing.fault;
            // Diagnostic only: the fault code, never a name, path or locator.
            std::fprintf(stderr, "[hyperdrive-vfs] directory listing failed: %s\n", FaultCode(out.fault));
            return out;
        }
        fresh = BuildFolderSnapshot(dir.accountId, listing.value);
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (cache_.size() >= kMaxCachedDirectories && cache_.find(key) == cache_.end()) {
            auto oldest = cache_.begin();
            for (auto it = cache_.begin(); it != cache_.end(); ++it) {
                if (it->second.fetchedAt < oldest->second.fetchedAt) oldest = it;
            }
            cache_.erase(oldest);  // open handles keep their own reference to the snapshot
        }
        cache_[key] = Cached{fresh, now_()};
    }
    out.value = fresh;
    return out;
}

Resolved MetadataTree::Resolve(const std::wstring& path) {
    Resolved r;
    std::vector<std::wstring> parts;
    std::size_t i = 0;
    while (i < path.size()) {
        while (i < path.size() && path[i] == L'\\') i++;
        std::size_t j = i;
        while (j < path.size() && path[j] != L'\\') j++;
        if (j > i) parts.push_back(path.substr(i, j - i));
        i = j;
    }
    Node current = root_;
    Node parent = root_;
    for (std::size_t k = 0; k < parts.size(); k++) {
        auto children = Children(current);
        if (!children.ok()) {
            if (children.fault == Fault::NotFound) {
                r.lookup = k + 1 == parts.size() ? Lookup::NameNotFound : Lookup::PathNotFound;
            } else {
                r.lookup = Lookup::Failed;
                r.fault = children.fault;
            }
            return r;
        }
        const Node* next = children.value->Find(parts[k]);
        if (next == nullptr) {
            r.lookup = k + 1 == parts.size() ? Lookup::NameNotFound : Lookup::PathNotFound;
            return r;
        }
        if (k + 1 < parts.size() && !next->IsDirectory()) {
            r.lookup = Lookup::PathNotFound;
            return r;
        }
        parent = current;
        current = *next;
    }
    r.lookup = Lookup::Found;
    r.node = current;
    r.parent = parts.empty() ? root_ : parent;
    return r;
}

}  // namespace hd
