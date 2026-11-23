// SPDX-License-Identifier: GPL-3.0-only
// Directory snapshots and WinFsp-style enumeration (marker continuation over a stable snapshot).
#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace hd {

enum class NodeKind { Root, Account, Folder, File };

// What the VFS knows about one visible entry. Provider identities stay separate from the virtual
// ones: virtual nodes (root, account directory) never carry a provider id.
struct Node {
    NodeKind kind = NodeKind::File;
    std::wstring name;                    // Windows-visible name (unique in its directory)
    std::string accountId;                // empty for the root
    std::string providerId;               // provider file/folder id; empty for virtual nodes
    std::optional<std::string> locator;   // folder: opaque directory locator for listing children
    std::uint64_t size = 0;               // files: real size; directories: 0
    std::int64_t lastWrite = 0;           // FILETIME ticks; 0 = unknown
    std::uint64_t indexNumber = 0;        // stable per (account, provider id)

    bool IsDirectory() const { return kind != NodeKind::File; }
};

// Provider entry as received from the agent (already validated).
struct RemoteEntry {
    std::string id;
    std::string name;  // UTF-8
    std::string kind;  // FILE | FOLDER | SHORTCUT | PROVIDER_DOCUMENT
    std::optional<std::uint64_t> sizeBytes;
    std::optional<std::int64_t> modifiedMillis;
    std::optional<std::string> directoryLocator;
};

struct RemoteAccount {
    std::string id;
    std::string provider;
    std::string rootType;
    std::string status;
};

// An immutable, sorted listing of one directory. Shared by open handles; released when the last
// handle and the cache drop it.
class DirectorySnapshot {
  public:
    DirectorySnapshot(std::vector<Node> entries, std::size_t excluded);

    const std::vector<Node>& Entries() const { return entries_; }
    std::size_t Excluded() const { return excluded_; }
    // Case-insensitive lookup by Windows name.
    const Node* Find(const std::wstring& name) const;
    // Index of the first entry strictly greater than `marker` (case-insensitive ordinal order).
    std::size_t FirstAfter(const std::wstring& marker) const;

  private:
    std::vector<Node> entries_;
    std::size_t excluded_;
};

// Converts a provider listing into a snapshot: assigns unique Windows names, converts metadata and
// excludes kinds that aren't ordinary readable items (shortcuts, native provider documents) and
// files without a known size. Exclusions are counted, never silent in the logs/UI.
std::shared_ptr<const DirectorySnapshot> BuildFolderSnapshot(const std::string& accountId,
                                                             const std::vector<RemoteEntry>& entries);
std::shared_ptr<const DirectorySnapshot> BuildRootSnapshot(const std::vector<RemoteAccount>& accounts);

std::int64_t MillisToFiletime(std::int64_t unixMillis);
std::uint64_t IndexNumberFor(const std::string& accountId, const std::string& providerId);

// Enumeration: emits "." and ".." first for non-root directories, then entries after `marker`.
// `emit` returns false when the caller's buffer is full. Returns true when the listing reached its
// end (the caller then writes the end-of-directory marker), false when it stopped early.
using DirEmitter = std::function<bool(const std::wstring& name, const Node& info)>;
bool EnumerateDirectory(const DirectorySnapshot& snapshot, const Node& self, const Node& parent, bool isRoot,
                        const wchar_t* marker, const DirEmitter& emit);

}  // namespace hd
