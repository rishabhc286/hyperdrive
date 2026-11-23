// SPDX-License-Identifier: GPL-3.0-only
#include <vector>

#include "core/snapshot.hpp"
#include "core/status.hpp"
#include "testing.hpp"

using namespace hd;

namespace {

RemoteEntry File(const std::string& id, const std::string& name, std::uint64_t size) {
    RemoteEntry e;
    e.id = id;
    e.name = name;
    e.kind = "FILE";
    e.sizeBytes = size;
    e.modifiedMillis = 0;
    return e;
}

RemoteEntry Folder(const std::string& id, const std::string& name) {
    RemoteEntry e;
    e.id = id;
    e.name = name;
    e.kind = "FOLDER";
    e.directoryLocator = id;
    return e;
}

// Collects names, accepting at most `capacity` entries per call (a tiny enumeration buffer).
std::vector<std::wstring> Drain(const DirectorySnapshot& s, const Node& self, bool isRoot, std::size_t capacity) {
    std::vector<std::wstring> all;
    std::wstring marker;
    bool haveMarker = false;
    for (int guard = 0; guard < 10000; guard++) {
        std::size_t taken = 0;
        bool done = EnumerateDirectory(s, self, self, isRoot, haveMarker ? marker.c_str() : nullptr,
                                       [&](const std::wstring& name, const Node&) {
                                           if (taken == capacity) return false;
                                           taken++;
                                           all.push_back(name);
                                           marker = name;
                                           return true;
                                       });
        haveMarker = true;
        if (done) return all;
    }
    throw hdtest::Failure{"enumeration did not terminate"};
}

}  // namespace

TEST(SnapshotsAreSortedCaseInsensitivelyAndFindIgnoresCase) {
    auto s = BuildFolderSnapshot("acc", {File("1", "b.txt", 1), File("2", "A.txt", 2), Folder("3", "c")});
    CHECK(s->Entries().size() == 3);
    CHECK(s->Entries()[0].name == L"A.txt");
    CHECK(s->Entries()[1].name == L"b.txt");
    CHECK(s->Find(L"B.TXT") != nullptr);
    CHECK(s->Find(L"B.TXT")->providerId == "1");
    CHECK(s->Find(L"missing") == nullptr);
}

TEST(UnsupportedKindsAndUnknownSizesAreExcludedAndCounted) {
    RemoteEntry shortcut;
    shortcut.id = "s";
    shortcut.name = "link";
    shortcut.kind = "SHORTCUT";
    RemoteEntry doc;
    doc.id = "d";
    doc.name = "Sheet";
    doc.kind = "PROVIDER_DOCUMENT";
    RemoteEntry noSize = File("n", "nosize", 0);
    noSize.sizeBytes.reset();
    auto s = BuildFolderSnapshot("acc", {File("1", "ok.bin", 5), shortcut, doc, noSize});
    CHECK(s->Entries().size() == 1);
    CHECK(s->Excluded() == 3);
}

TEST(MetadataConversionUsesRealValuesAndMarksUnknownTimes) {
    RemoteEntry f = File("1", "f", 1234);
    f.modifiedMillis = 0;  // 1970-01-01
    RemoteEntry g = File("2", "g", 0);
    g.modifiedMillis.reset();
    auto s = BuildFolderSnapshot("acc", {f, g, Folder("3", "dir")});
    CHECK(s->Find(L"f")->size == 1234);
    CHECK(s->Find(L"f")->lastWrite == 116444736000000000LL);
    CHECK(s->Find(L"g")->lastWrite == 0);
    CHECK(s->Find(L"g")->size == 0);  // a real zero-byte file
    CHECK(s->Find(L"dir")->size == 0);
    CHECK(s->Find(L"dir")->IsDirectory());
    CHECK(s->Find(L"f")->indexNumber == IndexNumberFor("acc", "1"));
    CHECK(MillisToFiletime(-20000000000000LL) == 0);
}

TEST(EnumerationEmitsDotsThenEntriesAndResumesAfterTheMarker) {
    std::vector<RemoteEntry> entries;
    for (int i = 0; i < 25; i++) entries.push_back(File("id" + std::to_string(i), "f" + std::to_string(100 + i), 1));
    auto s = BuildFolderSnapshot("acc", entries);
    Node self;
    self.kind = NodeKind::Folder;
    for (std::size_t cap : {1u, 2u, 3u, 7u, 100u}) {
        auto names = Drain(*s, self, false, cap);
        CHECK(names.size() == 27);
        CHECK(names[0] == L".");
        CHECK(names[1] == L"..");
        CHECK(names[2] == L"f100");
        CHECK(names.back() == L"f124");
    }
    auto rootNames = Drain(*s, self, true, 4);
    CHECK(rootNames.size() == 25);
    CHECK(rootNames[0] == L"f100");
}

TEST(EnumerationOfAnEmptyDirectoryEndsImmediately) {
    auto s = BuildFolderSnapshot("acc", {});
    Node self;
    self.kind = NodeKind::Folder;
    auto names = Drain(*s, self, false, 1);
    CHECK(names.size() == 2);
    auto rootNames = Drain(*s, self, true, 1);
    CHECK(rootNames.empty());
}

TEST(MarkersBetweenEntriesResumeAtTheNextGreaterName) {
    auto s = BuildFolderSnapshot("acc", {File("1", "a", 1), File("2", "c", 1), File("3", "e", 1)});
    CHECK(s->FirstAfter(L"b") == 1);
    CHECK(s->FirstAfter(L"C") == 2);
    CHECK(s->FirstAfter(L"z") == 3);
}

TEST(RootSnapshotListsAccountsWithStableNames) {
    auto s = BuildRootSnapshot({{"11111111-2222-4333-8444-555555555555", "GOOGLE_DRIVE", "USER", "CONNECTED"},
                                {"22222222-2222-4333-8444-555555555555", "DROPBOX", "TEAM", "CONNECTED"}});
    CHECK(s->Entries().size() == 2);
    CHECK(s->Entries()[0].name.rfind(L"Dropbox - Team space [", 0) == 0);
    CHECK(s->Entries()[1].name.rfind(L"Google Drive - Personal [", 0) == 0);
    CHECK(s->Entries()[0].providerId.empty());  // virtual node: never a provider id
    CHECK(s->Entries()[0].kind == NodeKind::Account);
}

TEST(ReadsFailHonestlyAndMutationsAreWriteProtected) {
    CHECK(StatusForRead(0, 0) == STATUS_END_OF_FILE);
    CHECK(StatusForRead(10, 10) == STATUS_END_OF_FILE);
    CHECK(StatusForRead(10, 0) == STATUS_NOT_SUPPORTED);
    CHECK(StatusForRead(10, 9) == STATUS_NOT_SUPPORTED);
    CHECK(kReadOnlyStatus == STATUS_MEDIA_WRITE_PROTECTED);
    CHECK((kWriteAccessMask & FILE_WRITE_DATA) != 0);
    CHECK((kWriteAccessMask & DELETE) != 0);
    CHECK((kWriteAccessMask & FILE_READ_DATA) == 0);
    CHECK(StatusForFault(Fault::AuthorizationLost) == STATUS_ACCESS_DENIED);
    CHECK(StatusForFault(Fault::Timeout) == STATUS_IO_TIMEOUT);
    CHECK(StatusForFault(Fault::TooLarge) == STATUS_INSUFFICIENT_RESOURCES);
    CHECK(StatusForFault(Fault::NotFound) == STATUS_OBJECT_PATH_NOT_FOUND);
}
