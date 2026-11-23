// SPDX-License-Identifier: GPL-3.0-only
#include "filesystem.hpp"

#include <sddl.h>

#include <cstring>
#include <mutex>
#include <vector>

#include "../core/naming.hpp"
#include "../core/status.hpp"

namespace hd {
namespace {

constexpr UINT32 kAllocationUnit = 4096;

// Per-open state. Directory enumeration keeps ONE snapshot from the first (marker-less) read until
// the handle closes or the caller rewinds, so continuation markers always refer to the same list.
struct OpenNode {
    Node node;
    Node parent;
    std::mutex mutex;
    std::shared_ptr<const DirectorySnapshot> enumeration;
};

HyperDriveFs* Self(FSP_FILE_SYSTEM* fs) { return static_cast<HyperDriveFs*>(fs->UserContext); }

void FillInfo(const HyperDriveFs& self, const Node& n, FSP_FSCTL_FILE_INFO* info) {
    std::memset(info, 0, sizeof *info);
    if (n.IsDirectory()) {
        info->FileAttributes = FILE_ATTRIBUTE_DIRECTORY;
    } else {
        // OFFLINE: content isn't locally available (and not readable through the drive yet), which also
        // tells Explorer not to open files for previews or thumbnails.
        info->FileAttributes = FILE_ATTRIBUTE_READONLY | FILE_ATTRIBUTE_OFFLINE;
        info->FileSize = n.size;
        info->AllocationSize = (n.size + kAllocationUnit - 1) / kAllocationUnit * kAllocationUnit;
    }
    std::int64_t t = n.kind == NodeKind::Root || n.kind == NodeKind::Account ? self.VolumeTime() : n.lastWrite;
    info->CreationTime = info->LastAccessTime = info->LastWriteTime = info->ChangeTime = static_cast<UINT64>(t);
    info->IndexNumber = n.indexNumber;
}

NTSTATUS LookupStatus(const Resolved& r) {
    switch (r.lookup) {
        case Lookup::Found: return STATUS_SUCCESS;
        case Lookup::NameNotFound: return STATUS_OBJECT_NAME_NOT_FOUND;
        case Lookup::PathNotFound: return STATUS_OBJECT_PATH_NOT_FOUND;
        case Lookup::Failed: return StatusForFault(r.fault);
    }
    return STATUS_UNEXPECTED_NETWORK_ERROR;
}

NTSTATUS CopySecurity(const HyperDriveFs& self, PSECURITY_DESCRIPTOR out, SIZE_T* size) {
    if (size == nullptr) return STATUS_SUCCESS;
    if (self.SecuritySize() > *size) {
        *size = self.SecuritySize();
        return STATUS_BUFFER_OVERFLOW;
    }
    *size = self.SecuritySize();
    if (out != nullptr) std::memcpy(out, self.Security(), self.SecuritySize());
    return STATUS_SUCCESS;
}

NTSTATUS GetVolumeInfo(FSP_FILE_SYSTEM* fs, FSP_FSCTL_VOLUME_INFO* info) {
    const auto& label = Self(fs)->Label();
    info->TotalSize = 0;  // no capacity concept for a cloud view; not invented
    info->FreeSize = 0;
    std::size_t chars = label.size() < 32 ? label.size() : 32;
    info->VolumeLabelLength = static_cast<UINT16>(chars * sizeof(WCHAR));
    std::memcpy(info->VolumeLabel, label.data(), chars * sizeof(WCHAR));
    return STATUS_SUCCESS;
}

NTSTATUS SetVolumeLabel(FSP_FILE_SYSTEM*, PWSTR, FSP_FSCTL_VOLUME_INFO*) { return kReadOnlyStatus; }

NTSTATUS GetSecurityByName(FSP_FILE_SYSTEM* fs, PWSTR name, PUINT32 attributes, PSECURITY_DESCRIPTOR sd,
                           SIZE_T* sdSize) {
    HyperDriveFs* self = Self(fs);
    Resolved r = self->Tree().Resolve(name);
    if (r.lookup != Lookup::Found) return LookupStatus(r);
    if (attributes != nullptr) {
        *attributes = r.node.IsDirectory() ? FILE_ATTRIBUTE_DIRECTORY : (FILE_ATTRIBUTE_READONLY | FILE_ATTRIBUTE_OFFLINE);
    }
    return CopySecurity(*self, sd, sdSize);
}

NTSTATUS Create(FSP_FILE_SYSTEM*, PWSTR, UINT32, UINT32, UINT32, PSECURITY_DESCRIPTOR, UINT64, PVOID*,
                FSP_FSCTL_FILE_INFO*) {
    return kReadOnlyStatus;
}

NTSTATUS Open(FSP_FILE_SYSTEM* fs, PWSTR name, UINT32 createOptions, UINT32 grantedAccess, PVOID* context,
              FSP_FSCTL_FILE_INFO* info) {
    if ((grantedAccess & kWriteAccessMask) != 0 || (createOptions & FILE_DELETE_ON_CLOSE) != 0) return kReadOnlyStatus;
    HyperDriveFs* self = Self(fs);
    Resolved r = self->Tree().Resolve(name);
    if (r.lookup != Lookup::Found) return LookupStatus(r);
    if ((createOptions & FILE_DIRECTORY_FILE) != 0 && !r.node.IsDirectory()) return STATUS_NOT_A_DIRECTORY;
    if ((createOptions & FILE_NON_DIRECTORY_FILE) != 0 && r.node.IsDirectory()) return STATUS_FILE_IS_A_DIRECTORY;
    auto* open = new (std::nothrow) OpenNode();
    if (open == nullptr) return STATUS_INSUFFICIENT_RESOURCES;
    open->node = r.node;
    open->parent = r.parent;
    *context = open;
    FillInfo(*self, open->node, info);
    return STATUS_SUCCESS;
}

NTSTATUS Overwrite(FSP_FILE_SYSTEM*, PVOID, UINT32, BOOLEAN, UINT64, FSP_FSCTL_FILE_INFO*) { return kReadOnlyStatus; }

VOID Cleanup(FSP_FILE_SYSTEM*, PVOID context, PWSTR, ULONG) {
    // Release the enumeration snapshot as soon as the handle is cleaned up.
    auto* open = static_cast<OpenNode*>(context);
    std::lock_guard<std::mutex> lock(open->mutex);
    open->enumeration.reset();
}

VOID Close(FSP_FILE_SYSTEM*, PVOID context) { delete static_cast<OpenNode*>(context); }

NTSTATUS Read(FSP_FILE_SYSTEM*, PVOID context, PVOID, UINT64 offset, ULONG, PULONG transferred) {
    auto* open = static_cast<OpenNode*>(context);
    *transferred = 0;
    if (open->node.IsDirectory()) return STATUS_INVALID_DEVICE_REQUEST;
    return StatusForRead(open->node.size, offset);
}

NTSTATUS Write(FSP_FILE_SYSTEM*, PVOID, PVOID, UINT64, ULONG, BOOLEAN, BOOLEAN, PULONG, FSP_FSCTL_FILE_INFO*) {
    return kReadOnlyStatus;
}

NTSTATUS Flush(FSP_FILE_SYSTEM* fs, PVOID context, FSP_FSCTL_FILE_INFO* info) {
    if (context != nullptr) FillInfo(*Self(fs), static_cast<OpenNode*>(context)->node, info);
    return STATUS_SUCCESS;  // nothing is ever buffered for writing
}

NTSTATUS GetFileInfo(FSP_FILE_SYSTEM* fs, PVOID context, FSP_FSCTL_FILE_INFO* info) {
    FillInfo(*Self(fs), static_cast<OpenNode*>(context)->node, info);
    return STATUS_SUCCESS;
}

NTSTATUS SetBasicInfo(FSP_FILE_SYSTEM*, PVOID, UINT32, UINT64, UINT64, UINT64, UINT64, FSP_FSCTL_FILE_INFO*) {
    return kReadOnlyStatus;
}

NTSTATUS SetFileSize(FSP_FILE_SYSTEM*, PVOID, UINT64, BOOLEAN, FSP_FSCTL_FILE_INFO*) { return kReadOnlyStatus; }

NTSTATUS Rename(FSP_FILE_SYSTEM*, PVOID, PWSTR, PWSTR, BOOLEAN) { return kReadOnlyStatus; }

NTSTATUS GetSecurity(FSP_FILE_SYSTEM* fs, PVOID, PSECURITY_DESCRIPTOR sd, SIZE_T* sdSize) {
    return CopySecurity(*Self(fs), sd, sdSize);
}

NTSTATUS SetSecurity(FSP_FILE_SYSTEM*, PVOID, SECURITY_INFORMATION, PSECURITY_DESCRIPTOR) { return kReadOnlyStatus; }

NTSTATUS SetDelete(FSP_FILE_SYSTEM*, PVOID, PWSTR, BOOLEAN) { return kReadOnlyStatus; }

NTSTATUS ReadDirectory(FSP_FILE_SYSTEM* fs, PVOID context, PWSTR, PWSTR marker, PVOID buffer, ULONG length,
                       PULONG transferred) {
    HyperDriveFs* self = Self(fs);
    auto* open = static_cast<OpenNode*>(context);
    if (!open->node.IsDirectory()) return STATUS_NOT_A_DIRECTORY;
    std::shared_ptr<const DirectorySnapshot> snapshot;
    {
        std::lock_guard<std::mutex> lock(open->mutex);
        snapshot = open->enumeration;
    }
    if (marker == nullptr || !snapshot) {
        // New enumeration (or rewind): take a snapshot without holding the handle lock during I/O.
        auto children = self->Tree().Children(open->node);
        if (!children.ok()) return StatusForFault(children.fault);
        snapshot = children.value;
        std::lock_guard<std::mutex> lock(open->mutex);
        open->enumeration = snapshot;
    }
    alignas(FSP_FSCTL_DIR_INFO) UINT8 raw[FIELD_OFFSET(FSP_FSCTL_DIR_INFO, FileNameBuf) + (kMaxComponent + 1) * sizeof(WCHAR)];
    auto* dirInfo = reinterpret_cast<FSP_FSCTL_DIR_INFO*>(raw);
    *transferred = 0;
    bool complete = EnumerateDirectory(
        *snapshot, open->node, open->parent, open->node.kind == NodeKind::Root, marker,
        [&](const std::wstring& name, const Node& n) {
            std::size_t chars = name.size() > kMaxComponent ? kMaxComponent : name.size();
            std::memset(dirInfo, 0, sizeof(FSP_FSCTL_DIR_INFO));
            dirInfo->Size = static_cast<UINT16>(FIELD_OFFSET(FSP_FSCTL_DIR_INFO, FileNameBuf) + chars * sizeof(WCHAR));
            FillInfo(*self, n, &dirInfo->FileInfo);
            std::memcpy(dirInfo->FileNameBuf, name.data(), chars * sizeof(WCHAR));
            return FspFileSystemAddDirInfo(dirInfo, buffer, length, transferred) != FALSE;
        });
    if (complete) FspFileSystemAddDirInfo(nullptr, buffer, length, transferred);  // end of directory
    return STATUS_SUCCESS;
}

FSP_FILE_SYSTEM_INTERFACE MakeInterface() {
    FSP_FILE_SYSTEM_INTERFACE i;
    std::memset(&i, 0, sizeof i);
    i.GetVolumeInfo = GetVolumeInfo;
    i.SetVolumeLabel = SetVolumeLabel;
    i.GetSecurityByName = GetSecurityByName;
    i.Create = Create;
    i.Open = Open;
    i.Overwrite = Overwrite;
    i.Cleanup = Cleanup;
    i.Close = Close;
    i.Read = Read;
    i.Write = Write;
    i.Flush = Flush;
    i.GetFileInfo = GetFileInfo;
    i.SetBasicInfo = SetBasicInfo;
    i.SetFileSize = SetFileSize;
    i.Rename = Rename;
    i.GetSecurity = GetSecurity;
    i.SetSecurity = SetSecurity;
    i.ReadDirectory = ReadDirectory;
    i.SetDelete = SetDelete;
    return i;
}

FSP_FILE_SYSTEM_INTERFACE g_interface = MakeInterface();

}  // namespace

HyperDriveFs::HyperDriveFs(MetadataTree& tree, std::wstring volumeLabel) : tree_(tree), label_(std::move(volumeLabel)) {}

HyperDriveFs::~HyperDriveFs() {
    Stop();
    if (security_ != nullptr) LocalFree(security_);
}

NTSTATUS HyperDriveFs::Create(std::int64_t volumeCreationTime) {
    volumeTime_ = volumeCreationTime;
    // Read-only for everyone, including the owner: System, Administrators, Everyone get read+execute.
    ULONG size = 0;
    if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(L"O:BAG:BAD:P(A;;FRFX;;;SY)(A;;FRFX;;;BA)(A;;FRFX;;;WD)",
                                                              SDDL_REVISION_1, &security_, &size)) {
        return FspNtStatusFromWin32(GetLastError());
    }
    securitySize_ = size;

