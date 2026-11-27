// SPDX-License-Identifier: GPL-3.0-only
// Batch 9: content reads through agent read handles (pure logic + client validation, no WinFsp).
#include <nlohmann/json.hpp>

#include <atomic>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

#include "core/agent_client.hpp"
#include "core/content.hpp"
#include "core/status.hpp"
#include "fake_transport.hpp"
#include "testing.hpp"

using namespace hd;
using nlohmann::json;

namespace {

const char* kAccount = "11111111-2222-4333-8444-555555555555";
const std::string kHandle = "hdf_" + std::string(43, 'h');
constexpr std::uint64_t kGiB = 1024ull * 1024ull * 1024ull;

std::uint8_t Gen(std::uint64_t p) { return static_cast<std::uint8_t>((p * 2654435761ull) >> 13); }

// A valid agent answer for (offset, length): bytes written straight into the destination.
BinaryResult Answer(std::uint64_t offset, std::uint32_t length, void* destination, std::uint64_t size) {
    BinaryResult r;
    r.kind = HttpResult::Kind::Ok;
    r.status = 200;
    r.contentType = "application/octet-stream";
    r.contentLength = std::to_string(length);
    r.readOffset = std::to_string(offset);
    r.readLength = std::to_string(length);
    r.eof = offset + length == size ? "true" : "false";
    auto* out = static_cast<std::uint8_t*>(destination);
    for (std::uint32_t i = 0; i < length; i++) out[i] = Gen(offset + i);
    r.bytes = length;
    return r;
}

BinaryResult ErrorAnswer(int status, const std::string& code) {
    BinaryResult r;
    r.kind = HttpResult::Kind::Ok;
    r.status = status;
    r.errorBody = R"({"code":")" + code + "\"}";
    return r;
}

Node FileNode(std::uint64_t size) {
    Node n;
    n.kind = NodeKind::File;
    n.accountId = kAccount;
    n.providerId = "id:abc";
    n.size = size;
    return n;
}

std::string OpenAnswer(std::uint64_t size) {
    return json{{"handle", kHandle}, {"sizeBytes", size}, {"consistency", "dropbox-revision-pinned"},
                {"revisionPinned", true}}
        .dump();
}

// An agent stand-in for ContentService tests: open/read/close/cancel with counters.
struct FakeAgent {
    FakeTransport t;
    std::uint64_t size = 0;
    int opens = 0, reads = 0, closes = 0, cancels = 0;
    HttpResult openAnswer;
    std::function<BinaryResult(std::uint64_t, std::uint32_t, void*)> read;

    explicit FakeAgent(std::uint64_t fileSize) : size(fileSize) {
        openAnswer = FakeTransport::Status(201, OpenAnswer(fileSize));
        read = [this](std::uint64_t off, std::uint32_t len, void* dst) { return Answer(off, len, dst, size); };
        t.handler = [this](const std::wstring& path, const std::string&) {
            if (path == L"/local/v1/vfs/open") {
                opens++;
                return openAnswer;
            }
            if (path == L"/local/v1/vfs/close") closes++;
            if (path == L"/local/v1/vfs/cancel") cancels++;
            return FakeTransport::Ok("{}");
        };
        t.binary = [this](const std::wstring&, const std::string& body, void* dst, std::size_t cap) {
            reads++;
            json j = json::parse(body);
            auto off = j["offset"].get<std::uint64_t>();
            auto len = j["length"].get<std::uint32_t>();
            CHECK(cap == len);  // the destination window is exactly the piece
            return read(off, len, dst);
        };
    }
};

}  // namespace

// --- ServeRead: the pure algorithm --------------------------------------------------------------

TEST(EofVariantsNeverFetchAndReportZeroBytes) {
    int fetches = 0;
    RangeFetcher fetch = [&](std::uint64_t, std::uint32_t, std::uint8_t*) {
        fetches++;
        return Fault::None;
    };
    std::uint8_t buf[16];
    std::uint32_t got = 99;
    CHECK(ServeRead(0, 0, 16, buf, got, fetch) == STATUS_END_OF_FILE && got == 0);    // empty file
    CHECK(ServeRead(10, 10, 16, buf, got, fetch) == STATUS_END_OF_FILE && got == 0);  // exactly at EOF
    CHECK(ServeRead(10, 11, 16, buf, got, fetch) == STATUS_END_OF_FILE && got == 0);  // beyond EOF
    CHECK(ServeRead(10, UINT64_MAX, 16, buf, got, fetch) == STATUS_END_OF_FILE && got == 0);
    CHECK(fetches == 0);
}

