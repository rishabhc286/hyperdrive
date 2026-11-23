// SPDX-License-Identifier: GPL-3.0-only
#include <cstdio>
#include <string>

#include <windows.h>
#include <winhttp.h>

#include "transport.hpp"

namespace hd {
namespace {

class WinHttpTransport final : public AgentTransport {
  public:
    WinHttpTransport(int port, const std::string& capability, const TransportLimits& limits)
        : limits_(limits) {
        auth_ = L"Authorization: Bearer " + std::wstring(capability.begin(), capability.end()) +
                L"\r\nContent-Type: application/json\r\n";
        session_ = WinHttpOpen(L"HyperDrive-VFS/0.1", WINHTTP_ACCESS_TYPE_NO_PROXY, WINHTTP_NO_PROXY_NAME,
                               WINHTTP_NO_PROXY_BYPASS, 0);
        if (session_) {
            WinHttpSetTimeouts(session_, limits.resolveMs, limits.connectMs, limits.sendMs, limits.receiveMs);
            connect_ = WinHttpConnect(session_, L"127.0.0.1", static_cast<INTERNET_PORT>(port), 0);
        }
    }

    ~WinHttpTransport() override {
        SecureZeroMemory(auth_.data(), auth_.size() * sizeof(wchar_t));  // best effort only
        if (connect_) WinHttpCloseHandle(connect_);
        if (session_) WinHttpCloseHandle(session_);
    }

    HttpResult Post(const std::wstring& path, const std::string& jsonBody) override {
        HttpResult result;
        if (!connect_) {
            result.kind = HttpResult::Kind::Unreachable;
            return result;
        }
        HINTERNET request = WinHttpOpenRequest(connect_, L"POST", path.c_str(), nullptr, WINHTTP_NO_REFERER,
                                               WINHTTP_DEFAULT_ACCEPT_TYPES, 0);
        if (!request) return Fail(result);
        DWORD disable = WINHTTP_DISABLE_REDIRECTS | WINHTTP_DISABLE_COOKIES | WINHTTP_DISABLE_AUTHENTICATION;
        WinHttpSetOption(request, WINHTTP_OPTION_DISABLE_FEATURE, &disable, sizeof disable);
        BOOL ok = WinHttpSendRequest(request, auth_.c_str(), static_cast<DWORD>(-1L),
                                     const_cast<char*>(jsonBody.data()), static_cast<DWORD>(jsonBody.size()),
                                     static_cast<DWORD>(jsonBody.size()), 0) &&
                  WinHttpReceiveResponse(request, nullptr);
        if (!ok) {
            Classify(result, GetLastError());
            WinHttpCloseHandle(request);
            return result;
        }
        DWORD status = 0, size = sizeof status;
        WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, WINHTTP_HEADER_NAME_BY_INDEX,
                            &status, &size, WINHTTP_NO_HEADER_INDEX);
        result.status = static_cast<int>(status);
        for (;;) {
            DWORD available = 0;
            if (!WinHttpQueryDataAvailable(request, &available)) {
                Classify(result, GetLastError());
                WinHttpCloseHandle(request);
                return result;
            }
            if (available == 0) break;
            if (result.body.size() + available > limits_.maxResponseBytes) {
                result.kind = HttpResult::Kind::TooLarge;
                result.body.clear();
                WinHttpCloseHandle(request);
                return result;
            }
            std::size_t offset = result.body.size();
            result.body.resize(offset + available);
            DWORD read = 0;
            if (!WinHttpReadData(request, result.body.data() + offset, available, &read)) {
                Classify(result, GetLastError());
                WinHttpCloseHandle(request);
                return result;
            }
            result.body.resize(offset + read);
        }
        WinHttpCloseHandle(request);
        result.kind = HttpResult::Kind::Ok;
        return result;
    }

  private:
    static HttpResult& Fail(HttpResult& r) {
        r.kind = HttpResult::Kind::Failed;
        return r;
    }

    static void Classify(HttpResult& r, DWORD error) {
        r.body.clear();
        // Diagnostic only: an error number, never a URL, header, name or body.
        std::fprintf(stderr, "[hyperdrive-vfs] agent request failed (winhttp error %lu)\n",
                     static_cast<unsigned long>(error));
        if (error == ERROR_WINHTTP_TIMEOUT) {
            r.kind = HttpResult::Kind::Timeout;
        } else if (error == ERROR_WINHTTP_CANNOT_CONNECT || error == ERROR_WINHTTP_CONNECTION_ERROR ||
                   error == ERROR_WINHTTP_NAME_NOT_RESOLVED) {
            r.kind = HttpResult::Kind::Unreachable;
        } else {
            r.kind = HttpResult::Kind::Failed;
        }
    }

    TransportLimits limits_;
    std::wstring auth_;
    HINTERNET session_ = nullptr;
    HINTERNET connect_ = nullptr;
};

}  // namespace

std::unique_ptr<AgentTransport> MakeWinHttpTransport(int port, const std::string& capability,
                                                     const TransportLimits& limits) {
    return std::make_unique<WinHttpTransport>(port, capability, limits);
}

}  // namespace hd
