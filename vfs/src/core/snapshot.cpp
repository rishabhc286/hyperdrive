// SPDX-License-Identifier: GPL-3.0-only
#include "snapshot.hpp"

#include <algorithm>

#include "naming.hpp"
#include "text.hpp"

namespace hd {

DirectorySnapshot::DirectorySnapshot(std::vector<Node> entries, std::size_t excluded)
    : entries_(std::move(entries)), excluded_(excluded) {
    std::sort(entries_.begin(), entries_.end(),
              [](const Node& a, const Node& b) { return CompareNames(a.name, b.name) < 0; });
}

const Node* DirectorySnapshot::Find(const std::wstring& name) const {
    auto it = std::lower_bound(entries_.begin(), entries_.end(), name,
                               [](const Node& n, const std::wstring& key) { return CompareNames(n.name, key) < 0; });
    if (it != entries_.end() && CompareNames(it->name, name) == 0) return &*it;
    return nullptr;
}

std::size_t DirectorySnapshot::FirstAfter(const std::wstring& marker) const {
    auto it = std::upper_bound(entries_.begin(), entries_.end(), marker,
                               [](const std::wstring& key, const Node& n) { return CompareNames(key, n.name) < 0; });
    return static_cast<std::size_t>(it - entries_.begin());
}

std::int64_t MillisToFiletime(std::int64_t unixMillis) {
    // 100 ns ticks since 1601-01-01. Values before 1601 can't be represented: treat as unknown.
    constexpr std::int64_t kEpochDiffMillis = 11644473600000LL;
    if (unixMillis < -kEpochDiffMillis) return 0;
    if (unixMillis > (INT64_MAX / 10000) - kEpochDiffMillis) return 0;
    return (unixMillis + kEpochDiffMillis) * 10000;
}

std::uint64_t IndexNumberFor(const std::string& accountId, const std::string& providerId) {
    std::uint64_t h = Fnv1a64(accountId + "\x1f" + providerId);
    return h == 0 ? 1 : h;
}

std::shared_ptr<const DirectorySnapshot> BuildFolderSnapshot(const std::string& accountId,
                                                             const std::vector<RemoteEntry>& entries) {
    std::vector<const RemoteEntry*> kept;
    std::size_t excluded = 0;
    for (const auto& e : entries) {
        bool folder = e.kind == "FOLDER";
        bool file = e.kind == "FILE" && e.sizeBytes.has_value();
        if (folder || file) {
            kept.push_back(&e);
        } else {
            excluded++;  // shortcuts, native documents, files of unknown size
        }
    }
    std::vector<NamingInput> naming;
    naming.reserve(kept.size());
    for (const auto* e : kept) naming.push_back({e->id, Utf8ToWide(e->name)});
    std::vector<std::wstring> names = AssignNames(naming);

    std::vector<Node> nodes;
    nodes.reserve(kept.size());
    for (std::size_t i = 0; i < kept.size(); i++) {
        const RemoteEntry& e = *kept[i];
        Node n;
        n.kind = e.kind == "FOLDER" ? NodeKind::Folder : NodeKind::File;
        n.name = names[i];
        n.accountId = accountId;
        n.providerId = e.id;
        n.locator = n.kind == NodeKind::Folder ? e.directoryLocator : std::nullopt;
        n.size = n.kind == NodeKind::File ? *e.sizeBytes : 0;
        n.lastWrite = e.modifiedMillis ? MillisToFiletime(*e.modifiedMillis) : 0;
        n.indexNumber = IndexNumberFor(accountId, e.id);
        nodes.push_back(std::move(n));
    }
    return std::make_shared<DirectorySnapshot>(std::move(nodes), excluded);
}

std::shared_ptr<const DirectorySnapshot> BuildRootSnapshot(const std::vector<RemoteAccount>& accounts) {
    std::vector<NamingInput> naming;
    for (const auto& a : accounts) {
        naming.push_back({a.id, AccountDirectoryBaseName(a.provider, a.rootType) + L" [" + HexSuffix(a.id, 8) + L"]"});
    }
    std::vector<std::wstring> names = AssignNames(naming);
    std::vector<Node> nodes;
    for (std::size_t i = 0; i < accounts.size(); i++) {
        Node n;
        n.kind = NodeKind::Account;
        n.name = names[i];
        n.accountId = accounts[i].id;
        n.indexNumber = IndexNumberFor(accounts[i].id, "");
        nodes.push_back(std::move(n));
    }
    return std::make_shared<DirectorySnapshot>(std::move(nodes), 0);
}

bool EnumerateDirectory(const DirectorySnapshot& snapshot, const Node& self, const Node& parent, bool isRoot,
                        const wchar_t* marker, const DirEmitter& emit) {
    std::size_t start = 0;
    if (!isRoot) {
        bool afterDot = marker != nullptr && std::wstring(marker) == L".";
        if (marker == nullptr) {
            if (!emit(L".", self)) return false;
        }
        if (marker == nullptr || afterDot) {
            if (!emit(L"..", parent)) return false;
            marker = nullptr;
        } else if (std::wstring(marker) == L"..") {
            marker = nullptr;
        }
    }
    if (marker != nullptr) start = snapshot.FirstAfter(marker);
    const auto& entries = snapshot.Entries();
    for (std::size_t i = start; i < entries.size(); i++) {
        if (!emit(entries[i].name, entries[i])) return false;
    }
    return true;
}

}  // namespace hd
