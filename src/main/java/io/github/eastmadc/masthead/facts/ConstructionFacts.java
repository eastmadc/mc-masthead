package io.github.eastmadc.masthead.facts;

import io.github.eastmadc.masthead.construction.Families;
import io.github.eastmadc.masthead.construction.Features;
import io.github.eastmadc.masthead.construction.NameRules;
import com.google.gson.JsonArray;
import io.github.eastmadc.masthead.construction.Kinds;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Gate 1: reads construction/out/DATE.json and keeps only what the writer may see, key by key. Anything not on the list is
 * dropped (the key name is reported, never its value). No key that could hold a place, and no digit in any text but a
 * player name, gets through even if a later code change puts one in the file.
 */
public final class ConstructionFacts {
    private ConstructionFacts() {}

    /** construction = the block for data.construction (null when there is nothing to print). */
    public record Result(JsonObject construction, List<BlotterFacts.Entry> blotter, Set<String> dropMinedAt, List<Awards.Extra> awardReasons,
                         List<String> dropped) {}

    static final Set<String> TAGS = Set.of("new_resident", "survey", "permit", "rebuild", "progress", "ribbon", "incident", "public_works", "demolition", "renovation");
    static final Set<String> KINDS = Set.copyOf(Kinds.ALL);
    static final Set<String> KIND_DISPLAY = Set.copyOf(Kinds.ALL.stream().map(Kinds::display).toList());
    private static final Set<String> CONFIDENCE = Set.of("low", "medium", "high"), SIZE = Set.of("compact", "medium", "large", "huge"),
            HEIGHT = Set.of("low", "tall", "very_tall", "towering"), CAUSE = Set.of("creeper", "tnt", "fire", "explosion", "wither"),
            ANGLE = Set.of("materials", "crew", "size", "day", "parts", "milestone", "incident"),
            PARTS = Set.of("pistons", "observers", "repeaters", "comparators", "redstone_dust", "dispensers", "crops");
    private static final Set<Integer> MILESTONES = Set.of(1000, 2500, 5000, 10000, 25000, 50000);
    private static final Set<String> COUNTS = Set.of("day", "blocks_today", "blocks_total", "blocks_week", "times_in_paper");
    private static final Set<String> ENTRY_KEYS = Set.of("tag", "permit", "name", "builders", "new_builders", "kind", "confidence", "megaproject", "day",
            "blocks_today", "blocks_total", "size", "height", "materials", "odd_material", "parts", "features", "milestone_blocks", "incident", "existing",
            "angle", "times_in_paper");
    private static final Set<String> ROW_KEYS = Set.of("permit", "name", "builders", "kind", "day", "blocks_total", "existing", "critic");
    private static final Set<String> SURVEY_KEYS = Set.of("permit", "name", "builders", "kind", "features", "blocks_total", "existing");
    /** The opening paper: up to this many stories whatever max-entries is, and up to this many rows on the Town Survey page. */
    static final int OPENING_STORIES = 5, SURVEY_ROWS = 300;
    private static final Set<String> CRITIC_KEYS = Set.of("confidence", "materials", "odd_material", "size", "height", "blocks_week");
    /** Keys that could carry a place, a time or an id: never allowed. */
    static final Pattern BANNED_KEY = Pattern.compile("(?i)^(x|y|z|cx|cy|cz|chunk|cell|cube|region|world|dim(ension)?|biome|pos|loc|location|bounds?|min|max"
            + "|center|centroid|dist(ance)?|dir(ection)?|bearing|near|neighbou?rs?|length|width|depth|area|light|time|date|t|id|site|uuid)$");
    private static final Pattern NAME = Pattern.compile("\\.?[A-Za-z0-9_]{3,16}"), WHAT = Pattern.compile("[a-z ]{2,30}"),
            AWARD_TEXT = Pattern.compile("[A-Za-z0-9 ,'-]{1,80}");

    /** Reads and filters an out/ file. A missing or broken file gives an empty result. */
    public static Result read(Path outFile, Set<String> players, Set<String> hidden, boolean weekly, int maxEntries) {
        List<String> dropped = new ArrayList<>();
        String txt = outFile == null ? null : Py.readCapped(outFile, 1 << 20);
        JsonElement el = txt == null ? null : Py.parse(txt);
        if (el == null || !el.isJsonObject()) return new Result(null, List.of(), Set.of(), List.of(), dropped);
        return filter(el.getAsJsonObject(), players, hidden, weekly, maxEntries, dropped);
    }

