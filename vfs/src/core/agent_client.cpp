// SPDX-License-Identifier: GPL-3.0-only
#include "agent_client.hpp"

#include <nlohmann/json.hpp>

#include <cstdint>
#include <cstdio>

#include <windows.h>

namespace hd {

using nlohmann::json;

const char* FaultCode(Fault f) {
    switch (f) {
        case Fault::None: return "none";
        case Fault::NotFound: return "not-found";
        case Fault::AuthorizationLost: return "authorization-lost";
        case Fault::Forbidden: return "forbidden";
        case Fault::NeedsReconnect: return "needs-reconnect";
        case Fault::Busy: return "busy";
        case Fault::Timeout: return "timeout";
        case Fault::Unavailable: return "unavailable";
        case Fault::TooLarge: return "too-large";
        case Fault::Invalid: return "invalid-response";
        case Fault::VersionChanged: return "version-changed";
        case Fault::Stale: return "stale-handle";
        case Fault::Cancelled: return "cancelled";
        case Fault::Unsupported: return "unsupported";
        case Fault::TooManyOpen: return "too-many-open";
        case Fault::Unmounting: return "unmounting";
    }
    return "unknown";
}

namespace {

std::string ErrorCode(const std::string& body) {
    json j = json::parse(body, nullptr, false);
    if (j.is_object() && j.contains("code") && j["code"].is_string()) return j["code"].get<std::string>();
    return {};
}

bool IsUuid(const std::string& s) {
    if (s.size() != 36) return false;
    for (std::size_t i = 0; i < s.size(); i++) {
        char c = s[i];
        bool dash = i == 8 || i == 13 || i == 18 || i == 23;
        if (dash ? c != '-' : !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
    }
    return true;
}

std::optional<std::string> OptString(const json& o, const char* key, std::size_t max, bool& bad) {
    if (!o.contains(key) || o[key].is_null()) return std::nullopt;
    if (!o[key].is_string() || o[key].get_ref<const std::string&>().size() > max) {
        bad = true;
        return std::nullopt;
    }
    return o[key].get<std::string>();
}

}  // namespace

Fault FaultFor(const HttpResult& r) {
    switch (r.kind) {
        case HttpResult::Kind::Timeout: return Fault::Timeout;
        case HttpResult::Kind::Unreachable: return Fault::Unavailable;
        case HttpResult::Kind::TooLarge: return Fault::TooLarge;
        case HttpResult::Kind::Failed: return Fault::Unavailable;
        case HttpResult::Kind::Ok: break;
    }
    if (r.status == 200 || r.status == 201) return Fault::None;
    return FaultForStatus(r.status, ErrorCode(r.body));
}

Fault FaultForStatus(int status, const std::string& code) {
    // Diagnostic only: status number and the agent's fixed error code (never names or secrets).
    std::fprintf(stderr, "[hyperdrive-vfs] agent answered %d (%s)\n", status, code.size() <= 40 ? code.c_str() : "?");
    switch (status) {
        case 400: return Fault::Invalid;
        case 401: return Fault::AuthorizationLost;
        case 403: return Fault::Forbidden;
        case 404: return Fault::NotFound;
        case 409:
            if (code == "needs_reconnect") return Fault::NeedsReconnect;
            if (code == "file_changed") return Fault::VersionChanged;
            if (code == "cancelled") return Fault::Cancelled;
            return Fault::Forbidden;  // credential rejected, content permission missing, ...
        case 410: return Fault::Stale;
        case 413: return Fault::TooLarge;
        case 415: return Fault::Unsupported;
        case 429: return Fault::Busy;
        case 503:
            if (code == "too_busy") return Fault::Busy;
            if (code == "too_many_open_files") return Fault::TooManyOpen;
            return Fault::Unavailable;
        case 504: return Fault::Timeout;
        default: return Fault::Unavailable;
    }
}

namespace {

bool IsReadHandle(const std::string& s) {
    if (s.size() != 47 || s.compare(0, 4, "hdf_") != 0) return false;
    for (std::size_t i = 4; i < s.size(); i++) {
        char ch = s[i];
        bool ok = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_';
        if (!ok) return false;
    }
    return true;
}

// Decimal, no sign, no spaces, at most 20 digits, no overflow.
std::optional<std::uint64_t> ParseU64(const std::optional<std::string>& v) {
    if (!v || v->empty() || v->size() > 20) return std::nullopt;
    std::uint64_t n = 0;
    for (char ch : *v) {
        if (ch < '0' || ch > '9') return std::nullopt;
        std::uint64_t d = static_cast<std::uint64_t>(ch - '0');
        if (n > (UINT64_MAX - d) / 10) return std::nullopt;
        n = n * 10 + d;
    }
    return n;
}

}  // namespace

RangeRead ValidateRange(const BinaryResult& r, std::uint64_t offset, std::uint32_t length) {
    RangeRead out;
    switch (r.kind) {
        case HttpResult::Kind::Timeout: out.fault = Fault::Timeout; return out;
        case HttpResult::Kind::Unreachable:
        case HttpResult::Kind::Failed: out.fault = Fault::Unavailable; return out;
        case HttpResult::Kind::TooLarge: out.fault = Fault::Invalid; return out;  // more bytes than asked for
        case HttpResult::Kind::Ok: break;
    }
    if (r.status != 200) {
        out.fault = FaultForStatus(r.status, ErrorCode(r.errorBody));
        return out;
    }
    auto declared = ParseU64(r.contentLength);
    auto echoedLength = ParseU64(r.readLength);
    auto echoedOffset = ParseU64(r.readOffset);
    bool typeOk = r.contentType.rfind("application/octet-stream", 0) == 0;
    // The VFS never asks past the size the agent pinned, so the answer must be exactly what was asked.
    if (!typeOk || !declared || !echoedLength || !echoedOffset || *declared != length || *echoedLength != length ||
        *echoedOffset != offset || r.bytes != length || !r.eof || (*r.eof != "true" && *r.eof != "false")) {
        std::fprintf(stderr, "[hyperdrive-vfs] content answer rejected (inconsistent length/offset/type)\n");
        out.fault = Fault::Invalid;
        return out;
    }
    out.eof = *r.eof == "true";
    return out;
}

Result<long long> AgentClient::Session() {
    Result<long long> out;
    HttpResult r = transport_.Post(L"/local/v1/vfs/session", "{}");
    out.fault = FaultFor(r);
    if (!out.ok()) return out;
    json j = json::parse(r.body, nullptr, false);
    if (!j.is_object() || !j.contains("generation") || !j["generation"].is_number_integer() ||
        j.value("protocolVersion", 0) != 1) {
        out.fault = Fault::Invalid;
        return out;
    }
    out.value = j["generation"].get<long long>();
    return out;
}

Result<std::vector<RemoteAccount>> AgentClient::Accounts() {
    Result<std::vector<RemoteAccount>> out;
    HttpResult r = transport_.Post(L"/local/v1/vfs/accounts", "{}");
    out.fault = FaultFor(r);
    if (!out.ok()) return out;
    json j = json::parse(r.body, nullptr, false);
    if (!j.is_object() || !j.contains("items") || !j["items"].is_array() || j["items"].size() > 1000) {
        out.fault = Fault::Invalid;
        return out;
    }
    for (const auto& item : j["items"]) {
        bool bad = false;
        RemoteAccount a;
        a.id = OptString(item, "id", 36, bad).value_or("");
        a.provider = OptString(item, "provider", 32, bad).value_or("");
        a.rootType = OptString(item, "rootType", 32, bad).value_or("");
        a.status = OptString(item, "status", 32, bad).value_or("");
        bool known = a.provider == "GOOGLE_DRIVE" || a.provider == "DROPBOX" || (fixtureMode_ && a.provider == "FIXTURE");
        if (bad || !IsUuid(a.id) || !known) {
            out.fault = Fault::Invalid;
            out.value.clear();
            return out;
        }
        out.value.push_back(std::move(a));
    }
    return out;
}

Result<std::vector<RemoteEntry>> AgentClient::List(const std::string& accountId,
                                                   const std::optional<std::string>& locator) {
    Result<std::vector<RemoteEntry>> out;
    json request = {{"accountId", accountId}};
    if (locator) request["directory"] = *locator;
    HttpResult r = transport_.Post(L"/local/v1/vfs/list", request.dump());
    out.fault = FaultFor(r);
    if (!out.ok()) return out;
    json j = json::parse(r.body, nullptr, false);
    // The agent assembles every provider page; a listing that isn't complete is never shown.
    if (!j.is_object() || j.value("complete", false) != true || !j.contains("items") || !j["items"].is_array()) {
        out.fault = Fault::Invalid;
        return out;
    }
    if (j["items"].size() > kMaxListingEntries) {
        out.fault = Fault::TooLarge;
        return out;
    }
    for (const auto& item : j["items"]) {
        bool bad = !item.is_object();
        RemoteEntry e;
        if (!bad) {
            e.id = OptString(item, "id", 512, bad).value_or("");
            e.name = OptString(item, "name", 1024, bad).value_or("");
            e.kind = OptString(item, "kind", 32, bad).value_or("");
            e.directoryLocator = OptString(item, "directoryLocator", 4096, bad);
            if (item.contains("sizeBytes") && !item["sizeBytes"].is_null()) {
                if (!item["sizeBytes"].is_number_unsigned() && !(item["sizeBytes"].is_number_integer() &&
                                                                 item["sizeBytes"].get<long long>() >= 0)) {
                    bad = true;
                } else {
                    e.sizeBytes = item["sizeBytes"].get<std::uint64_t>();
                }
            }
            if (item.contains("modifiedMillis") && !item["modifiedMillis"].is_null()) {
                if (!item["modifiedMillis"].is_number_integer()) {
                    bad = true;
                } else {
                    e.modifiedMillis = item["modifiedMillis"].get<std::int64_t>();
                }
            }
        }
        if (bad || e.id.empty() ||
            (e.kind != "FILE" && e.kind != "FOLDER" && e.kind != "SHORTCUT" && e.kind != "PROVIDER_DOCUMENT")) {
            out.fault = Fault::Invalid;
            out.value.clear();
            return out;
        }
        out.value.push_back(std::move(e));
    }
    return out;
}

Result<OpenedContent> AgentClient::OpenRead(const std::string& accountId, const std::string& fileId,
                                            std::uint64_t expectedSize) {
    Result<OpenedContent> out;
    if (fixtureMode_) {
        out.fault = Fault::Unsupported;  // fixture data has no content; never fabricated
        return out;
    }
    json request = {{"accountId", accountId}, {"fileId", fileId}, {"expectedSize", expectedSize}};
    HttpResult r = transport_.Post(L"/local/v1/vfs/open", request.dump());
    out.fault = FaultFor(r);
    if (!out.ok()) return out;
    json j = json::parse(r.body, nullptr, false);
    if (r.status != 201 || !j.is_object() || !j.contains("handle") || !j["handle"].is_string() ||
        !IsReadHandle(j["handle"].get<std::string>()) || !j.contains("sizeBytes") || !j["sizeBytes"].is_number_unsigned() ||
        j["sizeBytes"].get<std::uint64_t>() != expectedSize || !j.contains("consistency") || !j["consistency"].is_string()) {
        out.fault = Fault::Invalid;
        return out;
    }
    std::string consistency = j["consistency"].get<std::string>();
    if (consistency != "dropbox-revision-pinned" && consistency != "google-unpinned") {
        out.fault = Fault::Invalid;
        return out;
    }
    out.value.handle = j["handle"].get<std::string>();
    out.value.size = expectedSize;
    out.value.consistency = consistency;
    return out;
}

RangeRead AgentClient::ReadRange(const std::string& handle, std::uint64_t offset, std::uint32_t length,
                                 void* destination) {
    RangeRead out;
    if (length == 0 || length > kMaxAgentRead || offset > UINT64_MAX - length || !IsReadHandle(handle)) {
        out.fault = Fault::Invalid;
        return out;
    }
    json request = {{"handle", handle}, {"offset", offset}, {"length", length}};
    std::string body = request.dump();
    BinaryResult r = transport_.PostForBinary(L"/local/v1/vfs/read", body, destination, length, kContentReceiveMs);
    SecureZeroMemory(body.data(), body.size());  // the handle is a (narrow) bearer secret
    return ValidateRange(r, offset, length);
}

void AgentClient::CloseRead(const std::string& handle) {
    if (!IsReadHandle(handle)) return;
    json request = {{"handle", handle}};
    std::string body = request.dump();
    transport_.Post(L"/local/v1/vfs/close", body);  // best effort; the agent also drops it on unmount
    SecureZeroMemory(body.data(), body.size());
}

void AgentClient::CancelReads() {
    if (!fixtureMode_) transport_.Post(L"/local/v1/vfs/cancel", "{}");
}

}  // namespace hd
