// SPDX-License-Identifier: GPL-3.0-only
#include "bootstrap.hpp"

#include <nlohmann/json.hpp>
#include <regex>

namespace hd {

bool IsAllowedDriveLetter(wchar_t letter) {
    // A, B and C are never used (floppy legacy / system drive).
    return letter >= L'D' && letter <= L'Z';
}

bool ParseBootstrap(const std::string& line, Bootstrap& out, std::string& errorCode) {
    errorCode = "bootstrap_invalid";
    if (line.size() > kMaxBootstrapBytes) {
        errorCode = "bootstrap_too_large";
        return false;
    }
    nlohmann::json j = nlohmann::json::parse(line, nullptr, false);
    if (!j.is_object() || j.size() != 4) return false;
    for (const char* key : {"protocolVersion", "agentPort", "capability", "mount"}) {
        if (!j.contains(key)) return false;
    }
    if (!j["protocolVersion"].is_number_integer() || j["protocolVersion"].get<long long>() != kVfsProtocolVersion) {
        errorCode = "protocol_mismatch";
        return false;
    }
    if (!j["agentPort"].is_number_integer()) return false;
    long long port = j["agentPort"].get<long long>();
    if (port < 1024 || port > 65535) return false;
    if (!j["capability"].is_string()) return false;
    const std::string& cap = j["capability"].get_ref<const std::string&>();
    static const std::regex capability("^hdv_[A-Za-z0-9_-]{43}$");
    if (!std::regex_match(cap, capability)) return false;
    const auto& mount = j["mount"];
    if (!mount.is_object() || mount.size() != 1 || !mount.contains("driveLetter") || !mount["driveLetter"].is_string())
        return false;
    const std::string& letter = mount["driveLetter"].get_ref<const std::string&>();
    if (letter.size() != 1 || !IsAllowedDriveLetter(static_cast<wchar_t>(letter[0]))) {
        errorCode = "invalid_drive_letter";
        return false;
    }
    out.agentPort = static_cast<int>(port);
    out.capability = cap;
    out.driveLetter = static_cast<wchar_t>(letter[0]);
    errorCode.clear();
    return true;
}

}  // namespace hd
