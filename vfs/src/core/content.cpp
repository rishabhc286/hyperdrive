// SPDX-License-Identifier: GPL-3.0-only
#include "content.hpp"

#include <cstdio>

namespace hd {

NTSTATUS ServeRead(std::uint64_t fileSize, std::uint64_t offset, std::uint32_t length, std::uint8_t* buffer,
                   std::uint32_t& transferred, const RangeFetcher& fetch, Fault* failure) {
    transferred = 0;
    if (failure != nullptr) *failure = Fault::None;
    if (offset >= fileSize) return STATUS_END_OF_FILE;  // at/after EOF, including zero-byte files
    // fileSize > offset, so the subtraction can't underflow; the clamp keeps `want` within ULONG.
    std::uint64_t remaining = fileSize - offset;
    std::uint32_t want = remaining < length ? static_cast<std::uint32_t>(remaining) : length;
    std::uint32_t done = 0;
    while (done < want) {
        std::uint32_t piece = want - done < kMaxAgentRead ? want - done : kMaxAgentRead;
        Fault f = fetch(offset + done, piece, buffer + done);
        if (f != Fault::None) {
            // All or nothing: bytes already copied for earlier pieces are not reported.
            if (failure != nullptr) *failure = f;
            return StatusForContentFault(f);
        }
        done += piece;
    }
    transferred = want;
    return STATUS_SUCCESS;
}

bool IsPermanentContentFault(Fault fault) {
    switch (fault) {
        case Fault::NotFound:
        case Fault::VersionChanged:
        case Fault::Stale:
        case Fault::Unsupported:
        case Fault::Forbidden:
        case Fault::NeedsReconnect:
        case Fault::AuthorizationLost:
        case Fault::Invalid: return true;
        default: return false;
    }
}

ContentService::OpenFile::~OpenFile() {
    if (!handle.empty()) SecureZeroMemory(handle.data(), handle.size());
}

std::int64_t ContentService::DefaultNow() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

ContentService::ContentService(AgentClient& client, NowMs now) : client_(client), now_(std::move(now)) {}

std::string ContentService::NegativeKey(const Node& node) const {
    return node.accountId + "\x1f" + node.providerId + "\x1f" + std::to_string(node.size);
}

Fault ContentService::EnsureOpen(OpenFile& file, const Node& node, std::string& handle) {
    std::lock_guard<std::mutex> lock(file.mutex);  // per open only; never a global lock
    if (file.sticky != Fault::None) return file.sticky;
    if (!file.handle.empty()) {
        handle = file.handle;
        return Fault::None;
    }
    std::int64_t now = now_();
    if (file.lastTransient != Fault::None && now < file.retryAtMs) return file.lastTransient;
    {
        std::lock_guard<std::mutex> n(negativeMutex_);
        auto it = negative_.find(NegativeKey(node));
        if (it != negative_.end()) {
            if (now < it->second.second) {
                file.sticky = it->second.first;
                return file.sticky;
            }
            negative_.erase(it);
        }
    }
    if (!slots_.try_acquire_for(std::chrono::milliseconds(kContentSlotWaitMs))) return Fault::Busy;
    auto opened = client_.OpenRead(node.accountId, node.providerId, node.size);
    slots_.release();
    if (!opened.ok()) {
        std::fprintf(stderr, "[hyperdrive-vfs] content open failed: %s\n", FaultCode(opened.fault));
        if (IsPermanentContentFault(opened.fault)) {
            file.sticky = opened.fault;
            std::lock_guard<std::mutex> n(negativeMutex_);
            if (negative_.size() >= 1024) negative_.clear();  // bounded; it's only a probe damper
            negative_[NegativeKey(node)] = {opened.fault, now + kNegativeOpenTtlMs};
        } else {
            file.lastTransient = opened.fault;
            file.retryAtMs = now + kTransientOpenRetryMs;
        }
        return opened.fault;
    }
    if (unmounting_.load()) {
        client_.CloseRead(opened.value.handle);
        return Fault::Unmounting;
    }
    file.handle = std::move(opened.value.handle);
    file.lastTransient = Fault::None;
    handle = file.handle;
    return Fault::None;
}

NTSTATUS ContentService::Read(OpenFile& file, const Node& node, std::uint64_t offset, std::uint32_t length, void* buffer,
                              std::uint32_t& transferred) {
    transferred = 0;
    if (node.IsDirectory()) return STATUS_INVALID_DEVICE_REQUEST;
    if (unmounting_.load()) return STATUS_VOLUME_DISMOUNTED;
    if (offset >= node.size) return STATUS_END_OF_FILE;  // no agent call for EOF or empty files
    if (length == 0) return STATUS_SUCCESS;
    std::string handle;
    Fault opened = EnsureOpen(file, node, handle);
    if (opened != Fault::None) return StatusForContentFault(opened);

    Fault failure = Fault::None;
    NTSTATUS status = ServeRead(
        node.size, offset, length, static_cast<std::uint8_t*>(buffer), transferred,
        [&](std::uint64_t at, std::uint32_t n, std::uint8_t* destination) {
            if (unmounting_.load()) return Fault::Unmounting;
            if (!slots_.try_acquire_for(std::chrono::milliseconds(kContentSlotWaitMs))) return Fault::Busy;
            RangeRead r = client_.ReadRange(handle, at, n, destination);
            slots_.release();
            if (r.fault == Fault::None && r.eof != (at + n == node.size)) return Fault::Invalid;
            if (r.fault != Fault::None && unmounting_.load()) return Fault::Unmounting;
            return r.fault;
        },
        &failure);
    if (!handle.empty()) SecureZeroMemory(handle.data(), handle.size());
    if (failure != Fault::None) {
        std::fprintf(stderr, "[hyperdrive-vfs] content read failed: %s\n", FaultCode(failure));
        if (IsPermanentContentFault(failure)) {
            std::lock_guard<std::mutex> lock(file.mutex);
            file.sticky = failure;  // e.g. the file changed: every later read of this open fails too
        }
    }
    return status;
}

void ContentService::Close(OpenFile& file) {
    std::string handle;
    {
        std::lock_guard<std::mutex> lock(file.mutex);
        handle.swap(file.handle);
    }
    if (!handle.empty()) {
        if (!unmounting_.load()) client_.CloseRead(handle);
        SecureZeroMemory(handle.data(), handle.size());
    }
}

void ContentService::BeginUnmount() {
    if (!unmounting_.exchange(true)) client_.CancelReads();
}

}  // namespace hd
