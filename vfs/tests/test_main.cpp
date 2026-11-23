// SPDX-License-Identifier: GPL-3.0-only
#include <exception>

#include "testing.hpp"

int main() {
    int failed = 0;
    for (const auto& c : hdtest::Registry()) {
        try {
            c.body();
            std::printf("PASS %s\n", c.name);
        } catch (const hdtest::Failure& f) {
            failed++;
            std::printf("FAIL %s\n  %s\n", c.name, f.message.c_str());
        } catch (const std::exception& e) {
            failed++;
            std::printf("FAIL %s\n  exception: %s\n", c.name, e.what());
        }
    }
    std::printf("%zu tests, %d failed\n", hdtest::Registry().size(), failed);
    return failed == 0 ? 0 : 1;
}
