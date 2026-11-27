// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { taskkillArgs } from './node-runtime';

describe('taskkillArgs', () => {
  it('targets exactly one owned PID and its descendants, by absolute System32 path', () => {
    expect(taskkillArgs(4321, 'C:\\Windows')).toEqual({ command: 'C:\\Windows\\System32\\taskkill.exe', args: ['/PID', '4321', '/T', '/F'] });
  });

  it('never uses an untrusted SystemRoot or an invalid PID', () => {
    expect(taskkillArgs(7, 'relative\\dir').command).toBe('C:\\Windows\\System32\\taskkill.exe');
    expect(taskkillArgs(7, undefined).command).toBe('C:\\Windows\\System32\\taskkill.exe');
    expect(taskkillArgs(7, 'C:\\Win"dows').command).toBe('C:\\Windows\\System32\\taskkill.exe');
    expect(() => taskkillArgs(0, 'C:\\Windows')).toThrow();
    expect(() => taskkillArgs(-5, 'C:\\Windows')).toThrow();
    expect(() => taskkillArgs(1.5, 'C:\\Windows')).toThrow();
  });
});
