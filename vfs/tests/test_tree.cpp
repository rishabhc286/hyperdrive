// SPDX-License-Identifier: GPL-3.0-only
#include <nlohmann/json.hpp>

#include "core/fixture.hpp"
#include "core/tree.hpp"
#include "fake_transport.hpp"
#include "testing.hpp"

using namespace hd;

namespace {

const char* kAccount = "11111111-2222-4333-8444-555555555555";

std::string Accounts() {
    return std::string(R"({"generation":1,"items":[{"id":")") + kAccount +
           R"(","provider":"DROPBOX","rootType":"USER","status":"CONNECTED"}]})";
}

std::string Listing(const std::string& items) { return R"({"complete":true,"items":[)" + items + "]}"; }

}  // namespace

TEST(ResolvesRootAccountFolderAndFileFetchingOnlyTheNeededDirectories) {
    FakeTransport t;
    t.handler = [](const std::wstring& path, const std::string& body) {
        if (path == L"/local/v1/vfs/accounts") return FakeTransport::Ok(Accounts());
        auto req = nlohmann::json::parse(body);
        if (!req.contains("directory"))
            return FakeTransport::Ok(Listing(R"({"id":"f1","name":"Docs","kind":"FOLDER","directoryLocator":"/docs"})"));
        return FakeTransport::Ok(Listing(R"({"id":"x","name":"a.txt","kind":"FILE","sizeBytes":3})"));
    };
    AgentClient client(t);
    std::int64_t now = 0;
    MetadataTree tree(client, 123, [&] { return now; });
    auto rootChildren = tree.Children(tree.RootNode());
    CHECK(rootChildren.ok());
    std::wstring account = rootChildren.value->Entries()[0].name;

    Resolved file = tree.Resolve(L"\\" + account + L"\\docs\\A.TXT");
    CHECK(file.lookup == Lookup::Found);
    CHECK(file.node.kind == NodeKind::File);
    CHECK(file.node.size == 3);
    CHECK(file.parent.kind == NodeKind::Folder);
    CHECK(t.paths.size() == 3);  // accounts, account root, /docs: nothing else

    // Cached within the TTL; refetched after it.
    tree.Resolve(L"\\" + account + L"\\Docs\\a.txt");
    CHECK(t.paths.size() == 3);
    now += kSnapshotTtlMs + 1;
    tree.Resolve(L"\\" + account + L"\\Docs\\a.txt");
    CHECK(t.paths.size() == 6);

    CHECK(tree.Resolve(L"\\").lookup == Lookup::Found);
    CHECK(tree.Resolve(L"\\" + account + L"\\missing").lookup == Lookup::NameNotFound);
    CHECK(tree.Resolve(L"\\" + account + L"\\missing\\x").lookup == Lookup::PathNotFound);
    CHECK(tree.Resolve(L"\\" + account + L"\\Docs\\a.txt\\under-a-file").lookup == Lookup::PathNotFound);
    CHECK(tree.Resolve(L"\\Not an account").lookup == Lookup::NameNotFound);
}

TEST(AgentFailuresDuringResolutionAreReportedNotHidden) {
    FakeTransport t;
    t.handler = [](const std::wstring& path, const std::string&) {
        if (path == L"/local/v1/vfs/accounts") return FakeTransport::Ok(Accounts());
        return FakeTransport::Status(401, R"({"code":"authorization_lost"})");
    };
    AgentClient client(t);
    MetadataTree tree(client, 0);
    std::wstring account = tree.Children(tree.RootNode()).value->Entries()[0].name;
    Resolved r = tree.Resolve(L"\\" + account + L"\\x");
    CHECK(r.lookup == Lookup::Failed);
    CHECK(r.fault == Fault::AuthorizationLost);

    FakeTransport down;
    down.handler = [](const std::wstring&, const std::string&) {
        return FakeTransport::Transport(HttpResult::Kind::Timeout);
    };
    AgentClient c2(down);
    MetadataTree t2(c2, 0);
    CHECK(t2.Resolve(L"\\anything").fault == Fault::Timeout);
}

TEST(FixtureDataExercisesNamingAndExclusions) {
    auto transport = MakeFixtureTransport();
    AgentClient client(*transport, true);
    MetadataTree tree(client, 0);
    auto root = tree.Children(tree.RootNode());
    CHECK(root.ok());
    CHECK(root.value->Entries().size() == 1);
    CHECK(root.value->Entries()[0].name.rfind(L"FIXTURE (test data) - Personal [", 0) == 0);
    auto top = tree.Children(root.value->Entries()[0]);
    CHECK(top.ok());
    CHECK(top.value->Excluded() == 2);  // shortcut + native document
    CHECK(top.value->Find(L"CON_") != nullptr);
    CHECK(top.value->Find(L"a_b_c_.txt") != nullptr);
    CHECK(top.value->Find(L"trailing_") != nullptr);
    CHECK(top.value->Find(L"empty.bin")->size == 0);
    CHECK(top.value->Find(L"Report.txt") == nullptr);  // collided: both carry id suffixes
    std::size_t reports = 0;
    for (const auto& e : top.value->Entries()) {
        if (e.name.rfind(L"Report [", 0) == 0 || e.name.rfind(L"report [", 0) == 0) reports++;
    }
    CHECK(reports == 2);
    std::wstring account = root.value->Entries()[0].name;
    CHECK(tree.Resolve(L"\\" + account + L"\\Documents\\Nested\\deep.txt").lookup == Lookup::Found);
    CHECK(tree.Children(*top.value->Find(L"Many files")).value->Entries().size() == 300);
}
