# SPDX-License-Identifier: GPL-3.0-only
# Test helper for content-mount-check.mjs: one NON-CACHED ReadFile (FILE_FLAG_NO_BUFFERING) at an
# offset, so the request reaches the file system instead of being answered by the Windows cache
# manager, and large requests arrive at the VFS as one big callback. Prints one JSON line:
#   {"ok":true,"bytes":N,"sha256":"..."}  or  {"ok":false,"error":<win32 error>}
# Offset and length must be multiples of the sector size (4096 on this volume).
param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][long]$Offset, [Parameter(Mandatory)][int]$Length)

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class HdUnbuffered {
    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern IntPtr CreateFileW(string name, uint access, uint share, IntPtr sa, uint disposition, uint flags, IntPtr template);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool ReadFile(IntPtr h, IntPtr buffer, uint length, out uint read, ref NativeOverlapped o);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool CloseHandle(IntPtr h);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern IntPtr VirtualAlloc(IntPtr address, UIntPtr size, uint type, uint protect);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool VirtualFree(IntPtr address, UIntPtr size, uint type);
    [StructLayout(LayoutKind.Sequential)]
    public struct NativeOverlapped { public UIntPtr Internal; public UIntPtr InternalHigh; public uint Offset; public uint OffsetHigh; public IntPtr Event; }

    public static string Read(string path, long offset, int length) {
        IntPtr h = CreateFileW(path, 0x80000000 /*GENERIC_READ*/, 1 /*FILE_SHARE_READ*/, IntPtr.Zero, 3 /*OPEN_EXISTING*/,
                               0x20000000 /*FILE_FLAG_NO_BUFFERING*/, IntPtr.Zero);
        if (h == new IntPtr(-1)) return "{\"ok\":false,\"error\":" + Marshal.GetLastWin32Error() + "}";
        IntPtr buf = VirtualAlloc(IntPtr.Zero, (UIntPtr)(ulong)length, 0x3000, 0x04); // page-aligned
        try {
            NativeOverlapped o = new NativeOverlapped();
            o.Offset = (uint)(offset & 0xffffffffL);
            o.OffsetHigh = (uint)((ulong)offset >> 32);
            uint read;
            if (!ReadFile(h, buf, (uint)length, out read, ref o)) return "{\"ok\":false,\"error\":" + Marshal.GetLastWin32Error() + "}";
            byte[] data = new byte[read];
            Marshal.Copy(buf, data, 0, (int)read);
            using (var sha = System.Security.Cryptography.SHA256.Create()) {
                return "{\"ok\":true,\"bytes\":" + read + ",\"sha256\":\"" + BitConverter.ToString(sha.ComputeHash(data)).Replace("-", "").ToLowerInvariant() + "\"}";
            }
        } finally {
            VirtualFree(buf, UIntPtr.Zero, 0x8000);
            CloseHandle(h);
        }
    }
}
"@
[HdUnbuffered]::Read($Path, $Offset, $Length)
