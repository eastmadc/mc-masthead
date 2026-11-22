package io.github.eastmadc.masthead.game;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ParameterizedMessage;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps letters out of the server log. With spigot.yml commands.log: true (the default), the
 * server logs every player command as "Name issued server command: /letter i want to die" before the plugin sees it, and
 * the console log is kept forever. This drops that line when the command carries a letter: /letter <text>, /letter tip
 * <text>, /news write <text>, /news letter <text>, and the extra names from commands: in config.yml. A bare /letter (the
 * pop-up form) is still logged; it carries no words.
 * Paper supplies log4j; if a server has no log4j, {@link #install} says so and the plugin works without it.
 */
public final class LetterLogFilter extends AbstractFilter {
    private static final Pattern COMMAND = Pattern.compile("^\\S+ issued server command: /(?:[A-Za-z0-9_.-]+:)?(\\S+)(?:\\s+(.*))?$", Pattern.DOTALL);

    private final Set<String> letter, news;

    LetterLogFilter(Set<String> letterNames, Set<String> newsNames) {
        super(Result.DENY, Result.NEUTRAL);
        this.letter = letterNames;
        this.news = newsNames;
    }

    /** True if this console line is a player command that carries a letter's words. Names are lower case. */
    static boolean hides(String line, Set<String> letterNames, Set<String> newsNames) {
        if (line == null || !line.contains(" issued server command: /")) return false;
        Matcher m = COMMAND.matcher(line.strip());
        if (!m.matches()) return false;
        String label = m.group(1).toLowerCase(Locale.ROOT), rest = m.group(2) == null ? "" : m.group(2).strip();
        if (rest.isEmpty()) return false;
        if (letterNames.contains(label)) return true;   // /letter <text>, /letter tip <text>
        if (!newsNames.contains(label)) return false;
        String[] w = rest.split("\\s+", 2);              // /news write <text>, /news letter <text>
        String sub = w[0].toLowerCase(Locale.ROOT);
        return (sub.equals("write") || sub.equals("letter")) && w.length > 1 && !w[1].isBlank();
    }

    private Result check(String line) { return hides(line, letter, news) ? onMatch : onMismatch; }

    @Override public Result filter(LogEvent event) {
        return event == null || event.getMessage() == null ? onMismatch : check(event.getMessage().getFormattedMessage());
    }

    @Override public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        return msg == null ? onMismatch : check(msg.getFormattedMessage());
    }

    @Override public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
        return msg == null ? onMismatch : check(msg.toString());
    }

    @Override public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
        return msg == null ? onMismatch : check(params == null || params.length == 0 ? msg : ParameterizedMessage.format(msg, params));
    }

    // ------------------------------------------------------------ install / remove (the server's root logger)
    /** Adds the filter to the server's root logger. Returns the handle for {@link #remove}, or null if it could not. */
    public static Object install(Set<String> letterNames, Set<String> newsNames, java.util.logging.Logger log) {
        try {
            LetterLogFilter f = new LetterLogFilter(Set.copyOf(letterNames), Set.copyOf(newsNames));
            f.start();
            root().addFilter(f);
            return f;
        } catch (Throwable e) {   // no log4j on this server (NoClassDefFoundError), or a different logging setup
            log.warning("Could not keep letters out of the server log (" + e.getClass().getSimpleName() + "). Players' /letter <text> "
                    + "commands may be logged with their words; set commands.log: false in spigot.yml to stop that.");
            return null;
        }
    }

    public static void remove(Object handle) {
        if (!(handle instanceof LetterLogFilter f)) return;
        try { root().removeFilter(f); f.stop(); } catch (Throwable ignored) { }
    }

    private static LoggerConfig root() {
        return ((LoggerContext) LogManager.getContext(false)).getConfiguration().getRootLogger();
    }
}
