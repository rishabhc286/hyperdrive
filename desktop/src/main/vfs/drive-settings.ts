import { promises as fsPromises } from 'node:fs';
import { dirname } from 'node:path';
import { DEFAULT_DRIVE_LETTER, isAllowedDriveLetter } from '../../shared/vfs';

type FsLike = Pick<typeof fsPromises, 'readFile' | 'writeFile' | 'rename' | 'mkdir'>;

/** The configured drive letter (not a secret), validated on load and save. */
export class DriveSettings {
  constructor(
    private readonly file: string,
    private readonly fs: FsLike = fsPromises
  ) {}

  async load(): Promise<string> {
    try {
      const parsed = JSON.parse(await this.fs.readFile(this.file, 'utf8')) as { v?: unknown; driveLetter?: unknown };
      return parsed.v === 1 && isAllowedDriveLetter(parsed.driveLetter) ? parsed.driveLetter : DEFAULT_DRIVE_LETTER;
    } catch {
      return DEFAULT_DRIVE_LETTER;
    }
  }

  async save(letter: string): Promise<void> {
    if (!isAllowedDriveLetter(letter)) throw new Error('invalid drive letter');
    await this.fs.mkdir(dirname(this.file), { recursive: true });
    await this.fs.writeFile(`${this.file}.tmp`, JSON.stringify({ v: 1, driveLetter: letter }), 'utf8');
    await this.fs.rename(`${this.file}.tmp`, this.file);
  }
}
