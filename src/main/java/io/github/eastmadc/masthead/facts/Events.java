package io.github.eastmadc.masthead.facts;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Python parse_events(), reading the plugin's events/DATE.jsonl instead of the server log (format: FORMAT.md).
 * Only events inside the edition window [lo, hi) count. Same cleaning as Python: death text is cut at " using [" (the weapon's
 * custom name, which players can type), then capped at 120 characters; names must match the NAME pattern.
 * Since 2.0.1 the recorder stores mob name tags and renamed items as their type and marks the line "v":2. An older line
 * (no "v":2) is dropped when, after taking out player names, it still holds a digit or a [bracket]: that is a name tag or
 * a renamed item, which players type.
 */
final class Events {
    final List<String> joined = new ArrayList<>();        // sorted, unique
    final List<String> deaths = new ArrayList<>();
    final List<String[]> advancements = new ArrayList<>(); // [name, title]
    final List<String[]> pvp = new ArrayList<>();          // [victim, killer]

    static final int MAX_EVENTS = 20000;
    private static final Pattern USING = Pattern.compile(" using \\[.*$");
    private static final Pattern KILLER = Pattern.compile("by (\\.?[A-Za-z0-9_]{3,16})(?: using .*)?$");

    static Events read(Path dataFolder, LocalDate date, long lo, long hi, Set<String> hidden) {
        return read(dataFolder, date, lo, hi, hidden, Set.of());
    }

    /** players: the server's player names (an older death line may name them; their digits are not typed text). */
    static Events read(Path dataFolder, LocalDate date, long lo, long hi, Set<String> hidden, Set<String> players) {
        Events ev = new Events();
        Set<String> joined = new TreeSet<>();
        int seen = 0;
        for (LocalDate day : List.of(date.minusDays(1), date, date.plusDays(1))) {
            for (String line : Py.lines(dataFolder.resolve("events").resolve(day + ".jsonl"), 32 << 20, 200000)) {
                JsonElement el = Py.parse(line);
                if (el == null || !el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                Double t = FactsBuilder.dbl(o.get("t"));
                String k = FactsBuilder.str(o.get("k")), p = FactsBuilder.str(o.get("p"));
                if (t == null || k == null || !(lo <= t && t < hi) || !Py.isName(p) || hidden.contains(p.toLowerCase(java.util.Locale.ROOT))) continue;
                if (++seen > MAX_EVENTS) return ev.finish(joined);
                switch (k) {
                    case "join" -> joined.add(p);
                    case "death" -> ev.death(p, FactsBuilder.str(o.get("msg")), o.has("killer") ? o.get("killer") : null, o.has("killer"), hidden,
                            "2".equals(FactsBuilder.str(o.get("v"))) ? null : players);
                    case "adv" -> {
                        String title = FactsBuilder.str(o.get("title"));
                        if (title == null) continue;
                        title = Py.clean(title, 240);
                        int n = Py.len(title);
                        if (n >= 1 && n <= 60 && title.indexOf(']') < 0) ev.advancements.add(new String[]{p, title});
                    }
                    default -> { }   // unknown kinds are ignored (pvp comes from the death line)
                }
            }
        }
        return ev.finish(joined);
    }

    private Events finish(Set<String> j) {
        joined.addAll(j);
        return this;
    }

    private static final Pattern TYPED = Pattern.compile("[0-9\\[\\]]");

    /** oldLinePlayers: null for a line from the 2.0.1+ recorder; otherwise the player names, for the stricter check. */
    private void death(String p, String msg, JsonElement killerField, boolean hasKiller, Set<String> hidden, Set<String> oldLinePlayers) {
        if (msg == null) return;
        // Python capped the whole "System chat: <msg>" line at 240, so the message itself at 227.
        String text = Py.clean(msg, 227);
        if (!text.startsWith(p + " ")) return;
        text = Py.clean(USING.matcher(text).replaceFirst(""), 120);
        if (Py.mentions(text, hidden)) return;
        String killer = null;
        if (hasKiller) {
            // The plugin knows the killer for sure; "" or null = not killed by a player.
            String k = FactsBuilder.str(killerField);
            if (Py.isName(k)) killer = k;
        } else {
            Matcher m = KILLER.matcher(text);
            if (m.find()) killer = m.group(1);
        }
        if (killer != null && hidden.contains(killer.toLowerCase(java.util.Locale.ROOT))) return;
        if (oldLinePlayers != null && typedText(text, p, killer, oldLinePlayers)) return;
        deaths.add(text);
        if (killer != null) pvp.add(new String[]{p, killer});
    }

    /** An older line still holding a digit or a bracket once player names are taken out: a name tag or renamed item. */
    static boolean typedText(String text, String victim, String killer, Set<String> players) {
        List<String> names = new ArrayList<>(players);
        names.add(victim);
        if (killer != null) names.add(killer);
        names.sort((a, b) -> Integer.compare(b.length(), a.length()));
        String rest = text;
        for (String n : names) if (!n.isEmpty()) rest = rest.replace(n, " ");
        return TYPED.matcher(rest).find();
    }
}