TEST(SmallFinalPartialAndExactBytesAreCopied) {
    std::vector<std::uint8_t> buf(100, 0xEE);
    std::uint32_t got = 0;
    RangeFetcher fetch = [](std::uint64_t off, std::uint32_t len, std::uint8_t* dst) {
        for (std::uint32_t i = 0; i < len; i++) dst[i] = Gen(off + i);
        return Fault::None;
    };
    CHECK(ServeRead(1000, 0, 10, buf.data(), got, fetch) == STATUS_SUCCESS && got == 10);
    for (int i = 0; i < 10; i++) CHECK(buf[i] == Gen(static_cast<std::uint64_t>(i)));
    CHECK(buf[10] == 0xEE);  // nothing written past the clamped length
    CHECK(ServeRead(1000, 995, 100, buf.data(), got, fetch) == STATUS_SUCCESS && got == 5);  // final partial read
    for (int i = 0; i < 5; i++) CHECK(buf[i] == Gen(995 + static_cast<std::uint64_t>(i)));
    // A huge request near the end clamps without overflow.
    CHECK(ServeRead(1000, 990, 0xFFFFFFFFu, buf.data(), got, fetch) == STATUS_SUCCESS && got == 10);
}

TEST(LargeRequestsAreSplitInto4MiBPiecesWith64BitOffsets) {
    std::vector<std::pair<std::uint64_t, std::uint32_t>> pieces;
    std::vector<std::uint8_t> buf(10 * 1024 * 1024 + 5);
    RangeFetcher fetch = [&](std::uint64_t off, std::uint32_t len, std::uint8_t* dst) {
        pieces.push_back({off, len});
        for (std::uint32_t i = 0; i < len; i++) dst[i] = Gen(off + i);
        return Fault::None;
    };
    const std::uint64_t size = 5 * kGiB + 777;  // > 4 GiB: never truncated to 32 bits
    const std::uint64_t at = 4 * kGiB + 10;
    std::uint32_t got = 0;
    CHECK(ServeRead(size, at, static_cast<std::uint32_t>(buf.size()), buf.data(), got, fetch) == STATUS_SUCCESS);
    CHECK(got == buf.size());
    CHECK(pieces.size() == 3);
    CHECK(pieces[0].first == at && pieces[0].second == kMaxAgentRead);
    CHECK(pieces[1].first == at + kMaxAgentRead && pieces[1].second == kMaxAgentRead);
    CHECK(pieces[2].first == at + 2ull * kMaxAgentRead && pieces[2].second == 2u * 1024u * 1024u + 5u);
    CHECK(buf[0] == Gen(at) && buf[buf.size() - 1] == Gen(at + buf.size() - 1));
    CHECK(buf[kMaxAgentRead] == Gen(at + kMaxAgentRead));
}

TEST(AFailedPieceFailsTheWholeReadWithZeroBytesAndNoZeroFill) {
    std::vector<std::uint8_t> buf(9 * 1024 * 1024);
    int calls = 0;
    RangeFetcher fetch = [&](std::uint64_t, std::uint32_t, std::uint8_t*) {
        return ++calls == 2 ? Fault::VersionChanged : Fault::None;
    };
    std::uint32_t got = 123;
    Fault failure = Fault::None;
    CHECK(ServeRead(100ull * 1024 * 1024, 0, static_cast<std::uint32_t>(buf.size()), buf.data(), got, fetch, &failure) ==
          STATUS_FILE_INVALID);
    CHECK(got == 0);  // never a partial success
    CHECK(failure == Fault::VersionChanged);
    CHECK(calls == 2);  // stops at the first failure: no retries, no further pieces
}

