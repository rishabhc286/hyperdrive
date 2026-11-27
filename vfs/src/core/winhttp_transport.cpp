// SPDX-License-Identifier: GPL-3.0-only
#include <cstdint>
#include <cstdio>
#include <optional>
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

    BinaryResult PostForBinary(const std::wstring& path, const std::string& jsonBody, void* destination,
                               std::size_t capacity, int receiveMs) override {
        BinaryResult result;
        if (!connect_) {
            result.kind = HttpResult::Kind::Unreachable;
            return result;
        }
        HINTERNET request = WinHttpOpenRequest(connect_, L"POST", path.c_str(), nullptr, WINHTTP_NO_REFERER,
                                               WINHTTP_DEFAULT_ACCEPT_TYPES, 0);
        if (!request) return result;
        DWORD disable = WINHTTP_DISABLE_REDIRECTS | WINHTTP_DISABLE_COOKIES | WINHTTP_DISABLE_AUTHENTICATION;
        WinHttpSetOption(request, WINHTTP_OPTION_DISABLE_FEATURE, &disable, sizeof disable);
        WinHttpSetTimeouts(request, limits_.resolveMs, limits_.connectMs, limits_.sendMs, receiveMs);
        BOOL ok = WinHttpSendRequest(request, auth_.c_str(), static_cast<DWORD>(-1L),
                                     const_cast<char*>(jsonBody.data()), static_cast<DWORD>(jsonBody.size()),
                                     static_cast<DWORD>(jsonBody.size()), 0) &&
                  WinHttpReceiveResponse(request, nullptr);
        if (!ok) {
            DWORD error = GetLastError();
            HttpResult tmp;
            Classify(tmp, error);
            result.kind = tmp.kind;
            WinHttpCloseHandle(request);
            return result;
        }
        DWORD status = 0, size = sizeof status;
        WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, WINHTTP_HEADER_NAME_BY_INDEX,
                            &status, &size, WINHTTP_NO_HEADER_INDEX);
        result.status = static_cast<int>(status);
        result.contentType = Header(request, WINHTTP_QUERY_CONTENT_TYPE, nullptr).value_or("");
        result.contentLength = Header(request, WINHTTP_QUERY_CONTENT_LENGTH, nullptr);
        result.readOffset = Header(request, WINHTTP_QUERY_CUSTOM, L"X-HyperDrive-Read-Offset");
        result.readLength = Header(request, WINHTTP_QUERY_CUSTOM, L"X-HyperDrive-Read-Length");
        result.eof = Header(request, WINHTTP_QUERY_CUSTOM, L"X-HyperDrive-EOF");

        // 200: bytes go straight into the caller's buffer (no intermediate copy); anything beyond
        // `capacity` is an error, never silently dropped. Errors: a small bounded JSON body.
        bool binary = result.status == 200;
        auto* out = static_cast<unsigned char*>(destination);
        std::size_t limit = binary ? capacity : kMaxErrorBody;
        std::uint64_t got = 0;
        for (;;) {
            DWORD available = 0;
            if (!WinHttpQueryDataAvailable(request, &available)) {
                HttpResult tmp;
                Classify(tmp, GetLastError());
                result.kind = tmp.kind;
                result.errorBody.clear();
                WinHttpCloseHandle(request);
                return result;
            }
            if (available == 0) break;
            if (got + available > limit) {
                result.kind = HttpResult::Kind::TooLarge;
                result.errorBody.clear();
                WinHttpCloseHandle(request);
                return result;
            }
            DWORD read = 0;
            BOOL readOk;
            if (binary) {
                readOk = WinHttpReadData(request, out + got, available, &read);
            } else {
                std::size_t at = result.errorBody.size();
                result.errorBody.resize(at + available);
                readOk = WinHttpReadData(request, result.errorBody.data() + at, available, &read);
                result.errorBody.resize(at + (readOk ? read : 0));
            }
            if (!readOk) {
                HttpResult tmp;
                Classify(tmp, GetLastError());
                result.kind = tmp.kind;
                result.errorBody.clear();
                WinHttpCloseHandle(request);
                return result;
            }
            got += read;
        }
        WinHttpCloseHandle(request);
        result.bytes = binary ? got : 0;
        result.kind = HttpResult::Kind::Ok;
        return result;
    }

  private:
    // One response header as ASCII (bounded), or nullopt when absent.
    static std::optional<std::string> Header(HINTERNET request, DWORD query, const wchar_t* name) {
        wchar_t buffer[128];
        DWORD bytes = sizeof buffer;
        if (!WinHttpQueryHeaders(request, query, name == nullptr ? WINHTTP_HEADER_NAME_BY_INDEX : name, buffer, &bytes,
                                 WINHTTP_NO_HEADER_INDEX)) {
            return std::nullopt;  // absent, or longer than any valid value (rejected by the caller)
        }
        std::string value;
        for (DWORD i = 0; i < bytes / sizeof(wchar_t); i++) {
            if (buffer[i] > 0x7e || buffer[i] < 0x20) return std::string("\x01");  // never a valid value
            value.push_back(static_cast<char>(buffer[i]));
        }
        return value;
    }

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
