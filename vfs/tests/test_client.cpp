// SPDX-License-Identifier: GPL-3.0-only
#include "core/agent_client.hpp"
#include "fake_transport.hpp"
#include "testing.hpp"

using namespace hd;

namespace {
const char* kAccount = "11111111-2222-4333-8444-555555555555";
}

TEST(ListingIsParsedStrictlyAndCarriesNoSecrets) {
    FakeTransport t;
    t.handler = [](const std::wstring&, const std::string&) {
        return FakeTransport::Ok(
            R"({"accountId":"x","complete":true,"items":[{"id":"id:1","name":"a.txt","kind":"FILE","sizeBytes":5,)"
            R"("modifiedMillis":1000,"directoryLocator":null},{"id":"f","name":"dir","kind":"FOLDER","sizeBytes":null,)"
            R"("modifiedMillis":null,"directoryLocator":"/dir"}]})");
    };
    AgentClient c(t);
    auto r = c.List(kAccount, std::string("/x"));
    CHECK(r.ok());
    CHECK(r.value.size() == 2);
    CHECK(*r.value[0].sizeBytes == 5);
    CHECK(*r.value[1].directoryLocator == "/dir");
    CHECK(t.paths[0] == L"/local/v1/vfs/list");
    CHECK(t.bodies[0] == R"({"accountId":"11111111-2222-4333-8444-555555555555","directory":"/x"})");
    auto root = c.List(kAccount, std::nullopt);
    CHECK(t.bodies[1] == R"({"accountId":"11111111-2222-4333-8444-555555555555"})");
}

TEST(IncompleteOrMalformedListingsAreRejectedNotShown) {
    FakeTransport t;
    std::string body;
    t.handler = [&](const std::wstring&, const std::string&) { return FakeTransport::Ok(body); };
    AgentClient c(t);
    body = R"({"complete":false,"items":[]})";
    CHECK(c.List(kAccount, std::nullopt).fault == Fault::Invalid);
    body = R"({"complete":true,"items":[{"id":"1","name":"a","kind":"EXECUTABLE"}]})";
    CHECK(c.List(kAccount, std::nullopt).fault == Fault::Invalid);
    body = R"({"complete":true,"items":[{"id":"1","name":"a","kind":"FILE","sizeBytes":-4}]})";
    CHECK(c.List(kAccount, std::nullopt).fault == Fault::Invalid);
    body = "not json";
    CHECK(c.List(kAccount, std::nullopt).fault == Fault::Invalid);
    std::string many = R"({"complete":true,"items":[)";
    for (std::size_t i = 0; i <= kMaxListingEntries; i++) {
        many += (i ? "," : "") + std::string(R"({"id":"x)") + std::to_string(i) + R"(","name":"n","kind":"FILE","sizeBytes":1})";
    }
    body = many + "]}";
    CHECK(c.List(kAccount, std::nullopt).fault == Fault::TooLarge);
}

TEST(TransportAndAgentFailuresMapToFaults) {
    CHECK(FaultFor(FakeTransport::Transport(HttpResult::Kind::Timeout)) == Fault::Timeout);
    CHECK(FaultFor(FakeTransport::Transport(HttpResult::Kind::Unreachable)) == Fault::Unavailable);
    CHECK(FaultFor(FakeTransport::Transport(HttpResult::Kind::TooLarge)) == Fault::TooLarge);
    CHECK(FaultFor(FakeTransport::Status(401, R"({"code":"unauthorized"})")) == Fault::AuthorizationLost);
    CHECK(FaultFor(FakeTransport::Status(401, R"({"code":"authorization_lost"})")) == Fault::AuthorizationLost);
    CHECK(FaultFor(FakeTransport::Status(404, R"({"code":"not_found"})")) == Fault::NotFound);
    CHECK(FaultFor(FakeTransport::Status(409, R"({"code":"needs_reconnect"})")) == Fault::NeedsReconnect);
    CHECK(FaultFor(FakeTransport::Status(413, R"({"code":"directory_too_large"})")) == Fault::TooLarge);
    CHECK(FaultFor(FakeTransport::Status(503, R"({"code":"too_busy"})")) == Fault::Busy);
    CHECK(FaultFor(FakeTransport::Status(503, R"({"code":"backend_unavailable"})")) == Fault::Unavailable);
    CHECK(FaultFor(FakeTransport::Status(504, R"({"code":"timeout"})")) == Fault::Timeout);
}

TEST(AccountsRequireKnownProvidersAndFixtureOnlyInFixtureMode) {
    FakeTransport t;
    std::string provider = "DROPBOX";
    t.handler = [&](const std::wstring&, const std::string&) {
        return FakeTransport::Ok(std::string(R"({"generation":3,"items":[{"id":")") + kAccount +
                                 R"(","provider":")" + provider + R"(","rootType":"USER","status":"CONNECTED"}]})");
    };
    AgentClient normal(t);
    CHECK(normal.Accounts().ok());
    provider = "FIXTURE";
    CHECK(normal.Accounts().fault == Fault::Invalid);
    AgentClient fixture(t, true);
    CHECK(fixture.Accounts().ok());
    provider = "EVIL";
    CHECK(fixture.Accounts().fault == Fault::Invalid);
}

TEST(SessionRequiresTheExpectedProtocol) {
    FakeTransport t;
    std::string body = R"({"protocolVersion":1,"generation":7})";
    t.handler = [&](const std::wstring&, const std::string&) { return FakeTransport::Ok(body); };
    AgentClient c(t);
    auto s = c.Session();
    CHECK(s.ok());
    CHECK(s.value == 7);
    body = R"({"protocolVersion":2,"generation":7})";
    CHECK(c.Session().fault == Fault::Invalid);
}