TEST(ContentFaultsMapToDocumentedNtStatusValues) {
    CHECK(StatusForContentFault(Fault::AuthorizationLost) == STATUS_ACCESS_DENIED);
    CHECK(StatusForContentFault(Fault::NeedsReconnect) == STATUS_ACCESS_DENIED);
    CHECK(StatusForContentFault(Fault::Forbidden) == STATUS_ACCESS_DENIED);
    CHECK(StatusForContentFault(Fault::NotFound) == STATUS_FILE_INVALID);
    CHECK(StatusForContentFault(Fault::VersionChanged) == STATUS_FILE_INVALID);
    CHECK(StatusForContentFault(Fault::Stale) == STATUS_FILE_INVALID);
    CHECK(StatusForContentFault(Fault::Unsupported) == STATUS_NOT_SUPPORTED);
    CHECK(StatusForContentFault(Fault::Unavailable) == STATUS_UNEXPECTED_NETWORK_ERROR);
    CHECK(StatusForContentFault(Fault::Invalid) == STATUS_UNEXPECTED_NETWORK_ERROR);
    CHECK(StatusForContentFault(Fault::Timeout) == STATUS_IO_TIMEOUT);
    CHECK(StatusForContentFault(Fault::Busy) == STATUS_DEVICE_BUSY);
    CHECK(StatusForContentFault(Fault::Cancelled) == STATUS_CANCELLED);
    CHECK(StatusForContentFault(Fault::TooManyOpen) == STATUS_TOO_MANY_OPENED_FILES);
    CHECK(StatusForContentFault(Fault::Unmounting) == STATUS_VOLUME_DISMOUNTED);
    CHECK(!NT_SUCCESS(StatusForContentFault(Fault::Busy)));  // a warning-class status is still a failure
}

// --- Binary answer validation --------------------------------------------------------------------

TEST(BinaryAnswersAreValidatedStrictly) {
    std::uint8_t buf[100];
    BinaryResult ok = Answer(5000, 100, buf, 1ull << 40);
    CHECK(ValidateRange(ok, 5000, 100).fault == Fault::None);
    CHECK(!ValidateRange(ok, 5000, 100).eof);

    BinaryResult b = ok;
    b.bytes = 99;  // short / truncated body
    CHECK(ValidateRange(b, 5000, 100).fault == Fault::Invalid);
    b = ok;
    b.contentLength = "101";
    CHECK(ValidateRange(b, 5000, 100).fault == Fault::Invalid);
    b = ok;
    b.readLength = "50";
    CHECK(ValidateRange(b, 5000, 100).fault == Fault::Invalid);
    b = ok;
    b.readOffset = "5001";  // another range than asked for
    CHECK(ValidateRange(b, 5000, 100).fault == Fault::Invalid);
    b = ok;
    b.readOffset = "+5000";
    CHECK(ValidateRange(b, 5000, 100).fault == Fault::Invalid);
    b = ok;
    b.readOffset = "99999999999999999999999";  // overflow
    CHECK(ValidateRange(b, 5000, 100).fault == Fault::Invalid);
    b = ok;
    b.contentType = "application/json";
    CHECK(ValidateRange(b, 5000, 100).fault == Fault::Invalid);
    b = ok;
    b.eof.reset();
    CHECK(ValidateRange(b, 5000, 100).fault == Fault::Invalid);
    b = ok;
    b.kind = HttpResult::Kind::TooLarge;  // oversized body: rejected, not truncated
    CHECK(ValidateRange(b, 5000, 100).fault == Fault::Invalid);
    b.kind = HttpResult::Kind::Timeout;
    CHECK(ValidateRange(b, 5000, 100).fault == Fault::Timeout);
    b.kind = HttpResult::Kind::Unreachable;
    CHECK(ValidateRange(b, 5000, 100).fault == Fault::Unavailable);

    CHECK(ValidateRange(ErrorAnswer(410, "stale_handle"), 0, 1).fault == Fault::Stale);
    CHECK(ValidateRange(ErrorAnswer(409, "file_changed"), 0, 1).fault == Fault::VersionChanged);
    CHECK(ValidateRange(ErrorAnswer(409, "cancelled"), 0, 1).fault == Fault::Cancelled);
    CHECK(ValidateRange(ErrorAnswer(409, "needs_reconnect"), 0, 1).fault == Fault::NeedsReconnect);
    CHECK(ValidateRange(ErrorAnswer(409, "content_permission_missing"), 0, 1).fault == Fault::Forbidden);
    CHECK(ValidateRange(ErrorAnswer(409, "credential_rejected"), 0, 1).fault == Fault::Forbidden);
    CHECK(ValidateRange(ErrorAnswer(401, "authorization_expired"), 0, 1).fault == Fault::AuthorizationLost);
    CHECK(ValidateRange(ErrorAnswer(404, "not_found"), 0, 1).fault == Fault::NotFound);
    CHECK(ValidateRange(ErrorAnswer(415, "unsupported_file"), 0, 1).fault == Fault::Unsupported);
    CHECK(ValidateRange(ErrorAnswer(429, "rate_limited"), 0, 1).fault == Fault::Busy);
    CHECK(ValidateRange(ErrorAnswer(503, "too_busy"), 0, 1).fault == Fault::Busy);
    CHECK(ValidateRange(ErrorAnswer(503, "too_many_open_files"), 0, 1).fault == Fault::TooManyOpen);
    CHECK(ValidateRange(ErrorAnswer(503, "backend_unavailable"), 0, 1).fault == Fault::Unavailable);
    CHECK(ValidateRange(ErrorAnswer(504, "timeout"), 0, 1).fault == Fault::Timeout);
}

