package io.github.eastmadc.masthead.edition;

import io.github.eastmadc.masthead.config.Settings;
import io.github.eastmadc.masthead.construction.Features;
import io.github.eastmadc.masthead.construction.Kinds;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The draft checks construction news adds (only when it is on and printing). Gate C: anything shaped like a position,
 * anywhere in the paper. Gate W: place, judging and ranking words in anything about a build. Problems join the fact check's
 * list, so the usual "one rewrite, then the plain edition" applies; a problem only inside the section can instead drop it.
 */
public final class ConstructionGate {
    private ConstructionGate() {}

    /** Gate C's number shapes (labelled only "coordinates": the match itself is never echoed). */
    private static final List<Pattern> NUMERIC = List.of(
            Pattern.compile("(?i)\\b[xyz]\\s*[:=]\\s*-?\\d{1,8}\\b"),
            Pattern.compile("(?<![\\d.,])(?<!\\d\\s)\\b[xXzZ]\\s?-?\\d{1,8}\\b"),
            Pattern.compile("\\(\\s*-?\\d{1,8}\\s*,\\s*-?\\d{1,8}(?:\\s*,\\s*-?\\d{1,8})?\\s*\\)"),
            Pattern.compile("(?<![\\w.,])-\\d{1,8}\\s*,\\s*-?\\d{1,8}|\\b\\d{1,8}\\s*,\\s*-\\d{1,8}"),
            Pattern.compile("(?i)\\bat\\s+-?\\d{1,8}\\s*,?\\s+-?\\d{1,8}\\b"),
            Pattern.compile("\\bY\\s?-?\\d{1,4}\\b"),
            Pattern.compile("-?\\d{1,8}\\s*/\\s*-?\\d{1,8}\\s*/\\s*-?\\d{1,8}"),
            Pattern.compile("(?<![\\d.,])-\\d{1,8}\\s+-?\\d{1,8}\\b|(?<![\\d.,])\\b\\d{1,8}\\s+-\\d{1,8}\\b"),
            GateC.WORD_COORD);
    /** Gate C's keywords (chunk, coords, Y level): safe to echo, they are dictionary words. */
    private static final Pattern KEYWORDS = ConstructionWords.regex(ConstructionWords.GATE_C, false);
    private static final List<String> WORDS = ConstructionWords.gateWords();
    private static final Pattern GATE_W = ConstructionWords.regex(WORDS, false);
    /**
     * Kind and feature words the paper prints exactly as given, even with a gate word inside ("nether portal", "mushroom farm").
     * Masked before Gate W only when today's facts carry them at this detail level, and only as the thing itself (see
     * {@link #thing}), so the word alone ("near the nether") and the phrase as a landmark ("at the nether portal") are caught.
     */
    public static final List<String> ALLOWED = allowed();
    /** Before an allowed phrase used as a place: "at a nether portal" is a landmark even with "a". */
    private static final String PLACE_BEFORE = "(?<!\\b(?:at|near|by|beside|over|under|into|onto|inside|behind|above|below|next to|toward|towards|around|past"
            + "|from|off|across|through|beyond|within|atop|along|against|outside)\\s)";
    private static final Pattern GATE_W_CASE = ConstructionWords.regex(ConstructionWords.CASE_SENSITIVE, true);
    private static final Pattern SECTION = ConstructionWords.regex(ConstructionWords.SECTION_ONLY, false);
    private static final Pattern BLOCK = Pattern.compile("(?is)<(h2|h3|p|li|td)\\b[^>]*>(.*?)</\\1\\s*>");
    private static final Pattern UNDERSCORE = Pattern.compile("(?<![A-Za-z0-9_])[a-z0-9]+(?:_[a-z0-9]+)+(?![A-Za-z0-9_])");
    private static final Pattern H = Pattern.compile("(?is)<(h2|h3)\\b[^>]*>(.*?)</\\1\\s*>");

    /** The fix text added to the rewrite request when construction news prints. */
    public static final String REWRITE_NOTE = "Problems starting with \"construction\", and \"coordinates\" problems that show a word in brackets, quote "
            + "the exact words to remove. A \"coordinates\" problem with no quoted word means a number written in one of the shapes the hard lines "
            + "forbid (a number right after x, y or z, three numbers joined by slashes, the word at followed by two numbers, numbers in parentheses "
            + "or with minus signs): rewrite every number next to its word. Coordinates, place words and judging or ranking words are not allowed "
            + "in anything about a build. A \"construction mention in\" problem names the heading of the part where it was found; fix that "
            + "sentence there (it mentions a builder together with a build). Describe builds only through their fields.";

    public static boolean on(Settings s) {
        return s != null && s.features().construction() && s.construction().print();
    }

