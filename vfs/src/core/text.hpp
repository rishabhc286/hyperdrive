// SPDX-License-Identifier: GPL-3.0-only
// Text helpers: UTF-8 <-> UTF-16 and stable hashing for name suffixes.
#pragma once

#include <cstdint>
#include <string>

namespace hd {

// UTF-8 -> UTF-16. Invalid sequences become U+FFFD (deterministic, never throws on content).
std::wstring Utf8ToWide(const std::string& utf8);
// UTF-16 -> UTF-8. Unpaired surrogates become U+FFFD.
std::string WideToUtf8(const std::wstring& wide);

// 64-bit FNV-1a over the UTF-8 bytes. Stable across runs, machines and builds; not a security hash.
std::uint64_t Fnv1a64(const std::string& bytes);
// Lowercase hex of the first `digits` nibbles (1..16) of Fnv1a64(value).
std::wstring HexSuffix(const std::string& value, int digits);

// Case-insensitive ordinal key (invariant uppercase), matching how a case-insensitive Windows
// volume compares names closely enough for collision detection and ordering.
std::wstring UpcaseKey(const std::wstring& name);
// <0, 0, >0 like wcscmp, case-insensitive ordinal.
int CompareNames(const std::wstring& a, const std::wstring& b);

}  // namespace hd
