// SPDX-License-Identifier: GPL-3.0-only
// hyperdrive-vfs: HyperDrive's read-only, metadata-only virtual drive (Batch 8).
//
// Normal mode (started by the desktop's VfsManager):
//   * no command-line arguments; one bounded JSON bootstrap line on stdin (agent port, mount
//     capability, drive letter);
//   * JSON events on stdout, one per line, never containing secrets:
//       started -> agent-authenticated -> volume-created -> mounted -> unmounted, or failed{code};
//   * stdin stays open as the parent channel: "unmount" or EOF (desktop exit/crash) unmounts.
// Fixture mode (manual testing only): `hyperdrive-vfs.exe --fixture-mount <letter>` mounts fixed
// test data labeled "HyperDrive FIXTURE"; no agent, no cloud.
#include <winfsp/winfsp.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <mutex>
#include <string>
#include <thread>

#include "core/agent_client.hpp"
#include "core/bootstrap.hpp"
#include "core/fixture.hpp"
#include "core/tree.hpp"
#include "fs/filesystem.hpp"

namespace {

std::mutex g_outMutex;
std::mutex g_stopMutex;
std::condition_variable g_stopCv;
std::atomic<bool> g_stop{false};
std::string g_stopReason = "requested";

void Emit(const std::string& json) {
    std::lock_guard<std::mutex> lock(g_outMutex);
    std::fputs(json.c_str(), stdout);
    std::fputc('\n', stdout);
    std::fflush(stdout);
}

void EmitFailed(const char* code) { Emit(std::string(R"({"event":"failed","code":")") + code + "\"}"); }

void RequestStop(const std::string& reason) {
    std::lock_guard<std::mutex> lock(g_stopMutex);
    if (!g_stop.exchange(true)) g_stopReason = reason;
    g_stopCv.notify_all();
}

BOOL WINAPI OnConsoleCtrl(DWORD) {
    RequestStop("console-signal");
    return TRUE;
}

// Reads one line (without the newline) from stdin, at most `max` bytes. Returns false on EOF/error
// or if the line is too long.
bool ReadLine(HANDLE in, std::size_t max, std::string& line) {
    line.clear();
    char c;
    DWORD n = 0;
    while (ReadFile(in, &c, 1, &n, nullptr) && n == 1) {
        if (c == '\n') return true;
        if (c != '\r') line.push_back(c);
        if (line.size() > max) return false;
    }
    return false;
}

std::int64_t NowFiletime() {
    FILETIME ft;
    GetSystemTimeAsFileTime(&ft);
    return (static_cast<std::int64_t>(ft.dwHighDateTime) << 32) | ft.dwLowDateTime;
}

bool DriveLetterInUse(wchar_t letter) { return (GetLogicalDrives() & (1u << (letter - L'A'))) != 0; }

int Run(hd::AgentTransport& transport, wchar_t letter, bool fixture, HANDLE stdinHandle) {
    hd::AgentClient client(transport, fixture);
    if (!fixture) {
        auto session = client.Session();
        if (!session.ok()) {
            EmitFailed(session.fault == hd::Fault::AuthorizationLost ? "agent-unauthorized" : "agent-unreachable");
            return 3;
        }
        Emit(R"({"event":"agent-authenticated","generation":)" + std::to_string(session.value) + "}");
    }
    if (DriveLetterInUse(letter)) {
        EmitFailed("drive-letter-in-use");  // never replace an existing drive
        return 4;
    }
    std::int64_t created = NowFiletime();
    hd::MetadataTree tree(client, created);
    hd::HyperDriveFs fs(tree, fixture ? L"HyperDrive FIXTURE" : L"HyperDrive");
    if (!NT_SUCCESS(fs.Create(created))) {
        EmitFailed("volume-create-failed");
        return 5;
    }
    Emit(R"({"event":"volume-created"})");
    std::wstring mountPoint = std::wstring(1, letter) + L":";
    if (!NT_SUCCESS(fs.Mount(mountPoint))) {
        EmitFailed(DriveLetterInUse(letter) ? "drive-letter-in-use" : "mount-failed");
        return 6;
    }
    if (!NT_SUCCESS(fs.Start())) {
        fs.Stop();
        EmitFailed("dispatcher-failed");
        return 7;
    }
    // Only report "mounted" once Windows actually shows the drive.
    std::wstring root = mountPoint + L"\\";
    bool visible = false;
    for (int i = 0; i < 50 && !visible; i++) {
        visible = GetDriveTypeW(root.c_str()) != DRIVE_NO_ROOT_DIR;
        if (!visible) std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
    if (!visible) {
        fs.Stop();
        EmitFailed("mount-not-visible");
        return 8;
    }
    Emit(std::string(R"({"event":"mounted","mountPoint":")") + static_cast<char>(letter) + R"(:","fixture":)" +
         (fixture ? "true" : "false") + "}");

    // Parent channel: "unmount" or EOF stops the drive.
    std::thread control([stdinHandle] {
        std::string line;
        while (!g_stop) {
            if (!ReadLine(stdinHandle, 256, line)) {
                RequestStop("parent-closed");
                return;
            }
            if (line == "unmount") {
                RequestStop("requested");
                return;
            }
        }
    });
    control.detach();

    // Heartbeat: a revoked capability or a gone agent ends the mount within bounded time.
    std::thread heartbeat;
    if (!fixture) {
        heartbeat = std::thread([&client] {
            int failures = 0;
            while (!g_stop) {
                {
                    std::unique_lock<std::mutex> lock(g_stopMutex);
                    g_stopCv.wait_for(lock, std::chrono::seconds(10), [] { return g_stop.load(); });
                }
                if (g_stop) return;
                auto s = client.Session();
                if (s.fault == hd::Fault::AuthorizationLost) {
                    RequestStop("authorization-lost");
                    return;
                }
                failures = s.ok() ? 0 : failures + 1;
                if (failures >= 3) {
                    RequestStop("agent-unreachable");
                    return;
                }
            }
        });
    }

    {
        std::unique_lock<std::mutex> lock(g_stopMutex);
        g_stopCv.wait(lock, [] { return g_stop.load(); });
    }
    fs.Stop();
    if (heartbeat.joinable()) heartbeat.join();
    std::string reason;
    {
        std::lock_guard<std::mutex> lock(g_stopMutex);
        reason = g_stopReason;
    }
    Emit(R"({"event":"unmounted","reason":")" + reason + "\"}");
    return reason == "authorization-lost" || reason == "agent-unreachable" ? 9 : 0;
}

}  // namespace

int wmain(int argc, wchar_t** argv) {
    SetConsoleCtrlHandler(OnConsoleCtrl, TRUE);
    bool fixture = false;
    wchar_t fixtureLetter = 0;
    if (argc == 3 && std::wstring(argv[1]) == L"--fixture-mount" && std::wcslen(argv[2]) == 1) {
        fixture = true;
        fixtureLetter = static_cast<wchar_t>(towupper(argv[2][0]));
    } else if (argc != 1) {
        std::fputs("usage: hyperdrive-vfs.exe (bootstrap on stdin) | --fixture-mount <letter>\n", stderr);
        return 2;
    }
    if (!NT_SUCCESS(FspLoad(nullptr))) {
        EmitFailed("winfsp-runtime-missing");
        return 10;
    }
    Emit(R"({"event":"started","protocolVersion":1})");
    HANDLE in = GetStdHandle(STD_INPUT_HANDLE);

    if (fixture) {
        if (!hd::IsAllowedDriveLetter(fixtureLetter)) {
            EmitFailed("invalid_drive_letter");
            return 2;
        }
        Emit(R"({"event":"fixture-mode","note":"test data only; not cloud integration"})");
        auto transport = hd::MakeFixtureTransport();
        return Run(*transport, fixtureLetter, true, in);
    }

    // Bounded bootstrap with a startup timeout.
    std::string line;
    std::atomic<int> state{0};  // 0 pending, 1 ok, 2 failed
    std::thread reader([&] { state = ReadLine(in, hd::kMaxBootstrapBytes, line) ? 1 : 2; });
    for (int i = 0; i < 100 && state == 0; i++) std::this_thread::sleep_for(std::chrono::milliseconds(100));
    if (state != 1) {
        EmitFailed(state == 0 ? "bootstrap_timeout" : "bootstrap_invalid");
        reader.detach();
        std::fflush(stdout);
        ExitProcess(2);  // the reader thread may still be blocked on stdin
    }
    reader.join();
    hd::Bootstrap bootstrap;
    std::string error;
    bool parsed = hd::ParseBootstrap(line, bootstrap, error);
    SecureZeroMemory(line.data(), line.size());
    if (!parsed) {
        EmitFailed(error.c_str());
        return 2;
    }
    auto transport = hd::MakeWinHttpTransport(bootstrap.agentPort, bootstrap.capability);
    SecureZeroMemory(bootstrap.capability.data(), bootstrap.capability.size());
    return Run(*transport, bootstrap.driveLetter, false, in);
}
