package com.hyperdrive.agent;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Exclusive ownership of the agent's state directory via an OS file lock on {@code agent.lock}.
 * A second agent (from another Electron instance, or a stale process) fails fast instead of
 * sharing state. The lock is released by the OS if the process dies.
 */
final class DataDirectoryLock implements AutoCloseable {

    static final String LOCK_FILE = "agent.lock";

    static final class LockedException extends Exception {
        LockedException() {
            super("The agent data directory is already in use by another agent process", null, false, false);
        }
    }

    private final FileChannel channel;
    private final FileLock lock;

    private DataDirectoryLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static DataDirectoryLock acquire(Path dataDir) throws IOException, LockedException {
        Files.createDirectories(dataDir);
        if (!Files.isDirectory(dataDir) || !Files.isWritable(dataDir)) {
            throw new IOException("Agent data directory is not a writable directory");
        }
        FileChannel channel = FileChannel.open(dataDir.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        } catch (IOException e) {
            channel.close();
            throw e;
        }
        if (lock == null) {
            channel.close();
            throw new LockedException();
        }
        return new DataDirectoryLock(channel, lock);
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException ignored) {
            // Released by the OS on exit anyway.
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Nothing else to do.
        }
    }
}
