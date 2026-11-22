package com.hyperdrive.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads '\n'-terminated UTF-8 lines from a stream with a hard per-line limit, so a misbehaving or
 * hostile parent can never make the agent buffer without bound. A trailing '\r' is dropped.
 */
final class BoundedLineReader {

    static final class LineTooLongException extends IOException {
        LineTooLongException() {
            super("Line exceeds the maximum length");
        }
    }

    private final InputStream in;
    private final int maxBytes;

    BoundedLineReader(InputStream in, int maxBytes) {
        this.in = in;
        this.maxBytes = maxBytes;
    }

    /** The next line, or null at end of stream (a partial final line counts as EOF). */
    String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b == -1) {
                return null;
            }
            if (b == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, length, StandardCharsets.UTF_8);
            }
            if (line.size() >= maxBytes) {
                throw new LineTooLongException();
            }
            line.write(b);
        }
    }
}
