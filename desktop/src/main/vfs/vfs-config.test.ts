// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { resolveVfsConfig } from './vfs-config';

const WINFSP_DLL = 'C:\\Program Files (x86)\\WinFsp\\bin\\winfsp-x64.dll';
const base = {
  env: { 'ProgramFiles(x86)': 'C:\\Program Files (x86)' },
  platform: 'win32' as NodeJS.Platform,
  arch: 'x64',
  isPackaged: false,
  appPath: 'C:\\repo\\desktop'
};

describe('resolveVfsConfig', () => {
  it('reports each missing prerequisite precisely', () => {
    expect(resolveVfsConfig({ ...base, platform: 'linux', fileExists: () => true })).toEqual({ missing: 'unsupported-platform' });
    expect(resolveVfsConfig({ ...base, arch: 'ia32', fileExists: () => true })).toEqual({ missing: 'unsupported-platform' });
    expect(resolveVfsConfig({ ...base, fileExists: () => false })).toEqual({ missing: 'winfsp-missing' });
    expect(resolveVfsConfig({ ...base, fileExists: (p) => p === WINFSP_DLL })).toEqual({ missing: 'vfs-missing' });
    expect(resolveVfsConfig({ ...base, isPackaged: true, fileExists: () => true })).toEqual({ missing: 'vfs-missing' });
  });

  it('uses the development build or an absolute, explicit .exe only', () => {
    expect(resolveVfsConfig({ ...base, fileExists: () => true })).toEqual({ exePath: 'C:\\repo\\vfs\\build\\hyperdrive-vfs.exe' });
    expect(
      resolveVfsConfig({ ...base, env: { ...base.env, HYPERDRIVE_VFS_EXE: 'relative.exe' }, fileExists: () => true })
    ).toEqual({ missing: 'vfs-missing' });
    expect(
      resolveVfsConfig({ ...base, env: { ...base.env, HYPERDRIVE_VFS_EXE: 'C:\\x\\run.bat' }, fileExists: () => true })
    ).toEqual({ missing: 'vfs-missing' });
    expect(
      resolveVfsConfig({ ...base, env: { ...base.env, HYPERDRIVE_VFS_EXE: 'D:\\hd\\hyperdrive-vfs.exe' }, fileExists: () => true })
    ).toEqual({ exePath: 'D:\\hd\\hyperdrive-vfs.exe' });
  });
});