TEST(OpenReadIsValidatedAndCarriesNoCredentials) {
    FakeTransport t;
    HttpResult answer = FakeTransport::Status(201, OpenAnswer(5 * kGiB));
    t.handler = [&](const std::wstring&, const std::string&) { return answer; };
    AgentClient c(t);
    auto opened = c.OpenRead(kAccount, "id:abc", 5 * kGiB);
    CHECK(opened.ok());
    CHECK(opened.value.handle == kHandle && opened.value.size == 5 * kGiB);
    CHECK(t.paths[0] == L"/local/v1/vfs/open");
    CHECK(t.bodies[0] == R"({"accountId":"11111111-2222-4333-8444-555555555555","expectedSize":5368709120,"fileId":"id:abc"})");

    answer = FakeTransport::Status(201, OpenAnswer(5 * kGiB + 1));  // the agent pinned another size
    CHECK(c.OpenRead(kAccount, "id:abc", 5 * kGiB).fault == Fault::Invalid);
    answer = FakeTransport::Status(201, R"({"handle":"sl.provider-token","sizeBytes":5,"consistency":"google-unpinned"})");
    CHECK(c.OpenRead(kAccount, "id:abc", 5).fault == Fault::Invalid);  // only opaque hdf_ handles
    answer = FakeTransport::Status(201, json{{"handle", kHandle}, {"sizeBytes", 5}, {"consistency", "whatever"}}.dump());
    CHECK(c.OpenRead(kAccount, "id:abc", 5).fault == Fault::Invalid);
    answer = FakeTransport::Status(415, R"({"code":"unsupported_file"})");
    CHECK(c.OpenRead(kAccount, "id:abc", 5).fault == Fault::Unsupported);
    answer = FakeTransport::Status(409, R"({"code":"file_changed"})");
    CHECK(c.OpenRead(kAccount, "id:abc", 5).fault == Fault::VersionChanged);

    FakeTransport none;
    none.handler = [](const std::wstring&, const std::string&) { return FakeTransport::Ok("{}"); };
    AgentClient fixture(none, true);
    CHECK(fixture.OpenRead(kAccount, "fx:r1", 5).fault == Fault::Unsupported);  // fixture data has no content
    CHECK(none.paths.empty());
}

TEST(RangeReadsAskForExactlyThePieceAndRefuseOverflow) {
    FakeAgent a(1000);
    AgentClient c(a.t);
    std::uint8_t buf[64];
    CHECK(c.ReadRange(kHandle, 10, 64, buf).fault == Fault::None);
    CHECK(a.t.bodies.back() == R"({"handle":")" + kHandle + R"(","length":64,"offset":10})");
    CHECK(a.t.receiveTimeouts.back() == kContentReceiveMs);
    CHECK(buf[0] == Gen(10) && buf[63] == Gen(73));
    CHECK(c.ReadRange(kHandle, UINT64_MAX - 3, 64, buf).fault == Fault::Invalid);  // overflow, never sent
    CHECK(c.ReadRange(kHandle, 0, kMaxAgentRead + 1, buf).fault == Fault::Invalid);
    CHECK(c.ReadRange("not-a-handle", 0, 1, buf).fault == Fault::Invalid);
    CHECK(a.reads == 1);
}

// --- ContentService: lifecycle, stickiness, unmount ----------------------------------------------