    /** Every known player name in the facts (season_totals), for masking. */
    static List<String> names(JsonObject data) {
        List<String> out = new ArrayList<>();
        for (String n : PyJson.objOf(data, "season_totals").keySet()) if (Py.isName(n)) out.add(n);
        return out;
    }

    /** Each known name, as a whole word and in any case, becomes "Player", so a name never trips a check. */
    public static String mask(String text, Collection<String> names) {
        List<String> sorted = new ArrayList<>(names);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String n : sorted)
            text = Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(n) + "(?![A-Za-z0-9_])").matcher(text).replaceAll("Player");
        return text;
    }

    /** Gate C on plain text (names already masked): no problem, "coordinates", or coordinates ("chunk"). */
    public static List<String> coordinates(String text) {
        List<String> out = new ArrayList<>();
        for (Pattern p : NUMERIC) if (p.matcher(text).find()) { out.add("coordinates"); break; }
        LinkedHashSet<String> kw = new LinkedHashSet<>();
        Matcher m = KEYWORDS.matcher(text);
        while (m.find()) kw.add(ConstructionWords.phraseOf(m.group(), ConstructionWords.GATE_C));
        if (!kw.isEmpty()) out.add("coordinates (" + quoted(kw) + ")");
        return out;
    }

    /** Both gates on a draft. */
    public static List<String> problems(JsonObject data, String html, Settings s) {
        List<String> names = names(data);
        String title = s.construction().title();
        List<String> out = new ArrayList<>();   // Gate C itself runs on every draft (FactCheck.textProblems), so not again here
        Pattern thing = thing(data, s.construction().detail());
        int[] sec = section(html, title);
        if (sec != null) {
            String t = mask(text(html.substring(sec[0], sec[1])), names);
            LinkedHashSet<String> hits = words(t, true, thing);
            if (!hits.isEmpty()) out.add("construction section (" + quoted(hits) + ")");
        }
        out.addAll(mentions(data, html, sec, s, names, thing));
        return out;
    }

    /**
     * The allowed phrases a draft may use today, as the thing itself: right after a, an, for or a count ("a nether portal",
     * "18 obsidian blocks for nether portal", "1 cave spider spawner") and not after a place word. Only phrases in today's
     * entries or survey rows that print at this detail level; null when there are none.
     */
    static Pattern thing(JsonObject data, String detail) {
        java.util.Set<String> today = new java.util.HashSet<>();
        JsonObject cons = PyJson.objOf(data, "construction");
        for (String key : List.of("entries", "survey"))
            for (JsonElement e : PyJson.arr(cons.get(key))) {
                JsonObject o = PyJson.obj(e);
                String k = PyJson.strOr(o.get("kind"), null);
                if (k != null) today.add(Kinds.display(k));
                for (JsonElement f : PyJson.arr(o.get("features"))) {
                    today.add(PyJson.strOr(PyJson.obj(f).get("of"), ""));
                    today.add(PyJson.strOr(PyJson.obj(f).get("for"), ""));
                }
            }
        List<String> use = new ArrayList<>();
        for (String p : ALLOWED) if (today.contains(p) && printsAt(p, detail)) use.add(p);
        if (use.isEmpty()) return null;
        return Pattern.compile("(?<![A-Za-z0-9_])" + PLACE_BEFORE + "((?:an?|for|\\d[\\d,]*)\\s+)" + ConstructionWords.regex(use, false).pattern(),
                Pattern.CASE_INSENSITIVE);
    }

    /** Whether an allowed phrase (a kind's or a feature's words) prints at a construction.detail level. */
    private static boolean printsAt(String phrase, String detail) {
        for (String k : Kinds.ALL) if (Kinds.display(k).equals(phrase)) return k.equals(Kinds.shown(k, detail));
        for (Map.Entry<String, String[]> e : Features.WORDS.entrySet())
            if (phrase.equals(e.getValue()[0]) || phrase.equals(e.getValue()[1]) || phrase.equals(Features.counted(e.getKey(), 1)))
                return !Features.shown(List.of(new Features.F(e.getKey(), 1)), detail).isEmpty();
        return false;
    }

    /** Scope (b): construction copy outside the section. */
    private static List<String> mentions(JsonObject data, String html, int[] sec, Settings s, List<String> names, Pattern thing) {
        JsonObject cons = PyJson.objOf(data, "construction");
        List<String> builders = new ArrayList<>(), printed = new ArrayList<>();
        List<List<String>> triggers = new ArrayList<>();   // per builder: the trigger words of their entries
        for (JsonElement e : PyJson.arr(cons.get("entries"))) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String name = PyJson.pyStr(o.get("name"));
            if (o.get("name") != null && !o.get("name").isJsonNull()) printed.add(name);
            List<String> trig = new ArrayList<>(ConstructionWords.BUILD_VERBS);
            String kw = kindWord(PyJson.pyStr(o.get("kind")), StylePrompt.chickenTheme(s));
            if (kw != null) trig.add(kw);
            for (JsonElement b : PyJson.arr(o.get("builders"))) { builders.add(PyJson.pyStr(b)); triggers.add(trig); }
        }
        if (cons.isEmpty()) return List.of();
        String others = otherStrings(data);
        String normTitle = ConstructionWords.normalizeHeading(s.construction().title());
        Pattern always = Pattern.compile("(?i)(?<![A-Za-z0-9_])(?:permit|commission)(?![A-Za-z0-9_])");
        List<String> out = new ArrayList<>();
        String heading = "Front page";
        Matcher m = BLOCK.matcher(html);
        while (m.find()) {
            String tag = m.group(1).toLowerCase(Locale.ROOT);
            if (tag.equals("h2") || tag.equals("h3")) {
                String h = ConstructionWords.normalizeHeading(m.group(2)).replaceAll("\\d", "").replaceAll("\\s+", " ").strip();
                heading = h.length() > 40 ? h.substring(0, 40) : h;   // and the heading's own words are checked like a block
            }
            if (sec != null && m.start() >= sec[0] && m.start() < sec[1]) continue;
            String raw = m.group(2);
            String plain = text(raw);
            List<String> check = new ArrayList<>();
            boolean whole = always.matcher(plain).find() || ConstructionWords.normalizeHeading(plain).contains(normTitle);
            for (String n : printed) if (!n.isEmpty() && plain.toLowerCase(Locale.ROOT).contains(n.toLowerCase(Locale.ROOT))) whole = true;
            if (whole) check.add(plain);
            else {
                List<String> sentences = sentences(raw);
                for (int i = 0; i < builders.size(); i++) {
                    if (!hasWord(plain, builders.get(i))) continue;
                    Pattern trig = ConstructionWords.regex(triggers.get(i), false);
                    for (String sentence : sentences) {
                        boolean verb = hasWord(sentence, builders.get(i)) && Pattern.compile("(?i)\\b(?:placed|placing|blocks)\\b").matcher(sentence).find();
                        if (trig.matcher(sentence).find() || verb) check.add(sentence);
                    }
                }
            }
            LinkedHashSet<String> hits = new LinkedHashSet<>();
            for (String c : check) hits.addAll(words(mask(c, names), false, thing));
            hits.removeIf(w -> ConstructionWords.regex(List.of(w), w.equals("the End")).matcher(others).find());   // "Hero of the Village", lore, death lines
            if (!hits.isEmpty()) out.add("construction mention in \"" + heading + "\" (" + quoted(hits) + ")");
        }
        return out;
    }

    /**
     * The kind's trigger word (from its id or the facts' display words); on a chicken-theme paper "farm" is the whole server,
     * so it is not one, nor is "chicken farm"; "base" and "nether portal" never are ("back to base", "jumped through the nether
     * portal" are ordinary prose in deaths and advancements). Other kinds are specific enough ("library" only counts in a
     * sentence that also names that build's builder).
     */
    static String kindWord(String kind, boolean chicken) {
        String d = Kinds.display(kind);
        return switch (d) {
            case "farm", "auto farm" -> chicken ? null : "farm";
            case "animal pen" -> "pen";
            case "redstone machine" -> "redstone";
            case "chicken farm" -> chicken ? null : d;
            case "base", "nether portal" -> null;
            default -> d;   // "slime farm", not "slime": the mob is in kill lists
        };
    }

    private static List<String> allowed() {
        List<String> all = new ArrayList<>();
        for (String k : Kinds.ALL) all.add(Kinds.display(k));
        for (Map.Entry<String, String[]> e : Features.WORDS.entrySet()) {
            String[] w = e.getValue();
            all.add(w[0]);
            all.add(Features.counted(e.getKey(), 1));
            if (w[1] != null) all.add(w[1]);
        }
        List<String> out = new ArrayList<>();
        for (String p : all) if (GATE_W.matcher(p).find() && !out.contains(p)) out.add(p);
        return List.copyOf(out);
    }

    private static boolean hasWord(String text, String name) {
        return Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])").matcher(text).find();
    }

    /** Gate W words in text; inSection also checks "small". thing = today's allowed phrases (null: none). */
    private static LinkedHashSet<String> words(String text, boolean inSection, Pattern thing) {
        LinkedHashSet<String> hits = new LinkedHashSet<>();
        if (thing != null) text = thing.matcher(text).replaceAll("$1Kind");
        Matcher m = GATE_W.matcher(text);
        while (m.find()) hits.add(ConstructionWords.phraseOf(m.group(), WORDS));
        if (GATE_W_CASE.matcher(text).find()) hits.add("the End");
        if (inSection) {
            Matcher sm = SECTION.matcher(text);
            while (sm.find()) hits.add(ConstructionWords.phraseOf(sm.group(), ConstructionWords.SECTION_ONLY));
            Matcher um = UNDERSCORE.matcher(text);   // an id like auto_farm or smooth_stone_slab (names are already masked)
            while (um.find()) hits.add(um.group());
        }
        return hits;
    }

    /** Every other string in the facts, whose words are exempt outside the section. */
    private static String otherStrings(JsonObject data) {
        StringBuilder b = new StringBuilder();
        collect(data, b, true);
        return b.toString();
    }

    private static void collect(JsonElement e, StringBuilder b, boolean top) {
        if (e == null || e.isJsonNull()) return;
        if (e.isJsonObject()) {
            for (var en : e.getAsJsonObject().entrySet()) if (!(top && (en.getKey().equals("construction") || en.getKey().equals("mailbag_candidates")))) collect(en.getValue(), b, false);
        } else if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) collect(x, b, false);
        else if (e.getAsJsonPrimitive().isString()) b.append(e.getAsString()).append('\n');
    }

    /** Sentences of an element's inner HTML: split on . ! ? ; (then a space or the end) and on br. */
    public static List<String> sentences(String innerHtml) {
        List<String> out = new ArrayList<>();
        for (String part : innerHtml.split("(?i)<br\\s*/?>")) {
            for (String s : text(part).split("(?<=[.!?;])(?:\\s+|$)")) if (!s.isBlank()) out.add(s.strip());
        }
        return out;
    }

    private static String text(String html) {
        return HtmlClean.checkText(html).replaceAll("\\s+", " ").strip();
    }

    /**
     * The construction section in a draft: from the first h3 whose heading contains the title, to the next h2 or the next h3
     * that is not the Sunday table or the critic. {start, end}, or null.
     */
    static int[] section(String html, String title) {
        String want = ConstructionWords.normalizeHeading(title);
        if (want.isEmpty()) return null;
        Matcher m = H.matcher(html);
        int start = -1;
        while (m.find()) {
            String h = ConstructionWords.normalizeHeading(m.group(2));
            if (start < 0) {
                if (m.group(1).equalsIgnoreCase("h3") && h.contains(want)) start = m.start();
                continue;
            }
            if (m.group(1).equalsIgnoreCase("h2") || !(h.contains("permit tracker") || h.contains("architecture critic"))) return new int[]{start, m.start()};
        }
        return start < 0 ? null : new int[]{start, html.length()};
    }

    /** Whether a problem is about the construction section only (then dropping the section fixes it). */
    public static boolean sectionOnly(String problem) {
        return problem.startsWith("construction section");
    }

    /** The draft without its construction section (the same bounds the gate checked). */
    public static String dropSection(String html, String title) {
        int[] sec = section(html, title);
        return sec == null ? html : html.substring(0, sec[0]) + html.substring(sec[1]);
    }

    /** A paragraph or list item about a build: a permit, or a construction award reason (Projector's wording). */
    private static final Pattern BUILD_COPY = Pattern.compile("(?is)<(p|li)\\b[^>]*>(?:(?!</\\1\\s*>).)*?(?:permit\\s*#|days of building|one build"
            + "|first home|blew up part of their own)(?:(?!</\\1\\s*>).)*?</\\1\\s*>");

    /**
     * An older paper without its build stories (the section, and build lines outside it): a builder may have gone private or
     * secret, been forgotten, or had the name cleared since that paper ran. Used wherever an old paper is read again.
     */
    public static String withoutBuilds(String html, String title) {
        return BUILD_COPY.matcher(dropSection(html, title)).replaceAll(" ");
    }

    /**
     * Section-only recovery: when every problem left after the rewrite is inside the section, the draft without it, else
     * null (the plain edition, as before).
     */
    public static String recover(List<String> problems, String html, String title) {
        if (problems.isEmpty() || !problems.stream().allMatch(ConstructionGate::sectionOnly)) return null;
        return dropSection(html, title);
    }

    private static String quoted(Collection<String> words) {
        List<String> q = new ArrayList<>();
        for (String w : words) q.add("\"" + w + "\"");
        return String.join(", ", q);
    }

    /** For the notes: the words of the section problems ("construction section dropped (...)"). */
    public static String words(List<String> problems) {
        Set<String> w = new LinkedHashSet<>();
        for (String p : problems) { int a = p.indexOf('('), b = p.lastIndexOf(')'); if (a >= 0 && b > a) w.add(p.substring(a + 1, b)); }
        return String.join(", ", w);
    }
}
