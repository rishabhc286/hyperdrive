// SPDX-License-Identifier: GPL-3.0-only
#include "agent_client.hpp"

#include <nlohmann/json.hpp>

#include <cstdio>

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
    if (r.status == 200) return Fault::None;
    std::string code = ErrorCode(r.body);
    // Diagnostic only: status number and the agent's fixed error code (never names or secrets).
    std::fprintf(stderr, "[hyperdrive-vfs] agent answered %d (%s)\n", r.status,
                 code.size() <= 40 ? code.c_str() : "?");
    switch (r.status) {
        case 401: return Fault::AuthorizationLost;
        case 403: return Fault::Forbidden;
        case 404: return Fault::NotFound;
        case 409: return code == "needs_reconnect" ? Fault::NeedsReconnect : Fault::Forbidden;
        case 413: return Fault::TooLarge;
        case 429: return Fault::Busy;
        case 503: return code == "too_busy" ? Fault::Busy : Fault::Unavailable;
        case 504: return Fault::Timeout;
        default: return Fault::Unavailable;
    }
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

}  // namespace hd