    public static Result filter(JsonObject in, Set<String> players, Set<String> hidden, boolean weekly, int maxEntries, List<String> dropped) {
        Set<String> hid = new HashSet<>();
        for (String h : hidden) hid.add(h.toLowerCase(Locale.ROOT));
        JsonObject c = in.get("construction") instanceof JsonObject o ? o : new JsonObject();
        for (String k : c.keySet()) if (!Set.of("opening", "entries", "tracker", "survey").contains(k)) dropped.add(k);
        JsonObject cons = new JsonObject();
        boolean opening = c.get("opening") instanceof JsonPrimitive p && p.isBoolean() && p.getAsBoolean();
        cons.addProperty("opening", opening);
        JsonArray entries = new JsonArray();
        int cap = opening ? Math.max(maxEntries, OPENING_STORIES) : maxEntries;
        for (JsonElement e : arr(c.get("entries"))) {
            if (entries.size() >= cap) { dropped.add("entries"); break; }
            JsonObject x = entry(e, players, hid, dropped);
            if (x != null) entries.add(x);
        }
        cons.add("entries", entries);
        JsonArray tracker = new JsonArray();
        if (weekly) for (JsonElement r : arr(c.get("tracker"))) {
            if (tracker.size() >= 8) break;
            JsonObject x = row(r, players, hid, dropped);
            if (x != null) tracker.add(x);
        } else if (c.has("tracker")) dropped.add("tracker");
        if (tracker.size() > 0) cons.add("tracker", tracker);
        // the opening paper's Town Survey page: every registered build (the writer sees only how many)
        JsonArray survey = new JsonArray();
        if (opening) for (JsonElement r : arr(c.get("survey"))) {
            if (survey.size() >= SURVEY_ROWS) { dropped.add("survey"); break; }
            JsonObject x = surveyRow(r, players, hid, dropped);
            if (x != null) survey.add(x);
        } else if (c.has("survey")) dropped.add("survey");
        if (survey.size() > 0) {
            cons.add("survey", survey);
            cons.addProperty("survey_builds", survey.size());
        }

        List<BlotterFacts.Entry> blot = new ArrayList<>();
        for (JsonElement b : arr(in.get("blotter"))) {
            if (!(b instanceof JsonObject o)) continue;
            String by = str(o.get("by")), to = str(o.get("to")), x = str(o.get("x"));
            Integer n = count(o.get("n"));
            if (!"broke_build".equals(str(o.get("what"))) || !player(by, players, hid) || !player(to, players, hid) || by.equals(to) || n == null || n < 1
                    || x == null || !KIND_DISPLAY.contains(x)) { dropped.add("blotter"); continue; }
            BlotterFacts.Entry en = new BlotterFacts.Entry("broke_build", by, to, x, false);
            en.n = n;
            en.weight = BlotterFacts.KINDS.get("broke_build").weight() + Math.min(n, 64) / 4.0;
            blot.add(en);
        }
        Set<String> drop = new HashSet<>();
        for (JsonElement d : arr(in.get("drop_mined_at"))) {
            JsonArray a = arr(d);
            if (a.size() == 2 && str(a.get(0)) != null && str(a.get(1)) != null) drop.add(str(a.get(0)) + "\t" + str(a.get(1)));
        }
        List<Awards.Extra> awards = new ArrayList<>();
        for (JsonElement r : arr(in.get("award_reasons"))) {
            if (!(r instanceof JsonObject o)) continue;
            String award = str(o.get("award")), name = str(o.get("name")), text = str(o.get("text"));
            Integer score = count(o.get("score"));
            if (!Set.of("golden_egg", "headless_chicken").contains(award) || !player(name, players, hid) || score == null || score > 50 || text == null
                    || !AWARD_TEXT.matcher(text).matches()) { dropped.add("award_reasons"); continue; }
            awards.add(new Awards.Extra(award, name, score, text));
        }
        boolean any = opening || entries.size() > 0 || tracker.size() > 0;
        return new Result(any ? cons : null, blot, drop, awards, dropped);
    }

