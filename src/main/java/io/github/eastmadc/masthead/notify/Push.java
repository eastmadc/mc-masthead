package io.github.eastmadc.masthead.notify;

import java.util.List;

/** Where owner pushes go: ntfy and/or Discord. {@link Notifier} is the real one; tests record what would be sent. */
public interface Push {
    boolean hasNtfy();

    boolean hasDiscord();

    /** True if ntfy accepted it (false when ntfy is not set up). */
    boolean sendNtfy(String title, String body, String priority, List<String> tags);

    /** True if Discord accepted it (false when Discord is not set up). */
    boolean sendDiscord(String title, String body);

    /** The same message to every channel. True if at least one accepted it. */
    default boolean send(String title, String body, String priority, List<String> tags) {
        boolean ok = false;
        if (hasNtfy()) ok |= sendNtfy(title, body, priority, tags);
        if (hasDiscord()) ok |= sendDiscord(title, body);
        return ok;
    }
}
