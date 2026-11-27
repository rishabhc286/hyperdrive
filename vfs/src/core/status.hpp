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

// How a metadata fault (lookup, listing) surfaces to Windows.
NTSTATUS StatusForFault(Fault fault);

// How a content fault (OpenRead / Read through an agent read handle) surfaces to a Read callback
// (ADR 0005). Never success: a failed read reports 0 bytes and one of these statuses.
NTSTATUS StatusForContentFault(Fault fault);

// Every mutation on this read-only volume.
constexpr NTSTATUS kReadOnlyStatus = STATUS_MEDIA_WRITE_PROTECTED;

// Access rights that would modify something.
constexpr std::uint32_t kWriteAccessMask = FILE_WRITE_DATA | FILE_APPEND_DATA | FILE_WRITE_EA |
                                           FILE_WRITE_ATTRIBUTES | DELETE | WRITE_DAC | WRITE_OWNER;

}  // namespace hd