    private static JsonObject entry(JsonElement e, Set<String> players, Set<String> hid, List<String> dropped) {
        if (!(e instanceof JsonObject in)) return null;
        String tag = str(in.get("tag")), kind = str(in.get("kind"));
        Integer permit = count(in.get("permit"));
        JsonArray builders = names(in.get("builders"), players, hid, 1);
        if (tag == null || !TAGS.contains(tag) || permit == null || permit < 1 || permit > 999_999 || builders == null || kind == null || !KINDS.contains(kind)) {
            dropped.add("entry");
            return null;
        }
        boolean existing = bool(in.get("existing"));
        if (existing && tag.equals("new_resident")) { dropped.add("entry"); return null; }
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> kv : in.entrySet()) {
            String k = kv.getKey();
            JsonElement v = kv.getValue();
            if (!ENTRY_KEYS.contains(k) || BANNED_KEY.matcher(k).matches()) { dropped.add(k); continue; }
            JsonElement ok = value(k, v, kind, players, hid);
            if (ok == null) { dropped.add(k); continue; }
            out.add(k, ok);
        }
        if (existing) for (String k : List.of("size", "height", "megaproject")) if (out.remove(k) != null) dropped.add(k);
        if (count(out.get("day")) != null && count(out.get("day")) == 1 && out.remove("size") != null) dropped.add("size");
        // the angle must name a field the entry still carries
        String angle = str(out.get("angle"));
        boolean carried = angle == null || switch (angle) {
            case "day" -> true;
            case "crew" -> out.getAsJsonArray("builders").size() >= 2;
            case "milestone" -> out.has("milestone_blocks");
            case "materials" -> out.has("materials") && !out.getAsJsonArray("materials").isEmpty();
            default -> out.has(angle);   // size, parts, incident
        };
        if (!carried) out.addProperty("angle", "day");
        // One story per entry. The writer prints every field it is sent, so an entry with size, height, materials, parts and
        // features came out as a list ("large, tall, 40 hoppers"). The size words go only when size is
        // the story; the materials only when they are the story or nothing else says what is inside.
        String told = str(out.get("angle"));
        if (!"size".equals(told)) { out.remove("size"); out.remove("height"); }
        if (!"materials".equals(told) && (out.has("parts") || out.has("features"))) out.remove("materials");
        return out;
    }

    private static JsonObject row(JsonElement e, Set<String> players, Set<String> hid, List<String> dropped) {
        if (!(e instanceof JsonObject in)) return null;
        String kind = str(in.get("kind"));
        Integer permit = count(in.get("permit"));
        if (permit == null || permit < 1 || kind == null || !KINDS.contains(kind) || names(in.get("builders"), players, hid, 1) == null) { dropped.add("tracker"); return null; }
        boolean critic = bool(in.get("critic")), existing = bool(in.get("existing"));
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> kv : in.entrySet()) {
            String k = kv.getKey();
            boolean allowed = ROW_KEYS.contains(k) || (critic && CRITIC_KEYS.contains(k));
            if (!allowed || BANNED_KEY.matcher(k).matches() || (existing && (k.equals("size") || k.equals("height")))) { dropped.add(k); continue; }
            JsonElement ok = value(k, kv.getValue(), kind, players, hid);
            if (ok == null) { dropped.add(k); continue; }
            out.add(k, ok);
        }
        if (count(out.get("day")) != null && count(out.get("day")) == 1 && out.remove("size") != null) dropped.add("size");
        return out;
    }

    /** A Town Survey row: permit, builders, kind and blocks_total required; name, features and existing optional; nothing else. */
    private static JsonObject surveyRow(JsonElement e, Set<String> players, Set<String> hid, List<String> dropped) {
        if (!(e instanceof JsonObject in)) { dropped.add("survey"); return null; }
        String kind = str(in.get("kind"));
        Integer permit = count(in.get("permit"));
        if (permit == null || permit < 1 || permit > 999_999 || kind == null || !KINDS.contains(kind) || names(in.get("builders"), players, hid, 1) == null
                || count(in.get("blocks_total")) == null) { dropped.add("survey"); return null; }
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> kv : in.entrySet()) {
            String k = kv.getKey();
            JsonElement ok = SURVEY_KEYS.contains(k) && !BANNED_KEY.matcher(k).matches() ? value(k, kv.getValue(), kind, players, hid) : null;
            if (ok == null) { dropped.add(k); continue; }
            out.add(k, ok);
        }
        return out;
    }

    /** One value checked against its key's rule; null = not allowed. */
    private static JsonElement value(String k, JsonElement v, String kind, Set<String> players, Set<String> hid) {
        switch (k) {
            case "tag" -> { return v; }
            case "kind" -> { return new JsonPrimitive(Kinds.display(kind)); }   // the writer sees "auto farm", never an id with an underscore
            case "permit" -> { Integer n = count(v); return n != null && n >= 1 && n <= 999_999 ? v : null; }
            case "name" -> {
                if (v == null || v.isJsonNull()) return v;
                String s = str(v);
                Set<String> known = new java.util.HashSet<>(players);
                known.addAll(hid);   // never a hidden player's name inside a build name
                return s != null && s.equals(NameRules.check(s, known, "clean")) ? v : null;
            }
            case "builders" -> { return names(v, players, hid, 1); }
            case "new_builders" -> { return names(v, players, hid, 0); }
            case "opening", "megaproject", "critic", "existing" -> { return v instanceof JsonPrimitive p && p.isBoolean() ? v : null; }
            case "confidence" -> { return CONFIDENCE.contains(str(v)) ? v : null; }
            case "size" -> { return SIZE.contains(str(v)) ? v : null; }
            case "height" -> { return HEIGHT.contains(str(v)) && !Kinds.FLAT.contains(kind) ? v : null; }
            case "angle" -> { return ANGLE.contains(str(v)) ? v : null; }
            case "milestone_blocks" -> { Integer n = count(v); return n != null && MILESTONES.contains(n) ? v : null; }
            case "materials" -> {
                JsonArray out = new JsonArray();
                for (JsonElement m : arr(v)) { JsonObject x = mat(m); if (x == null) return null; out.add(x); }
                return out.size() <= 3 && v.isJsonArray() ? out : null;
            }
            case "odd_material" -> { return mat(v); }
            case "parts" -> {
                if (!(v instanceof JsonObject o)) return null;
                JsonObject out = new JsonObject();
                for (Map.Entry<String, JsonElement> p : o.entrySet()) if (PARTS.contains(p.getKey()) && count(p.getValue()) != null) out.add(p.getKey(), p.getValue());
                return out.size() == o.size() ? out : null;
            }
            case "features" -> {
                // closed ids and positive counts only; the writer gets the plain words ("lecterns", "librarian trading"), never an id
                if (!(v instanceof JsonArray a) || a.isEmpty() || a.size() > Features.MAX) return null;
                JsonArray out = new JsonArray();
                for (JsonElement f : a) {
                    String[] w = f instanceof JsonObject o && o.size() == 2 ? Features.WORDS.get(str(o.get("feature"))) : null;
                    Integer n = w == null ? null : count(((JsonObject) f).get("n"));
                    if (n == null || n < 1) return null;
                    JsonObject x = new JsonObject();
                    x.addProperty("n", n);
                    x.addProperty("of", Features.counted(str(((JsonObject) f).get("feature")), n));
                    if (w[1] != null) x.addProperty("for", w[1]);
                    out.add(x);
                }
                return out;
            }
            case "incident" -> {
                if (!(v instanceof JsonObject o) || o.size() != 3 || !CAUSE.contains(str(o.get("cause"))) || count(o.get("blocks")) == null
                        || !(o.get("self") instanceof JsonPrimitive p && p.isBoolean())) return null;
                return o;
            }
            default -> { return COUNTS.contains(k) && count(v) != null ? v : null; }
        }
    }

    private static JsonObject mat(JsonElement m) {
        if (!(m instanceof JsonObject o) || o.size() != 2) return null;
        String what = str(o.get("what"));
        return what != null && WHAT.matcher(what).matches() && Families.allowed(what) && count(o.get("blocks")) != null ? o : null;
    }

    /** 1-6 (or 0-6) real, visible player names, sorted alphabetically; null if anything is off. */
    private static JsonArray names(JsonElement v, Set<String> players, Set<String> hid, int min) {
        if (v == null || !v.isJsonArray()) return null;
        JsonArray a = v.getAsJsonArray();
        if (a.size() < min || a.size() > 6) return null;
        String prev = null;
        for (JsonElement e : a) {
            String n = str(e);
            if (!player(n, players, hid)) return null;
            if (prev != null && String.CASE_INSENSITIVE_ORDER.compare(prev, n) > 0) return null;
            prev = n;
        }
        return a;
    }

    private static boolean player(String n, Set<String> players, Set<String> hid) {
        return n != null && NAME.matcher(n).matches() && players.contains(n) && !hid.contains(n.toLowerCase(Locale.ROOT));
    }

    /** An int 0-10,000,000, or null. */
    private static Integer count(JsonElement v) {
        if (!(v instanceof JsonPrimitive p) || !p.isNumber()) return null;
        double d = p.getAsDouble();
        return d == Math.rint(d) && d >= 0 && d <= 10_000_000 ? (int) d : null;
    }

    private static boolean bool(JsonElement v) { return v instanceof JsonPrimitive p && p.isBoolean() && p.getAsBoolean(); }

    private static String str(JsonElement v) { return v instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null; }

    private static JsonArray arr(JsonElement v) { return v != null && v.isJsonArray() ? v.getAsJsonArray() : new JsonArray(); }

    /** For tests: the filter on text. */
    static Result filter(String json, Set<String> players, Set<String> hidden, boolean weekly, int maxEntries) {
        return filter(JsonParser.parseString(json).getAsJsonObject(), players, hidden, weekly, maxEntries, new ArrayList<>());
    }
}
