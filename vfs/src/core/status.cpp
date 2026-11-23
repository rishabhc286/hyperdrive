// SPDX-License-Identifier: GPL-3.0-only
#include "status.hpp"

namespace hd {

NTSTATUS StatusForFault(Fault fault) {
    switch (fault) {
        case Fault::None: return STATUS_SUCCESS;
        case Fault::NotFound: return STATUS_OBJECT_PATH_NOT_FOUND;
        case Fault::AuthorizationLost:
        case Fault::Forbidden:
        case Fault::NeedsReconnect: return STATUS_ACCESS_DENIED;
        case Fault::Busy: return STATUS_DEVICE_BUSY;
        case Fault::Timeout: return STATUS_IO_TIMEOUT;
        case Fault::Unavailable: return STATUS_UNEXPECTED_NETWORK_ERROR;
        case Fault::TooLarge: return STATUS_INSUFFICIENT_RESOURCES;
        case Fault::Invalid: return STATUS_UNEXPECTED_NETWORK_ERROR;
    }
    return STATUS_UNEXPECTED_NETWORK_ERROR;
}

NTSTATUS StatusForRead(std::uint64_t fileSize, std::uint64_t offset) {
    if (offset >= fileSize) return STATUS_END_OF_FILE;
    return STATUS_NOT_SUPPORTED;
}

}  // namespace hd
