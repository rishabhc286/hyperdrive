// SPDX-License-Identifier: GPL-3.0-only
// Minimal self-contained test harness (no third-party test framework).
#pragma once

#include <cstdio>
#include <functional>
#include <string>
#include <vector>

namespace hdtest {

struct Case {
    const char* name;
    std::function<void()> body;
};

inline std::vector<Case>& Registry() {
    static std::vector<Case> cases;
    return cases;
}

struct Registrar {
    Registrar(const char* name, std::function<void()> body) { Registry().push_back({name, std::move(body)}); }
};

struct Failure {
    std::string message;
};

inline void Check(bool ok, const char* expr, const char* file, int line) {
    if (!ok) throw Failure{std::string(file) + ":" + std::to_string(line) + ": CHECK(" + expr + ")"};
}

}  // namespace hdtest

#define HD_CAT2(a, b) a##b
#define HD_CAT(a, b) HD_CAT2(a, b)
#define TEST(name)                                                                \
    static void name();                                                           \
    static hdtest::Registrar HD_CAT(registrar_, name)(#name, name);               \
    static void name()
#define CHECK(expr) hdtest::Check(static_cast<bool>(expr), #expr, __FILE__, __LINE__)
