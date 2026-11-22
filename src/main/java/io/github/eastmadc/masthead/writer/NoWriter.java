package io.github.eastmadc.masthead.writer;

import java.time.Duration;

/** writer.mode: none. Always throws, so the caller prints the plain (no-AI) edition. */
public final class NoWriter implements Writer {
    @Override
    public String name() {
        return "none";
    }

    @Override
    public String write(String system, String user, Duration timeout) {
        throw new UnsupportedOperationException("no AI writer configured (writer.mode: none)");
    }
}
