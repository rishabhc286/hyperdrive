// SPDX-License-Identifier: GPL-3.0-only
#include "text.hpp"

#include <windows.h>

#include <vector>

namespace hd {

std::wstring Utf8ToWide(const std::string& utf8) {
    if (utf8.empty()) return {};
    int n = MultiByteToWideChar(CP_UTF8, 0, utf8.data(), static_cast<int>(utf8.size()), nullptr, 0);
    if (n <= 0) return {};
    std::wstring out(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, utf8.data(), static_cast<int>(utf8.size()), out.data(), n);
    return out;
}

std::string WideToUtf8(const std::wstring& wide) {
    if (wide.empty()) return {};
    int n = WideCharToMultiByte(CP_UTF8, 0, wide.data(), static_cast<int>(wide.size()), nullptr, 0, nullptr, nullptr);
    if (n <= 0) return {};
    std::string out(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, wide.data(), static_cast<int>(wide.size()), out.data(), n, nullptr, nullptr);
    return out;
}

std::uint64_t Fnv1a64(const std::string& bytes) {
    std::uint64_t h = 1469598103934665603ULL;
    for (unsigned char c : bytes) {
        h ^= c;
        h *= 1099511628211ULL;
    }
    return h;
}

std::wstring HexSuffix(const std::string& value, int digits) {
    if (digits < 1) digits = 1;
    if (digits > 16) digits = 16;
    static const wchar_t* hex = L"0123456789abcdef";
    std::uint64_t h = Fnv1a64(value);
    std::wstring out;
    for (int i = 0; i < digits; i++) {
        out.push_back(hex[(h >> (60 - 4 * i)) & 0xF]);
    }
    return out;
}

std::wstring UpcaseKey(const std::wstring& name) {
    if (name.empty()) return {};
    int n = LCMapStringEx(LOCALE_NAME_INVARIANT, LCMAP_UPPERCASE, name.data(), static_cast<int>(name.size()), nullptr,
                          0, nullptr, nullptr, 0);
    if (n <= 0) return name;
    std::wstring out(static_cast<size_t>(n), L'\0');
    LCMapStringEx(LOCALE_NAME_INVARIANT, LCMAP_UPPERCASE, name.data(), static_cast<int>(name.size()), out.data(), n,
                  nullptr, nullptr, 0);
    return out;
}

int CompareNames(const std::wstring& a, const std::wstring& b) {
    int r = CompareStringOrdinal(a.data(), static_cast<int>(a.size()), b.data(), static_cast<int>(b.size()), TRUE);
    return r == CSTR_LESS_THAN ? -1 : r == CSTR_GREATER_THAN ? 1 : 0;
}

}  // namespace hd
