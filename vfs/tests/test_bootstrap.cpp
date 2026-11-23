// SPDX-License-Identifier: GPL-3.0-only
#include "core/bootstrap.hpp"
#include "testing.hpp"

using namespace hd;

namespace {
const std::string kCap = "hdv_" + std::string(43, 'A');
}

TEST(AValidBootstrapIsAccepted) {
    Bootstrap b;
    std::string err;
    CHECK(ParseBootstrap(R"({"protocolVersion":1,"agentPort":51234,"capability":")" + kCap +
                             R"(","mount":{"driveLetter":"H"}})",
                         b, err));
    CHECK(b.agentPort == 51234);
    CHECK(b.driveLetter == L'H');
    CHECK(b.capability == kCap);
    CHECK(err.empty());
}

TEST(BootstrapRejectsExtraFieldsBadValuesAndOversizedInput) {
    Bootstrap b;
    std::string err;
    auto base = [](const std::string& port, const std::string& cap, const std::string& letter) {
        return R"({"protocolVersion":1,"agentPort":)" + port + R"(,"capability":")" + cap +
               R"(","mount":{"driveLetter":")" + letter + R"("}})";
    };
    CHECK(!ParseBootstrap(base("80", kCap, "H"), b, err));
    CHECK(!ParseBootstrap(base("70000", kCap, "H"), b, err));
    CHECK(!ParseBootstrap(base("51234", "hda_" + std::string(43, 'A'), "H"), b, err));
    CHECK(!ParseBootstrap(base("51234", kCap, "C"), b, err));
    CHECK(err == "invalid_drive_letter");
    CHECK(!ParseBootstrap(base("51234", kCap, "HH"), b, err));
    CHECK(!ParseBootstrap(R"({"protocolVersion":2,"agentPort":51234,"capability":")" + kCap +
                              R"(","mount":{"driveLetter":"H"}})",
                          b, err));
    CHECK(err == "protocol_mismatch");
    CHECK(!ParseBootstrap(R"({"protocolVersion":1,"agentPort":51234,"capability":")" + kCap +
                              R"(","mount":{"driveLetter":"H"},"url":"http://example.com"})",
                          b, err));
    CHECK(!ParseBootstrap(R"({"protocolVersion":1,"agentPort":51234,"capability":")" + kCap +
                              R"(","mount":{"driveLetter":"H","path":"C:\\"}})",
                          b, err));
    CHECK(!ParseBootstrap(std::string(kMaxBootstrapBytes + 1, ' '), b, err));
    CHECK(err == "bootstrap_too_large");
    CHECK(!ParseBootstrap("not json", b, err));
}

TEST(OnlyDToZAreAllowedDriveLetters) {
    CHECK(!IsAllowedDriveLetter(L'A'));
    CHECK(!IsAllowedDriveLetter(L'C'));
    CHECK(IsAllowedDriveLetter(L'D'));
    CHECK(IsAllowedDriveLetter(L'H'));
    CHECK(IsAllowedDriveLetter(L'Z'));
    CHECK(!IsAllowedDriveLetter(L'h'));
}
