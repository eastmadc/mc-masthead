package io.github.eastmadc.masthead.edition;

import java.nio.file.Path;

/**
 * Where one edition run writes. A real run (publish) writes into the plugin folder. A preview writes only under
 * preview/: its own editions/ and preview/index.html (its stats/ copy is deleted when it ends, and it keeps no desk
 * answer, since that quotes letters). It never touches the published
 * archive (editions/), the day's facts (data/), the stats snapshot (stats/), the desk cache (desk/), today.json,
 * the web pages, or any mailroom file.
 */
public record Outputs(boolean publish, Path root) {
    /** A real run: everything in the plugin folder. */
    public static Outputs publish(Path dataFolder) { return new Outputs(true, dataFolder); }

    /** A preview: everything under preview/ in the plugin folder. */
    public static Outputs preview(Path dataFolder) { return new Outputs(false, dataFolder.resolve("preview")); }

    /** Where DATE.html is written (the archive is read from the plugin's editions/ as well). */
    public Path editionsDir() { return root.resolve("editions"); }

    /** Where the day's facts file goes; null for a preview (no facts file). */
    public Path factsDir() { return publish ? root.resolve("data") : null; }

    /** Where a fresh editor-desk answer is cached. */
    public Path deskDir() { return root.resolve("desk"); }
}
