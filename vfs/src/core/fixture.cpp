// SPDX-License-Identifier: GPL-3.0-only
#include "fixture.hpp"

#include <nlohmann/json.hpp>
#include <string>

namespace hd {
namespace {

using nlohmann::json;

constexpr const char* kAccount = "00000000-0000-4000-8000-00000000f1f1";

json File(const std::string& id, const std::string& name, long long size) {
    return {{"id", id}, {"name", name}, {"kind", "FILE"}, {"sizeBytes", size}, {"modifiedMillis", 1767225600000LL},
            {"directoryLocator", nullptr}};
}

json Folder(const std::string& id, const std::string& name, const std::string& locator) {
    return {{"id", id}, {"name", name}, {"kind", "FOLDER"}, {"sizeBytes", nullptr},
            {"modifiedMillis", 1767225600000LL}, {"directoryLocator", locator}};
}

class FixtureTransport final : public AgentTransport {
  public:
    HttpResult Post(const std::wstring& path, const std::string& body) override {
        HttpResult r;
        r.kind = HttpResult::Kind::Ok;
        r.status = 200;
        if (path == L"/local/v1/vfs/session") {
            r.body = json{{"protocolVersion", 1}, {"generation", 0}}.dump();
        } else if (path == L"/local/v1/vfs/accounts") {
            r.body = json{{"generation", 0},
                          {"items", json::array({{{"id", kAccount}, {"provider", "FIXTURE"}, {"rootType", "USER"},
                                                   {"status", "CONNECTED"}}})}}
                         .dump();
        } else if (path == L"/local/v1/vfs/list") {
            json req = json::parse(body, nullptr, false);
            std::string dir = req.contains("directory") && req["directory"].is_string() ? req["directory"].get<std::string>()
                                                                                        : "";
            json items = json::array();
            if (dir.empty()) {
                items.push_back(Folder("fx:docs", "Documents", "fx:docs"));
                items.push_back(Folder("fx:many", "Many files", "fx:many"));
                items.push_back(File("fx:r1", "Report.txt", 1234));
                items.push_back(File("fx:r2", "report.TXT", 99));  // case-insensitive collision
                items.push_back(File("fx:con", "CON", 10));         // reserved device name
                items.push_back(File("fx:bad", "a:b?c*.txt", 5));   // invalid characters
                items.push_back(File("fx:dot", "trailing.", 7));    // trailing dot
                items.push_back(File("fx:uni", "R\xC3\xA9sum\xC3\xA9 \xE6\x97\xA5\xE6\x9C\xAC.txt", 42));
                items.push_back(File("fx:empty", "empty.bin", 0));
                items.push_back({{"id", "fx:shortcut"}, {"name", "A shortcut"}, {"kind", "SHORTCUT"}});
                items.push_back({{"id", "fx:doc"}, {"name", "A native document"}, {"kind", "PROVIDER_DOCUMENT"}});
            } else if (dir == "fx:docs") {
                items.push_back(Folder("fx:nested", "Nested", "fx:nested"));
                items.push_back(File("fx:n1", "notes.md", 2048));
            } else if (dir == "fx:nested") {
                items.push_back(File("fx:deep", "deep.txt", 3));
            } else if (dir == "fx:many") {
                for (int i = 0; i < 300; i++) items.push_back(File("fx:m" + std::to_string(i), "file-" + std::to_string(i) + ".dat", i));
            } else {
                r.status = 404;
                r.body = R"({"code":"not_found"})";
                return r;
            }
            r.body = json{{"accountId", kAccount}, {"complete", true}, {"items", items}}.dump();
        } else {
            r.status = 404;
            r.body = R"({"code":"not_found"})";
        }
        return r;
    }

    BinaryResult PostForBinary(const std::wstring&, const std::string&, void*, std::size_t, int) override {
        BinaryResult r;  // fixture data has no content: reads are never fabricated
        r.kind = HttpResult::Kind::Ok;
        r.status = 415;
        r.errorBody = R"({"code":"unsupported_file"})";
        return r;
    }
};

}  // namespace

std::unique_ptr<AgentTransport> MakeFixtureTransport() { return std::make_unique<FixtureTransport>(); }

}  // namespace hd
