package io.github.eastmadc.masthead.pipeline;

/**
 * When to (re)try today's edition. A day counts as done only once a paper is published (state.yml last-edition). Before
 * each try the plugin notes the date, the try number and the time (edition-attempt-*). A try that fails, or is cut off
 * by a restart, is tried again about 10 minutes later, up to 3 tries. After the third failure the plain edition is
 * published at once (try 4). If even that fails, the owner is told and the plugin waits for tomorrow (or /masthead now).
 */
public final class Attempts {
    private Attempts() {}

    public static final int TRIES = 3;
    public static final long GAP_SECONDS = 600;

    public enum Next { DONE, RUN, WAIT, PLAIN, GAVE_UP }

    /**
     * @param today        today's date (yyyy-mm-dd)
     * @param lastEdition  the last published day
     * @param attemptDate  the day of the last noted try
     * @param tries        how many tries were noted for that day
     * @param lastAt       when the last try started or failed (epoch seconds)
     * @param now          now (epoch seconds)
     */
    public static Next next(String today, String lastEdition, String attemptDate, int tries, long lastAt, long now) {
        if (today.equals(lastEdition)) return Next.DONE;
        if (!today.equals(attemptDate) || tries <= 0) return Next.RUN;
        if (tries < TRIES) return now - lastAt >= GAP_SECONDS ? Next.RUN : Next.WAIT;
        if (tries == TRIES) return Next.PLAIN;
        return Next.GAVE_UP;
    }

    /** The last published day: state.yml's, or today when this server published today but could not save it. */
    public static String lastEdition(String saved, String publishedInMemory, String today) {
        return today.equals(publishedInMemory) ? today : saved;
    }

    /** GAVE_UP: tell the owner "today's paper failed" unless they were told today (state.yml or memory). */
    public static boolean tellGaveUp(String today, String toldSaved, String toldInMemory) {
        return !today.equals(toldSaved) && !today.equals(toldInMemory);
    }

    /** A short, one-line reason for the owner (no stack trace, no line breaks). */
    public static String reason(Throwable t) {
        String m = t.getMessage() == null ? "" : ": " + t.getMessage().replaceAll("[\\r\\n\\t]+", " ").strip();
        String r = t.getClass().getSimpleName() + m;
        return r.length() > 160 ? r.substring(0, 160) : r;
    }
}
