// SPDX-License-Identifier: GPL-3.0-only
// Deterministic mapping from provider names to Windows-visible names.
//
// Rules (docs/virtual-drive.md):
//  * Characters invalid in Windows names (< 0x20 and <>:"/\|?*) become '_'.
//  * Trailing spaces and dots become '_' (Windows strips them, which would change identity).
//  * Reserved device names (CON, PRN, AUX, NUL, COM0-9, LPT0-9, incl. superscript digits), with or
//    without an extension, get '_' appended to the base name ("CON.txt" -> "CON_.txt").
//  * Empty names become "_". Names are capped at 255 UTF-16 units, keeping the extension and never
//    splitting a surrogate pair.
//  * Every entry whose (sanitized) name collides case-insensitively with another entry in the same
//    directory gets " [xxxxxxxx]" before its extension, derived from its stable provider id. Nothing
//    is dropped or overwritten, and the result doesn't depend on provider pagination order.
#pragma once

#include <string>
#include <vector>

namespace hd {

constexpr size_t kMaxComponent = 255;

struct SanitizedName {
    std::wstring name;
    bool changed = false;
};

SanitizedName SanitizeComponent(const std::wstring& raw);
bool IsReservedDeviceName(const std::wstring& name);
// Inserts " [suffix]" before the extension (if any).
std::wstring InsertSuffix(const std::wstring& name, const std::wstring& suffix);

struct NamingInput {
    std::string id;         // stable provider identity (never shown)
    std::wstring rawName;   // provider display name, already UTF-16
};

// Returns one Windows name per input, same order, unique case-insensitively.
std::vector<std::wstring> AssignNames(const std::vector<NamingInput>& inputs);

// "Google Drive - Personal [1a2b3c4d]" / "Dropbox - Team space [..]". No email or display name.
std::wstring AccountDirectoryBaseName(const std::string& provider, const std::string& rootType);

}  // namespace hd
