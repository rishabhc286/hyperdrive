// SPDX-License-Identifier: GPL-3.0-only
#include <algorithm>
#include <set>

#include "core/naming.hpp"
#include "core/text.hpp"
#include "testing.hpp"

using namespace hd;

TEST(InvalidCharactersAndTrailingSpacesOrDotsAreReplaced) {
    CHECK(SanitizeComponent(L"a:b?c*.txt").name == L"a_b_c_.txt");
    CHECK(SanitizeComponent(L"x<y>z|\"w\"").name == L"x_y_z__w_");
    CHECK(SanitizeComponent(L"tab\there").name == L"tab_here");
    CHECK(SanitizeComponent(L"trailing.").name == L"trailing_");
    CHECK(SanitizeComponent(L"spaces  ").name == L"spaces__");
    CHECK(SanitizeComponent(L"..").name == L"__");
    CHECK(SanitizeComponent(L"").name == L"_");
    CHECK(SanitizeComponent(L"plain.txt").changed == false);
    CHECK(SanitizeComponent(L"a/b").changed == true);
}

TEST(ReservedDeviceNamesAreEscapedWithOrWithoutExtension) {
    CHECK(SanitizeComponent(L"CON").name == L"CON_");
    CHECK(SanitizeComponent(L"con.txt").name == L"con_.txt");
    CHECK(SanitizeComponent(L"Lpt1.log").name == L"Lpt1_.log");
    CHECK(SanitizeComponent(L"COM¹").name == L"COM¹_");
    CHECK(SanitizeComponent(L"NUL .txt").name == L"NUL _.txt");
    CHECK(!IsReservedDeviceName(L"NUL _.txt"));
    CHECK(SanitizeComponent(L"CONSOLE").name == L"CONSOLE");
    CHECK(SanitizeComponent(L"COM10").name == L"COM10");
    CHECK(IsReservedDeviceName(L"aux.tar.gz"));
}

TEST(LongNamesAreCappedKeepingTheExtensionAndSurrogatePairs) {
    std::wstring longName(400, L'a');
    auto out = SanitizeComponent(longName + L".pdf");
    CHECK(out.name.size() == kMaxComponent);
    CHECK(out.name.substr(out.name.size() - 4) == L".pdf");
    std::wstring emoji;
    for (int i = 0; i < 200; i++) emoji += L"\U0001F600";  // surrogate pairs
    auto e = SanitizeComponent(emoji);
    CHECK(e.name.size() <= kMaxComponent);
    wchar_t last = e.name.back();
    CHECK(!(last >= 0xD800 && last <= 0xDBFF));
}

TEST(CaseInsensitiveCollisionsGetStableIdSuffixesAndNothingIsDropped) {
    std::vector<NamingInput> in = {{"id:1", L"Report.txt"}, {"id:2", L"report.TXT"}, {"id:3", L"other.txt"}};
    auto names = AssignNames(in);
    CHECK(names.size() == 3);
    CHECK(names[0] == L"Report [" + HexSuffix("id:1", 8) + L"].txt");
    CHECK(names[1] == L"report [" + HexSuffix("id:2", 8) + L"].TXT");
    CHECK(names[2] == L"other.txt");
    std::set<std::wstring> keys;
    for (auto& n : names) keys.insert(UpcaseKey(n));
    CHECK(keys.size() == 3);
}

TEST(DuplicateProviderNamesStayDistinctAndIndependentOfPaginationOrder) {
    std::vector<NamingInput> a = {{"id:a", L"Same"}, {"id:b", L"Same"}, {"id:c", L"Same"}, {"id:d", L"x"}};
    std::vector<NamingInput> b = {a[3], a[2], a[0], a[1]};
    auto na = AssignNames(a);
    auto nb = AssignNames(b);
    CHECK(na[0] == nb[2]);
    CHECK(na[1] == nb[3]);
    CHECK(na[2] == nb[1]);
    CHECK(na[3] == nb[0]);
    std::set<std::wstring> unique(na.begin(), na.end());
    CHECK(unique.size() == 4);
}

TEST(SanitizedNamesThatCollideWithRealNamesAreBothSuffixed) {
    auto names = AssignNames({{"id:1", L"a:b"}, {"id:2", L"a_b"}});
    CHECK(names[0] != names[1]);
    CHECK(names[0].find(L"[") != std::wstring::npos);
    CHECK(names[1].find(L"[") != std::wstring::npos);
}

TEST(UnicodeNamesSurviveUnchanged) {
    std::wstring name = Utf8ToWide("R\xC3\xA9sum\xC3\xA9 \xE6\x97\xA5\xE6\x9C\xAC.txt");
    CHECK(name == L"Résumé 日本.txt");
    CHECK(SanitizeComponent(name).name == name);
    CHECK(WideToUtf8(name) == "R\xC3\xA9sum\xC3\xA9 \xE6\x97\xA5\xE6\x9C\xAC.txt");
    // Accented names differing only in case collide case-insensitively.
    auto names = AssignNames({{"id:1", L"Été"}, {"id:2", L"éTÉ"}});
    CHECK(names[0] != names[1]);
}

TEST(AccountDirectoryNamesAvoidPersonalDataAndAreStable) {
    CHECK(AccountDirectoryBaseName("GOOGLE_DRIVE", "USER") == L"Google Drive - Personal");
    CHECK(AccountDirectoryBaseName("DROPBOX", "TEAM") == L"Dropbox - Team space");
    CHECK(HexSuffix("11111111-2222-4333-8444-555555555555", 8) == HexSuffix("11111111-2222-4333-8444-555555555555", 8));
    CHECK(HexSuffix("a", 8) != HexSuffix("b", 8));
    CHECK(HexSuffix("a", 8).size() == 8);
}

TEST(SuffixInsertionKeepsTheComponentLimit) {
    std::wstring longName(260, L'b');
    std::wstring out = InsertSuffix(longName + L".txt", L"12345678");
    CHECK(out.size() <= kMaxComponent);
    CHECK(out.find(L" [12345678].txt") != std::wstring::npos);
    CHECK(InsertSuffix(L".gitignore", L"ab") == L".gitignore [ab]");
}
