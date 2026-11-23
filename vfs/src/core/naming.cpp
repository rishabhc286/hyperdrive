// SPDX-License-Identifier: GPL-3.0-only
#include "naming.hpp"

#include <algorithm>
#include <map>
#include <set>

#include "text.hpp"

namespace hd {
namespace {

bool IsInvalidChar(wchar_t c) {
    return c < 0x20 || c == L'<' || c == L'>' || c == L':' || c == L'"' || c == L'/' || c == L'\\' || c == L'|' ||
           c == L'?' || c == L'*';
}

bool IsHighSurrogate(wchar_t c) { return c >= 0xD800 && c <= 0xDBFF; }

// Index of the extension dot, or npos. A leading dot (".gitignore") is not an extension.
size_t ExtensionDot(const std::wstring& name) {
    size_t dot = name.rfind(L'.');
    if (dot == std::wstring::npos || dot == 0 || dot + 1 >= name.size()) return std::wstring::npos;
    if (name.size() - dot > 16) return std::wstring::npos;  // long "extensions" are just part of the name
    return dot;
}

// Truncate to at most `max` UTF-16 units without splitting a surrogate pair.
std::wstring TruncateUnits(const std::wstring& s, size_t max) {
    if (s.size() <= max) return s;
    size_t cut = max;
    if (cut > 0 && IsHighSurrogate(s[cut - 1])) cut--;
    return s.substr(0, cut);
}

std::wstring FitLength(const std::wstring& name, size_t max) {
    if (name.size() <= max) return name;
    size_t dot = ExtensionDot(name);
    if (dot == std::wstring::npos) return TruncateUnits(name, max);
    std::wstring ext = name.substr(dot);
    std::wstring base = name.substr(0, dot);
    if (ext.size() >= max) return TruncateUnits(name, max);
    return TruncateUnits(base, max - ext.size()) + ext;
}

}  // namespace

bool IsReservedDeviceName(const std::wstring& name) {
    std::wstring base = name.substr(0, name.find(L'.'));
    while (!base.empty() && base.back() == L' ') base.pop_back();
    std::wstring up = UpcaseKey(base);
    static const std::set<std::wstring> fixed = {L"CON", L"PRN", L"AUX", L"NUL", L"CONIN$", L"CONOUT$"};
    if (fixed.count(up)) return true;
    if (up.size() == 4 && (up.rfind(L"COM", 0) == 0 || up.rfind(L"LPT", 0) == 0)) {
        wchar_t d = up[3];
        return (d >= L'0' && d <= L'9') || d == L'¹' || d == L'²' || d == L'³';
    }
    return false;
}

SanitizedName SanitizeComponent(const std::wstring& raw) {
    SanitizedName out;
    std::wstring s = raw;
    for (auto& c : s) {
        if (IsInvalidChar(c)) {
            c = L'_';
            out.changed = true;
        }
    }
    for (size_t i = s.size(); i > 0 && (s[i - 1] == L' ' || s[i - 1] == L'.'); i--) {
        s[i - 1] = L'_';
        out.changed = true;
    }
    if (s.empty()) {
        s = L"_";
        out.changed = true;
    }
    if (IsReservedDeviceName(s)) {
        size_t dot = s.find(L'.');
        s.insert(dot == std::wstring::npos ? s.size() : dot, L"_");
        out.changed = true;
    }
    std::wstring fitted = FitLength(s, kMaxComponent);
    if (fitted != s) out.changed = true;
    out.name = fitted;
    return out;
}

std::wstring InsertSuffix(const std::wstring& name, const std::wstring& suffix) {
    std::wstring tag = L" [" + suffix + L"]";
    // Keep room for the suffix within the component limit.
    std::wstring fitted = FitLength(name, kMaxComponent - tag.size());
    size_t dot = ExtensionDot(fitted);
    if (dot == std::wstring::npos) return fitted + tag;
    return fitted.substr(0, dot) + tag + fitted.substr(dot);
}

std::vector<std::wstring> AssignNames(const std::vector<NamingInput>& inputs) {
    const size_t n = inputs.size();
    std::vector<std::wstring> names(n);
    for (size_t i = 0; i < n; i++) names[i] = SanitizeComponent(inputs[i].rawName).name;

    // Group by case-insensitive key; every member of a colliding group gets an id suffix. Widen the
    // suffix for any remaining collision; a final, order-independent "~n" (by id) is the backstop.
    for (int digits : {8, 16}) {
        std::map<std::wstring, std::vector<size_t>> groups;
        for (size_t i = 0; i < n; i++) groups[UpcaseKey(names[i])].push_back(i);
        bool collided = false;
        for (auto& [key, members] : groups) {
            if (members.size() < 2) continue;
            collided = true;
            for (size_t i : members) {
                names[i] = InsertSuffix(SanitizeComponent(inputs[i].rawName).name, HexSuffix(inputs[i].id, digits));
            }
        }
        if (!collided) return names;
    }
    std::map<std::wstring, std::vector<size_t>> groups;
    for (size_t i = 0; i < n; i++) groups[UpcaseKey(names[i])].push_back(i);
    for (auto& [key, members] : groups) {
        if (members.size() < 2) continue;
        std::sort(members.begin(), members.end(), [&](size_t a, size_t b) { return inputs[a].id < inputs[b].id; });
        for (size_t k = 1; k < members.size(); k++) {
            names[members[k]] = InsertSuffix(names[members[k]], L"~" + std::to_wstring(k));
        }
    }
    return names;
}

std::wstring AccountDirectoryBaseName(const std::string& provider, const std::string& rootType) {
    std::wstring p = provider == "GOOGLE_DRIVE" ? L"Google Drive"
                     : provider == "DROPBOX"    ? L"Dropbox"
                     : provider == "FIXTURE"    ? L"FIXTURE (test data)"
                                                : L"Cloud";
    std::wstring space = rootType == "TEAM" ? L"Team space" : L"Personal";
    return p + L" - " + space;
}

}  // namespace hd