TEST(TheAgentHandleIsOpenedLazilyOnceAndClosedInClose) {
    FakeAgent a(20 * 1024 * 1024);
    AgentClient c(a.t);
    ContentService s(c);
    Node n = FileNode(a.size);
    {
        ContentService::OpenFile f;
        std::vector<std::uint8_t> buf(9 * 1024 * 1024);
        std::uint32_t got = 0;
        CHECK(a.opens == 0);  // opening the file in Windows makes no agent call
        CHECK(s.Read(f, n, a.size, 10, buf.data(), got) == STATUS_END_OF_FILE && a.opens == 0);
        CHECK(s.Read(f, n, 100, static_cast<std::uint32_t>(buf.size()), buf.data(), got) == STATUS_SUCCESS);
        CHECK(got == buf.size() && a.opens == 1 && a.reads == 3);
        CHECK(buf[5 * 1024 * 1024] == Gen(100 + 5 * 1024 * 1024));
        CHECK(s.Read(f, n, 0, 10, buf.data(), got) == STATUS_SUCCESS && a.opens == 1);
        s.Close(f);
        CHECK(a.closes == 1);
        const std::string closeBody = std::string(R"({"handle":")") + kHandle + std::string(R"("})");
        CHECK(a.t.bodies.back() == closeBody);
    }
    // Zero-byte files never open an agent context.
    ContentService::OpenFile empty;
    std::uint8_t b[4];
    std::uint32_t got = 7;
    CHECK(s.Read(empty, FileNode(0), 0, 4, b, got) == STATUS_END_OF_FILE && got == 0);
    s.Close(empty);
    CHECK(a.opens == 1 && a.closes == 1);
    // Nothing the VFS sends contains anything but ids, sizes, offsets and the opaque handle.
    for (const auto& body : a.t.bodies) {
        CHECK(body.find("token") == std::string::npos && body.find("Bearer") == std::string::npos);
    }
}

TEST(PermanentFailuresAreRememberedSoProbesDontStorm) {
    std::int64_t now = 1000;
    FakeAgent a(100);
    a.openAnswer = FakeTransport::Status(415, R"({"code":"unsupported_file"})");
    AgentClient c(a.t);
    ContentService s(c, [&] { return now; });
    Node n = FileNode(100);
    std::uint8_t buf[10];
    std::uint32_t got = 0;
    ContentService::OpenFile f1;
    CHECK(s.Read(f1, n, 0, 10, buf, got) == STATUS_NOT_SUPPORTED);
    CHECK(s.Read(f1, n, 0, 10, buf, got) == STATUS_NOT_SUPPORTED);
    ContentService::OpenFile f2;  // Explorer opens it again: answered from the short negative cache
    CHECK(s.Read(f2, n, 0, 10, buf, got) == STATUS_NOT_SUPPORTED);
    CHECK(a.opens == 1);
    now += kNegativeOpenTtlMs + 1;
    ContentService::OpenFile f3;
    CHECK(s.Read(f3, n, 0, 10, buf, got) == STATUS_NOT_SUPPORTED && a.opens == 2);
}

TEST(TransientOpenFailuresBackOffThenRetry) {
    std::int64_t now = 1000;
    FakeAgent a(100);
    a.openAnswer = FakeTransport::Status(503, R"({"code":"backend_unavailable"})");
    AgentClient c(a.t);
    ContentService s(c, [&] { return now; });
    Node n = FileNode(100);
    std::uint8_t buf[10];
    std::uint32_t got = 0;
    ContentService::OpenFile f;
    CHECK(s.Read(f, n, 0, 10, buf, got) == STATUS_UNEXPECTED_NETWORK_ERROR);
    CHECK(s.Read(f, n, 0, 10, buf, got) == STATUS_UNEXPECTED_NETWORK_ERROR && a.opens == 1);
    now += kTransientOpenRetryMs + 1;
    a.openAnswer = FakeTransport::Status(201, OpenAnswer(100));
    CHECK(s.Read(f, n, 0, 10, buf, got) == STATUS_SUCCESS && got == 10 && a.opens == 2);
}

TEST(AVersionChangeMidwayFailsAndStaysFailedForThatOpen) {
    FakeAgent a(10 * 1024 * 1024);
    int calls = 0;
    a.read = [&](std::uint64_t off, std::uint32_t len, void* dst) {
        return ++calls == 2 ? ErrorAnswer(409, "file_changed") : Answer(off, len, dst, a.size);
    };
    AgentClient c(a.t);
    ContentService s(c);
    Node n = FileNode(a.size);
    ContentService::OpenFile f;
    std::vector<std::uint8_t> buf(8 * 1024 * 1024);
    std::uint32_t got = 5;
    CHECK(s.Read(f, n, 0, static_cast<std::uint32_t>(buf.size()), buf.data(), got) == STATUS_FILE_INVALID && got == 0);
    CHECK(s.Read(f, n, 0, 10, buf.data(), got) == STATUS_FILE_INVALID && got == 0);
    CHECK(a.reads == 2);  // no further agent reads for this open
}

