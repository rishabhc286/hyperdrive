// SPDX-License-Identifier: GPL-3.0-only
// The WinFsp filesystem: a read-only view of the user's connected accounts. Batch 9: file content
// through agent-owned read handles (core/content.hpp).
#pragma once

#include <winfsp/winfsp.h>

#include <memory>
#include <string>

#include "../core/content.hpp"
#include "../core/tree.hpp"

namespace hd {

class HyperDriveFs {
  public:
    HyperDriveFs(MetadataTree& tree, ContentService& content, std::wstring volumeLabel);
    ~HyperDriveFs();

    // Creates the WinFsp volume (not yet mounted).
    NTSTATUS Create(std::int64_t volumeCreationTime);
    // e.g. L"H:"; fails if the letter is taken.
    NTSTATUS Mount(const std::wstring& mountPoint);
    NTSTATUS Start();
    // Stops the dispatcher and deletes the volume (removing the mount point). Idempotent.
    void Stop();

    MetadataTree& Tree() { return tree_; }
    ContentService& Content() { return content_; }
    const std::wstring& Label() const { return label_; }
    PSECURITY_DESCRIPTOR Security() const { return security_; }
    std::size_t SecuritySize() const { return securitySize_; }
    std::int64_t VolumeTime() const { return volumeTime_; }

  private:
    MetadataTree& tree_;
    ContentService& content_;
    std::wstring label_;
    FSP_FILE_SYSTEM* fs_ = nullptr;
    PSECURITY_DESCRIPTOR security_ = nullptr;
    std::size_t securitySize_ = 0;
    std::int64_t volumeTime_ = 0;
    bool started_ = false;
};

}  // namespace hd
