// SPDX-License-Identifier: GPL-3.0-only
#pragma once

#include <deque>
#include <functional>
#include <string>
#include <vector>

#include "core/transport.hpp"

struct FakeTransport : hd::AgentTransport {
    std::function<hd::HttpResult(const std::wstring&, const std::string&)> handler;
    std::vector<std::wstring> paths;
    std::vector<std::string> bodies;
    // Batch 9: binary answers (the handler writes into the destination like the real transport).
    std::function<hd::BinaryResult(const std::wstring&, const std::string&, void*, std::size_t)> binary;
    std::vector<int> receiveTimeouts;

    hd::HttpResult Post(const std::wstring& path, const std::string& body) override {
        paths.push_back(path);
        bodies.push_back(body);
        return handler(path, body);
    }

    hd::BinaryResult PostForBinary(const std::wstring& path, const std::string& body, void* destination,
                                   std::size_t capacity, int receiveMs) override {
        paths.push_back(path);
        bodies.push_back(body);
        receiveTimeouts.push_back(receiveMs);
        return binary(path, body, destination, capacity);
    }

    static hd::HttpResult Ok(const std::string& body) {
        hd::HttpResult r;
        r.kind = hd::HttpResult::Kind::Ok;
        r.status = 200;
        r.body = body;
        return r;
    }

    static hd::HttpResult Status(int status, const std::string& body) {
        hd::HttpResult r;
        r.kind = hd::HttpResult::Kind::Ok;
        r.status = status;
        r.body = body;
        return r;
    }

    static hd::HttpResult Transport(hd::HttpResult::Kind kind) {
        hd::HttpResult r;
        r.kind = kind;
        return r;
    }
};
