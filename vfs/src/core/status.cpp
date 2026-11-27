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
        case Fault::VersionChanged:
        case Fault::Stale: return STATUS_FILE_INVALID;
        case Fault::Cancelled: return STATUS_CANCELLED;
        case Fault::Unsupported: return STATUS_NOT_SUPPORTED;
        case Fault::TooManyOpen: return STATUS_TOO_MANY_OPENED_FILES;
        case Fault::Unmounting: return STATUS_VOLUME_DISMOUNTED;
    }
    return STATUS_UNEXPECTED_NETWORK_ERROR;
}

NTSTATUS StatusForContentFault(Fault fault) {
    switch (fault) {
        case Fault::None: return STATUS_SUCCESS;
        // The open file no longer matches what Windows was shown: gone, changed, or its handle died.
        case Fault::NotFound:
        case Fault::VersionChanged:
        case Fault::Stale: return STATUS_FILE_INVALID;
        // Authorization: capability/grant ended, reconnect needed, token rejected, no content permission.
        case Fault::AuthorizationLost:
        case Fault::Forbidden:
        case Fault::NeedsReconnect: return STATUS_ACCESS_DENIED;
        case Fault::Unsupported: return STATUS_NOT_SUPPORTED;
        case Fault::Busy: return STATUS_DEVICE_BUSY;
        case Fault::Timeout: return STATUS_IO_TIMEOUT;
        case Fault::Cancelled: return STATUS_CANCELLED;
        case Fault::TooManyOpen: return STATUS_TOO_MANY_OPENED_FILES;
        case Fault::Unmounting: return STATUS_VOLUME_DISMOUNTED;
        case Fault::Unavailable:
        case Fault::Invalid:
        case Fault::TooLarge: return STATUS_UNEXPECTED_NETWORK_ERROR;
    }
    return STATUS_UNEXPECTED_NETWORK_ERROR;
}

}  // namespace hd
