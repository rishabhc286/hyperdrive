// SPDX-License-Identifier: GPL-3.0-only
// NTSTATUS decisions, kept free of WinFsp so they can be unit-tested.
#pragma once

#define WIN32_NO_STATUS
#include <windows.h>
#include <winternl.h>
#undef WIN32_NO_STATUS
#include <ntstatus.h>

#include <cstdint>

#include "agent_client.hpp"

namespace hd {

// How a metadata fault surfaces to Windows.
NTSTATUS StatusForFault(Fault fault);

// Content reads are not implemented in Batch 8. Reads at or past the end of a file report
// STATUS_END_OF_FILE (true for zero-byte files); any other read fails explicitly with
// STATUS_NOT_SUPPORTED. Never success with fabricated or zero bytes.
NTSTATUS StatusForRead(std::uint64_t fileSize, std::uint64_t offset);

// Every mutation on this read-only volume.
constexpr NTSTATUS kReadOnlyStatus = STATUS_MEDIA_WRITE_PROTECTED;

// Access rights that would modify something.
constexpr std::uint32_t kWriteAccessMask = FILE_WRITE_DATA | FILE_APPEND_DATA | FILE_WRITE_EA |
                                           FILE_WRITE_ATTRIBUTES | DELETE | WRITE_DAC | WRITE_OWNER;

}  // namespace hd