TEST(TruncatedTimeoutCancelledAndEofMismatchAnswersAreFailures) {
    FakeAgent a(1000);
    AgentClient c(a.t);
    ContentService s(c);
    Node n = FileNode(1000);
    std::uint8_t buf[100];
    std::uint32_t got = 0;
    a.read = [&](std::uint64_t off, std::uint32_t len, void* dst) {
        BinaryResult r = Answer(off, len, dst, a.size);
        r.bytes = len / 2;  // the agent connection dropped mid-body
        return r;
    };
    ContentService::OpenFile f1;
    CHECK(s.Read(f1, n, 0, 100, buf, got) == STATUS_UNEXPECTED_NETWORK_ERROR && got == 0);
    a.read = [&](std::uint64_t, std::uint32_t, void*) {
        BinaryResult r;
        r.kind = HttpResult::Kind::Timeout;
        return r;
    };
    ContentService::OpenFile f2;
    CHECK(s.Read(f2, n, 0, 100, buf, got) == STATUS_IO_TIMEOUT && got == 0);
    a.read = [&](std::uint64_t, std::uint32_t, void*) { return ErrorAnswer(409, "cancelled"); };
    CHECK(s.Read(f2, n, 0, 100, buf, got) == STATUS_CANCELLED && got == 0);
    a.read = [&](std::uint64_t off, std::uint32_t len, void* dst) {
        BinaryResult r = Answer(off, len, dst, a.size);
        r.eof = "true";  // claims EOF in the middle of the file
        return r;
    };
    ContentService::OpenFile f3;
    CHECK(s.Read(f3, n, 0, 100, buf, got) == STATUS_UNEXPECTED_NETWORK_ERROR && got == 0);
}

TEST(UnmountFailsNewReadsCancelsInFlightOnesAndSkipsNetworkCloses) {
    FakeAgent a(1000);
    AgentClient c(a.t);
    ContentService s(c);
    Node n = FileNode(1000);
    ContentService::OpenFile f;
    std::uint8_t buf[10];
    std::uint32_t got = 0;
    CHECK(s.Read(f, n, 0, 10, buf, got) == STATUS_SUCCESS);
    s.BeginUnmount();
    s.BeginUnmount();  // idempotent
    CHECK(a.cancels == 1);
    CHECK(s.Read(f, n, 0, 10, buf, got) == STATUS_VOLUME_DISMOUNTED && got == 0);
    int readsBefore = a.reads;
    s.Close(f);
    CHECK(a.closes == 0 && a.reads == readsBefore);  // the agent drops every context on revoke
}

TEST(ConcurrentReadersShareABoundedNumberOfAgentRequests) {
    struct Counting : AgentTransport {
        std::atomic<int> active{0}, peak{0}, opens{0};
        HttpResult Post(const std::wstring& path, const std::string&) override {
            if (path == L"/local/v1/vfs/open") opens++;
            return path == L"/local/v1/vfs/open" ? FakeTransport::Status(201, OpenAnswer(64 * 1024 * 1024))
                                                 : FakeTransport::Ok("{}");
        }
        BinaryResult PostForBinary(const std::wstring&, const std::string& body, void* dst, std::size_t, int) override {
            int now = ++active;
            int p = peak.load();
            while (now > p && !peak.compare_exchange_weak(p, now)) {
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            json j = json::parse(body);
            BinaryResult r = Answer(j["offset"].get<std::uint64_t>(), j["length"].get<std::uint32_t>(), dst, 64 * 1024 * 1024);
            --active;
            return r;
        }
    } t;
    AgentClient c(t);
    ContentService s(c);
    Node n = FileNode(64 * 1024 * 1024);
    std::atomic<int> ok{0};
    std::vector<std::thread> threads;
    for (int i = 0; i < 12; i++) {
        threads.emplace_back([&, i] {
            ContentService::OpenFile f;
            std::vector<std::uint8_t> buf(1024 * 1024);
            for (int k = 0; k < 4; k++) {
                std::uint32_t got = 0;
                std::uint64_t at = static_cast<std::uint64_t>(i) * 4 * 1024 * 1024 + static_cast<std::uint64_t>(k) * 1024 * 1024;
                if (s.Read(f, n, at, 1024 * 1024, buf.data(), got) == STATUS_SUCCESS && got == buf.size() &&
                    buf[777] == Gen(at + 777)) {
                    ok++;
                }
            }
            s.Close(f);
        });
    }
    for (auto& th : threads) th.join();
    CHECK(ok == 48);
    CHECK(t.peak.load() <= kMaxContentRequests);
    CHECK(t.opens == 12);  // one agent context per Windows open
}
