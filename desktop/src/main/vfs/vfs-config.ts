import { isAbsolute, join, resolve } from 'node:path';
import type { VirtualDrivePrerequisite } from '../../shared/vfs';

export interface VfsEnv {
  /** Absolute path of hyperdrive-vfs.exe (overrides the development default). */
  readonly HYPERDRIVE_VFS_EXE?: string | undefined;
  readonly 'ProgramFiles(x86)'?: string | undefined;
}

export type VfsConfig = { readonly exePath: string } | { readonly missing: VirtualDrivePrerequisite };

/**
 * Where hyperdrive-vfs.exe comes from, and whether WinFsp is installed. Trusted main-process inputs
 * only (Electron's environment and paths). No installer exists yet: a packaged app needs
 * HYPERDRIVE_VFS_EXE. WinFsp itself is never installed or downloaded by HyperDrive.
 */
export function resolveVfsConfig(options: {
  readonly env: VfsEnv;
  readonly platform: NodeJS.Platform;
  readonly arch: string;
  readonly isPackaged: boolean;
  readonly appPath: string;
  readonly fileExists: (path: string) => boolean;
}): VfsConfig {
  if (options.platform !== 'win32' || options.arch !== 'x64') return { missing: 'unsupported-platform' };
  const programFilesX86 = options.env['ProgramFiles(x86)'] ?? 'C:\\Program Files (x86)';
  // The runtime DLL WinFsp's loader (FspLoad) resolves; installed by the WinFsp MSI.
  if (!options.fileExists(join(programFilesX86, 'WinFsp', 'bin', 'winfsp-x64.dll'))) return { missing: 'winfsp-missing' };
  const explicit = options.env.HYPERDRIVE_VFS_EXE?.trim();
  let exePath: string;
  if (explicit) {
    if (!isAbsolute(explicit) || !explicit.toLowerCase().endsWith('.exe')) return { missing: 'vfs-missing' };
    exePath = explicit;
  } else if (options.isPackaged) {
    return { missing: 'vfs-missing' };
  } else {
    exePath = resolve(options.appPath, '..', 'vfs', 'build', 'hyperdrive-vfs.exe');
  }
  return options.fileExists(exePath) ? { exePath } : { missing: 'vfs-missing' };
}