    FSP_FSCTL_VOLUME_PARAMS params;
    std::memset(&params, 0, sizeof params);
    params.SectorSize = kAllocationUnit;
    params.SectorsPerAllocationUnit = 1;
    params.MaxComponentLength = static_cast<UINT16>(kMaxComponent);
    params.VolumeCreationTime = static_cast<UINT64>(volumeCreationTime);
    params.VolumeSerialNumber = static_cast<UINT32>(volumeCreationTime / 10000);
    params.FileInfoTimeout = 1000;
    params.CaseSensitiveSearch = 0;
    params.CasePreservedNames = 1;
    params.UnicodeOnDisk = 1;
    params.PersistentAcls = 1;
    params.ReadOnlyVolume = 1;
    params.PostCleanupWhenModifiedOnly = 1;
    params.UmFileContextIsUserContext2 = 1;
    wcscpy_s(params.FileSystemName, sizeof params.FileSystemName / sizeof(WCHAR), L"HyperDrive");
    NTSTATUS result = FspFileSystemCreate(const_cast<PWSTR>(L"" FSP_FSCTL_DISK_DEVICE_NAME), &params, &g_interface, &fs_);
    if (!NT_SUCCESS(result)) return result;
    fs_->UserContext = this;
    return STATUS_SUCCESS;
}

NTSTATUS HyperDriveFs::Mount(const std::wstring& mountPoint) {
    if (fs_ == nullptr) return STATUS_INVALID_DEVICE_STATE;
    return FspFileSystemSetMountPoint(fs_, const_cast<PWSTR>(mountPoint.c_str()));
}

NTSTATUS HyperDriveFs::Start() {
    if (fs_ == nullptr) return STATUS_INVALID_DEVICE_STATE;
    NTSTATUS result = FspFileSystemStartDispatcher(fs_, 0);
    started_ = NT_SUCCESS(result);
    return result;
}

void HyperDriveFs::Stop() {
    if (fs_ == nullptr) return;
    if (started_) FspFileSystemStopDispatcher(fs_);
    started_ = false;
    FspFileSystemDelete(fs_);  // also removes the mount point
    fs_ = nullptr;
}

}  // namespace hd
