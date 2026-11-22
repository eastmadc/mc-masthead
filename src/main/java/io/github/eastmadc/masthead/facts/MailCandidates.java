package io.github.eastmadc.masthead.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Python mail_candidates(): letters and tips waiting for the editor desk (mailroom/DATE.jsonl, original Mailroom format).
 * A letter normally waits at most 3 editions. One the desk could not read (listed in mailbag-carry.txt) waits up to 7 days,
 * as long as the mailroom keeps the file. "wb":true (a worried letter, marked when it was stored) is carried along.
 * A worried letter stays a candidate even when a later letter from the same player replaces it (review round 3): one held
 * back by the per-player push limit is reported at the edition, whatever the player wrote after it.
 */
final class MailCandidates {
    private MailCandidates() {}

    static final int MAIL_DAYS = 3;   // a letter waits at most 3 editions
    static final int MAX_CANDIDATES = 8;
    static final int CARRY_DAYS = 7;   // the mailroom deletes letters after 7 days
    private static final Pattern MAIL_ID = Pattern.compile("\\d{4}-\\d\\d-\\d\\d-[0-9a-f]{8}");
    private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /** Real players only, last version per player/kind/day, not done (mailbag-done.txt), oldest first. */
    static JsonArray read(Path dataFolder, LocalDate date, Set<String> players) {
        Set<String> done = new HashSet<>();
        String d = Py.readCapped(dataFolder.resolve("mailbag-done.txt"), 4 << 20);
        if (d != null) for (String w : d.trim().split("\\s+")) if (!w.isEmpty()) done.add(w);
        Set<String> carry = new HashSet<>();
        String cf = Py.readCapped(dataFolder.resolve("mailbag-carry.txt"), 1 << 20);
        if (cf != null) for (String w : cf.trim().split("\\s+")) if (!w.isEmpty()) carry.add(w);
        LinkedHashMap<String, JsonObject> last = new LinkedHashMap<>(), lastWorried = new LinkedHashMap<>();
        for (int i = (carry.isEmpty() ? MAIL_DAYS : CARRY_DAYS) - 1; i >= 0; i--) {
            String day = date.minusDays(i).toString();
            for (String line : Py.lines(dataFolder.resolve("mailroom").resolve(day + ".jsonl"), 1 << 20, 5000)) {
                JsonElement el = Py.parse(line);
                if (el == null || !el.isJsonObject()) continue;   // a line cut short is picked up whole next time
                JsonObject r = el.getAsJsonObject();
                String id = str(r.get("id")), name = str(r.get("name")), kind = str(r.get("kind")), text = str(r.get("text"));
                if (id == null || !MAIL_ID.matcher(id).matches() || !id.startsWith(day) || !Py.isName(name) || !players.contains(name)
                        || !("letter".equals(kind) || "tip".equals(kind)) || text == null) continue;
                if (i >= MAIL_DAYS && !carry.contains(id)) continue;
                text = SPACES.matcher(Py.clean(text, 220)).replaceAll(" ").strip();
                int n = Py.len(text);
                if (n < 3 || n > 200) continue;
                JsonObject c = new JsonObject();
                c.addProperty("id", id);
                c.addProperty("from", name);
                c.addProperty("kind", kind);
                c.addProperty("text", text);
                if (r.get("wb") != null && r.get("wb").isJsonPrimitive() && r.get("wb").getAsJsonPrimitive().isBoolean() && r.get("wb").getAsBoolean())
                    c.addProperty("wb", true);
                String key = name + "\t" + kind + "\t" + day;
                last.remove(key);   // Python dict keeps the first position; harmless here because we sort by id below
                last.put(key, c);
                if (c.has("wb")) lastWorried.put(key, c);
            }
        }
        // the last worried letter of each player/kind/day that a later letter replaced: reported anyway (never printed)
        List<JsonObject> worried = new ArrayList<>();
        lastWorried.forEach((key, c) -> { if (last.get(key) != c) worried.add(c); });
        List<JsonObject> all = new ArrayList<>(last.values());
        all.sort((a, b) -> a.get("id").getAsString().compareTo(b.get("id").getAsString()));   // ids start with the day: oldest first
        JsonArray out = new JsonArray();
        Set<String> seen = new HashSet<>();
        for (JsonObject c : all) {
            String id = c.get("id").getAsString(), who = c.get("from").getAsString() + "\t" + c.get("kind").getAsString();
            if (done.contains(id) || seen.contains(who)) continue;
            seen.add(who);
            out.add(c);
            if (out.size() >= MAX_CANDIDATES) break;
        }
        for (JsonObject c : worried) if (!done.contains(c.get("id").getAsString())) out.add(c);
        return out;
    }

    private static String str(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }
}
