package io.github.eastmadc.masthead.edition;

import io.github.eastmadc.masthead.config.Settings;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fact check run on every AI draft (port of check / numbers_in / facts): numbers with 3+ digits that are not in the
 * data, and banned phrases (tired filler, times of day, weekday names, and the chicken puns when the watch animal is
 * the chicken). An empty list = the draft is fine.
 */
public final class FactCheck {
    private FactCheck() {}

    /** Filler, times of day and weekday names: banned for every paper. */
    public static final List<String> BANNED_ALWAYS = List.of("the grind is real", "different frequency", "another level",
            "absolutely cooked", "chaotic energy", "locked in", "mad lad", "unhinged",
            // computed math the style forbids ("once every 2.4 seconds", "15 times more"); found in the first live plugin test
            "once every", "times more", "times as many", "% of",
            // school ("like homework done right" got through)
            "homework", "teacher", "classroom", "report card", "detention");
    /** Banned in the paper only (not in quoted mail): repeated tics the round-3 judges called teacher voice. */
    public static final List<String> BANNED_EDITION = List.of("home turf", "farm hands",
            // two Blotter lines are two facts: never the same animal or chest, never in order
            "that same", "the same horse", "before the owner",
            // grown-up life and the adult newsroom (matched after accents are dropped, so "résumé" too)
            "resume", "payroll", "office", "bureau", "lawyer", "court", "commute", "retirement", "committee", "town council",
            "for the record", "on the record");
    /** The truth part of BANNED_EDITION, kept for every voice. */
    static final List<String> BLOTTER_ORDER = List.of("that same", "the same horse", "before the owner");
    /** Banned only when paper.watchAnimal is "chicken". */
    public static final List<String> BANNED_CHICKEN = List.of("egg-cellent", "eggs-actly", "egg-cited", "egg-straordinary", "fowl play",
            "poultry in motion", "hen-sational", "peck-uliar", "winner winner", "cross the road");
    public static final List<String> BANNED_TIME = List.of("no cap fr", "fr fr", "before breakfast", "by morning", "overnight",
            "all night", "late night", "bedtime", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            // the data is a 24-hour window, not play sessions
            "in one session", "in a single session", "in one sitting", "in a single sitting", "in one go", "in a single go", "in one night");
    /** Clock times ("3am", "11:30 pm", "at 10:45"): a time of day in any form. */
    static final Pattern CLOCK = Pattern.compile("(?i)\\b\\d{1,2}(?::\\d{2})?\\s?[ap]\\.?m\\b\\.?|\\b\\d{1,2}:\\d{2}\\b|\\b(?:midnight|noon|o'clock)\\b");

    /** The banned list in production order for this paper. */
    public static List<String> bannedPhrases(Settings s) {
        List<String> out = new ArrayList<>(BANNED_ALWAYS);
        if (ClassicPrompt.heavy(s)) { out.remove("locked in"); out.remove("unhinged"); }   // heavy slang is the readers' own words
        if (StylePrompt.chicken(s)) out.addAll(BANNED_CHICKEN);
        out.addAll(BANNED_TIME);
        return out;
    }

    /**
     * The data as the writers may see it (facts()): no mailroom letters, no reader names and no cooldown word list. Returns a shallow copy;
     * the input is not changed.
     */
    public static JsonObject facts(JsonObject data) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : data.entrySet()) {
            if (e.getKey().equals("mailbag_candidates") || e.getKey().equals("recent_words")) continue;   // recent_words: the cooldown, not news
            if (e.getKey().equals("circulation") && e.getValue().isJsonObject()) {
                JsonObject c = new JsonObject();
                for (Map.Entry<String, JsonElement> ce : e.getValue().getAsJsonObject().entrySet())
                    if (!ce.getKey().equals("who")) c.add(ce.getKey(), ce.getValue());
                out.add("circulation", c);
                continue;
            }
            if (e.getKey().equals("construction") && e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("survey")) {
                JsonObject c = e.getValue().getAsJsonObject().deepCopy();   // the Town Survey's rows are for its page; the writer gets survey_builds
                c.remove("survey");
                out.add("construction", c);
                continue;
            }
            out.add(e.getKey(), e.getValue());
        }
        return out;
    }

    private static final Pattern NUM_IN_STR = Pattern.compile("\\d[\\d,.]*", Pattern.UNICODE_CHARACTER_CLASS);
    static final Pattern NUM_IN_TEXT = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?", Pattern.UNICODE_CHARACTER_CLASS);
    /** A count cut short: "90k", "90 thousand", "2.5 million". Not "5km", "2kg" or "y2k". */
    private static final Pattern SHORT_NUM = Pattern.compile("\\b(\\d[\\d,]*(?:\\.\\d+)?)(?:k|[\\s-]*(thousand|million))\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /** numbers_in(obj, acc): every number in the data, plus its rounded / per-60 / per-1000 (also as "90k") / per-1440 / per-million forms, as strings. */
    public static Set<String> numbersIn(JsonElement obj, Set<String> acc) {
        if (obj == null || obj.isJsonNull()) return acc;
        if (obj.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject().entrySet()) numbersIn(e.getValue(), acc);
        } else if (obj.isJsonArray()) {
            for (JsonElement e : obj.getAsJsonArray()) numbersIn(e, acc);
        } else {
            JsonPrimitive p = obj.getAsJsonPrimitive();
            if (p.isBoolean()) return acc;
            if (p.isNumber()) {
                boolean f = PyJson.isFloat(p);
                double d = p.getAsDouble();
                String first = f ? Py.repr(d) : PyJson.bigInt(p).toString();
                add(acc, first);
                add(acc, f ? Py.roundInt(d).toString() : PyJson.bigInt(p).toString());   // round(int) is the int itself
                add(acc, Py.roundInt(d / 60).toString());
                add(acc, Py.repr(Py.round(d / 60, 1)));
                add(acc, Py.roundInt(d / 1000).toString());
                add(acc, Py.repr(Py.round(d / 1000, 1)));
                acc.add(trim(Py.roundInt(d / 1000).toString()) + "k");       // what "90k" in the text is compared with
                acc.add(trim(Py.repr(Py.round(d / 1000, 1))) + "k");
                add(acc, Py.repr(Py.round(d / 1440, 1)));
                if (Math.abs(d) >= 1e6) {   // "2.5 million" and "3 million" for a real 2512345, as bigNumbers reads them
                    acc.add(java.math.BigDecimal.valueOf(Py.round(d / 1e6, 1)).movePointRight(6).toPlainString());
                    acc.add(Py.roundInt(d / 1e6) + "000000");
                }
            } else {
                Matcher m = NUM_IN_STR.matcher(p.getAsString());
                while (m.find()) acc.add(rstripDots(m.group().replace(",", "")));
                shortNumbers(p.getAsString(), acc);   // "12k" in the owner's lore is a number the paper may repeat
            }
        }
        return acc;
    }

    private static void add(Set<String> acc, String s) {
        acc.add(trim(s));
    }

    /** "90.0" -> "90", "0.60" -> "0.6": a decimal without its trailing zeros. */
    private static String trim(String s) {
        if (s.indexOf('.') >= 0) {
            int e = s.length();
            while (e > 0 && s.charAt(e - 1) == '0') e--;
            s = s.substring(0, e);
            e = s.length();
            while (e > 0 && s.charAt(e - 1) == '.') e--;
            s = s.substring(0, e);
        }
        return s;
    }

    /** Counts cut short in text: "90k" and "90 thousand" as "90k", "2.5 million" as 2500000. */
    private static void shortNumbers(String text, Set<String> out) {
        for (Matcher m = SHORT_NUM.matcher(text); m.find(); ) {
            String x = m.group(1).replace(",", "");
            out.add("million".equalsIgnoreCase(m.group(2)) ? trim(new java.math.BigDecimal(x).movePointRight(6).toPlainString()) : trim(x) + "k");
        }
    }

    static String rstripDots(String s) {
        int e = s.length();
        while (e > 0 && s.charAt(e - 1) == '.') e--;
        return s.substring(0, e);
    }

    /**
     * 3+ digit numbers in text (commas removed), as check() and the mailroom see them. HTML entities are decoded first
     * (so "&#49;&#50;&#48;&#48;" is 1200, as the reader sees it), and numbers written in words count too ("seven thousand
     * three hundred forty-two" is 7342). A count cut short counts whatever its length: "90k" and "90 thousand" give "90k",
     * which only the data's per-1000 forms match (so "0.6k" for a real 600 passes and "90k" for a real 90 does not),
     * "2.5 million" gives 2500000 (known for a real 2512345: the data's rounded millions are known numbers).
     */
    static Set<String> bigNumbers(String text) {
        text = Py.unescape(text);
        Set<String> out = new TreeSet<>();
        Matcher m = NUM_IN_TEXT.matcher(text);
        while (m.find()) {
            String x = m.group().replace(",", "");
            int dot = x.indexOf('.');
            String ip = dot < 0 ? x : x.substring(0, dot);
            if (Py.len(ip) >= 3) out.add(rstripDots(x));
        }
        for (long v : wordNumbers(text)) if (v >= 100) out.add(Long.toString(v));
        shortNumbers(text, out);
        return out;
    }

    private static final Map<String, Integer> SMALL = new java.util.HashMap<>();
    private static final Map<String, Long> SCALE = Map.of("thousand", 1_000L, "million", 1_000_000L, "billion", 1_000_000_000L);
    private static final Pattern WORD = Pattern.compile("[a-z]+");
    private static final Pattern A_COUNT = Pattern.compile("[\\s-]+(?:hundred|thousand|million|billion)\\b");
    static {
        String[] u = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
                "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
        for (int i = 0; i < u.length; i++) SMALL.put(u[i], i);
        String[] t = {"twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
        for (int i = 0; i < t.length; i++) SMALL.put(t[i], 20 + 10 * i);
    }

    /**
     * Numbers written in words, as values: "seven thousand three hundred forty-two" = 7342, "a hundred and twelve" = 112.
     * Words in one number are joined by spaces, hyphens or "and". "a" counts as one only right before hundred, thousand,
     * million or billion; a bare "hundred" or "thousand" with no count before it is not a number ("hundreds of").
     */
    static List<Long> wordNumbers(String text) {
        String low = Py.lower(text);
        List<Long> out = new ArrayList<>();
        long total = 0, cur = 0;
        boolean in = false, counted = false;   // in = inside a number; counted = it has a count word (not a bare "hundred")
        int lastEnd = 0;
        Matcher m = WORD.matcher(low);
        while (true) {
            boolean more = m.find();
            String w = more ? m.group() : "";
            boolean joined = in && more && low.substring(lastEnd, m.start()).matches("[\\s-]+");
            boolean num = SMALL.containsKey(w) || w.equals("hundred") || SCALE.containsKey(w);
            if (in && !(joined && (num || w.equals("and")))) {   // the number ended here
                if (counted) out.add(total + cur);
                total = 0; cur = 0; in = false; counted = false;
            }
            if (!more) return out;
            int end = m.end();
            if (w.equals("a") && A_COUNT.matcher(low).region(end, low.length()).lookingAt()) {
                in = true; counted = true; cur = 1;
            } else if (w.equals("and") && in) {
                // "a hundred and twelve": keep going
            } else if (SMALL.containsKey(w)) {
                int v = SMALL.get(w);
                if (in && (v >= 10 ? cur % 100 != 0 : cur % 10 != 0)) {   // "two three" is two numbers
                    if (counted) out.add(total + cur);
                    total = 0; cur = 0;
                }
                in = true; counted = true; cur += v;
            } else if (w.equals("hundred")) {
                in = true; cur *= 100;
            } else if (SCALE.containsKey(w)) {
                in = true; total += cur * SCALE.get(w); cur = 0;
            } else continue;
            lastEnd = end;
        }
    }

    /**
     * The known numbers for a date: numbers_in(facts(data)) plus the year. Built from the cleaned facts only: no letters,
     * no reader names, and not the death lines (a death line's text comes from the game and can carry a name players typed;
     * a vanilla death line has no numbers of its own).
     */
    public static Set<String> known(JsonObject data, LocalDate date) {
        JsonObject clean = facts(data);
        clean.remove("deaths");
        Set<String> k = numbersIn(clean, new java.util.HashSet<>());
        k.add(String.valueOf(date.getYear()));
        return k;
    }

    private static final Pattern OWN_SERVER = Pattern.compile("(?<![a-z])own server(?![a-z])");

    /** check() with the default (chicken) ban list. */
    public static List<String> problems(JsonObject data, String draftHtml, LocalDate date) {
        return problems(data, draftHtml, date, null);
    }

    /**
     * check(): up to 20 numbers not in the data (sorted), then up to 20 banned phrases (quoted), in production order, then
     * the checks added since: a clock time, a hidden player, a short never-mention phrase, and Gate C (coordinates).
     * data may be the full facts (letters and reader names are stripped here). settings null = chicken ban list.
     */
    public static List<String> problems(JsonObject data, String draftHtml, LocalDate date, Settings settings) {
        Set<String> known = known(data, date);
        String page = HtmlClean.clean(draftHtml);   // the gates read what the page will show: a dropped tag or an em is no gap
        String text = HtmlClean.checkText(page);
        TreeSet<String> bad = new TreeSet<>(bigNumbers(text));
        bad.removeAll(known);
        List<String> out = new ArrayList<>();
        int i = 0;
        for (String b : bad) {
            if (i++ >= 20) break;
            out.add(b);
        }
        List<String> names = new ArrayList<>();
        for (String n : PyJson.objOf(data, "season_totals").keySet()) if (Py.isName(n)) names.add(n);
        Set<String> lift = lifted(settings);
        lift.addAll(mobKin(text, names));   // the paper only: in a letter, "my cousin" is a player's
        out.addAll(textProblems(text, settings, names, lift));
        if (ConstructionGate.on(settings)) out.addAll(ConstructionGate.problems(data, draftHtml, settings));
        if (settings != null) {   // the paper's own titles ("Fumble of the Day") are never a claim or a slang word
            String own = Lexicon.withoutTitles(settings, text);
            out.addAll(Lexicon.claimProblems(settings, data, own, names));   // slang never invents a play
            out.addAll(Lexicon.tierProblems(settings, own, names));   // no word above tone.audience
            for (String r : Lexicon.restProblems(settings, data, own, names)) if (!r.startsWith("slang ")) out.add(r);   // a removed word (resting is style)
            out.addAll(genderProblems(settings, text, names));
            out.addAll(personProblems(settings, data, own, names));
        }
        // the paper only: dated slang, and tics that became a habit (a player's letter may still say them)
        if (!ClassicPrompt.heavy(settings))   // heavy slang: the readers' own words pass
            for (String d : WordGate.dated(text, names)) out.add("dated (\"" + d + "\")");
        else   // a heavy slang word the owner removed (tone.lexicon.remove) goes back, like a removed lexicon word
            for (String w : StylePrompt.heavyRemoved(settings))   // as tokens, like the dated words: a curly apostrophe or a second space is no way past
                if (!out.contains("removed (\"" + w + "\")") && WordGate.hasPhrase(WordGate.tokens(text, names), w)) out.add("removed (\"" + w + "\")");
        String low = " " + Py.lower(Py.collapse(text)) + " ";
        // names out and accents dropped ("résumé"); a phrase the facts already hold (a build called "The Office") is theirs
        String plain = " " + Py.collapse(WordGate.withoutNames(text, names)) + " ", held = WordGate.norm(String.join(" | ", Lexicon.heldText(settings, data)));
        // classic: the Blotter-order phrases only matter between Blotter lines ("crafted that same shift" in an obituary is fine)
        String blotter = ClassicPrompt.on(settings) ? " " + Py.collapse(WordGate.withoutNames(HtmlClean.checkText(sections(page, "blotter", "cold case")), names)) + " " : plain;
        for (String b : BANNED_EDITION) {
            if (ClassicPrompt.on(settings) && !BLOTTER_ORDER.contains(b)) continue;   // the classic voice's officialese is the joke
            Pattern p = Pattern.compile("(?<![a-z])" + Pattern.quote(b) + "(?![a-z])");
            if (p.matcher(BLOTTER_ORDER.contains(b) ? blotter : plain).find() && !p.matcher(held).find()) out.add("\"" + b + "\"");
        }
        // classic: a tic like the ones above, and the classic prompt never names it. As a gate it sent whole papers back for
        // a blind rewrite (two sentences that each say it once cannot be cut by Salvage either).
        if (!ClassicPrompt.on(settings) && OWN_SERVER.matcher(low).results().count() >= 2) out.add("\"own server\" (more than once)");
        return out;
    }

    /**
     * Style problems: the slang cap and slang tags, resting slang, and a repeated joke shape. They are guidance, never a
     * gate: a draft with only these is published as written, and they go in the owner's notes as "style: ..." (a rewrite to
     * fix them sanded the jokes off). The hard gates stay in problems().
     */
    public static List<String> style(JsonObject data, String draftHtml, Settings settings) {
        String page = HtmlClean.clean(draftHtml), text = HtmlClean.checkText(page);
        List<String> names = new ArrayList<>();
        for (String n : PyJson.objOf(data, "season_totals").keySet()) if (Py.isName(n)) names.add(n);
        List<String> out = new ArrayList<>();
        if (settings != null) {
            if (!ClassicPrompt.on(settings))   // the classic voice sets no slang count, so there is no cap to note
                out.addAll(Lexicon.slangProblems(settings, data, Lexicon.withoutTitles(settings, page), names));   // the slang cap, counted
            for (String r : Lexicon.restProblems(settings, data, Lexicon.withoutTitles(settings, text), names)) if (r.startsWith("slang ")) out.add(r);
        }
        if (Lexicon.contrasts(text) >= 2) out.add("\"" + Lexicon.CONTRAST + "\" (more than once)");   // the classic AI tell, once at most
        return out;
    }

    private static final Set<String> PRONOUNS = Set.of("he", "him", "his", "himself", "she", "her", "hers", "herself");
    /** Animals and mobs the paper quotes or jokes about; a pronoun after one, in a clause with no player, is about it. */
    static final Set<String> ANIMALS = Set.of("hen", "hens", "spokeshen", "chicken", "chickens", "rooster", "cow", "cows", "sheep", "pig", "pigs",
            "horse", "horses", "donkey", "mule", "llama", "cat", "wolf", "dog", "parrot", "bee", "villager", "villagers", "librarian", "golem",
            "spider", "creeper", "zombie", "skeleton", "piglin", "hoglin", "witch", "phantom", "goat", "fox", "frog", "axolotl", "camel", "sniffer",
            "silverfish");

    /** Family words a mob or an animal may have in a joke ("the silverfish family", "the spider's cousin"). */
    private static final List<String> KIN = List.of("family", "the family", "cousin", "cousins");
    /** Words that point at a person: a sentence with one keeps its family words held. */
    private static final Set<String> PERSON = Set.of("he", "him", "his", "himself", "she", "her", "hers", "herself", "they", "them", "their",
            "theirs", "themselves", "you", "your", "yours", "owner", "owners");

    /**
     * The KIN words that are only ever a mob's or an animal's: each one is in a quote (in the paper only mobs, animals and
     * villagers speak) or just after one ("the silverfish family", "the spider's smaller cousin", "its cousin"; never "a spider
     * bit the cousin"), in a sentence
     * that names a mob or an animal and no player (not even part of a name: "the Cat family" is Cat_3's), no he, she, they or
     * you, and no owner. The floor lifts them for the paper only.
     * shortcut: a quote counts as the speaker's own family ("The whole family logged on," said a villager, passes); hold quotes
     * too if a draft ever uses one about the players.
     */
    static Set<String> mobKin(String text, java.util.Collection<String> names) {
        Set<String> parts = new HashSet<>();
        for (String n : names) for (String p : n.split("[^A-Za-z]+|(?<=[a-z])(?=[A-Z])")) if (p.length() >= 3) parts.add(Py.lower(p));
        Set<String> out = new HashSet<>(KIN);
        for (String sn : WordGate.sentences(text)) {
            List<String> raw = WordGate.rawTokens(sn);
            boolean ok = raw.stream().anyMatch(FactCheck::animal) && WordGate.namesIn(sn, names).isEmpty()
                    && raw.stream().noneMatch(t -> PERSON.contains(t) || parts.contains(t) || parts.contains(t.replaceAll("s$", "")));
            String[] seg = sn.split("[\"“”]", -1);
            if (seg.length % 2 == 0) seg = new String[]{sn};   // a quote mark left open is no quote ("the 6\" spider")
            StringBuilder said = new StringBuilder();   // the sentence with its quotes cut out
            for (int i = 0; i < seg.length; i += 2) said.append(seg[i]).append(' ');
            List<String> o = WordGate.tokens(said.toString(), names);   // as the floor reads it: "f a m i l y" is a word here too
            for (int i = 0; i < o.size() && ok; i++)
                if (KIN.contains(o.get(i))) ok = mobOwns(o, i);
            List<String> toks = WordGate.tokens(sn, names);
            if (!ok) out.removeIf(k -> WordGate.hasExact(toks, k));
        }
        return out;
    }

    /**
     * Whether the KIN word at i is the mob's: the mob or "its" right before it ("the silverfish family", "its cousin", "the
     * spider's cousin"), or "its" or a mob's "'s" one word before it ("its whole family", "the spider's smaller cousin").
     * A mob further back is not its owner: "zombies chased family", "a spider bit the cousin".
     */
    private static boolean mobOwns(List<String> o, int i) {
        for (int j = i - 1; j >= Math.max(0, i - 2); j--) {
            String t = o.get(j);
            if (t.equals("its") || (t.equals("s") && j > 0 && animal(o.get(j - 1))) || (j == i - 1 && animal(t))) return true;
        }
        return false;
    }

    private static boolean animal(String t) {
        return ANIMALS.contains(t) || ANIMALS.contains(t.replaceAll("s$", "")) || ANIMALS.contains(t.replaceAll("es$", ""));
    }

    /**
     * "gender (\"herself\")": a gendered pronoun in a sentence that names a player the paper can't know the gender of. The owner's
     * own pronoun is fine where the owner is named, in the sentence or the one before it ("Kai_Ore opened Rex_Owner's
     * barrels and a chest of his"); any other gendered pronoun next to a player's name is a guess.
     */
    static List<String> genderProblems(Settings s, String text, java.util.Collection<String> names) {
        String owner = StylePrompt.hasOwner(s) ? StylePrompt.owner(s) : null;
        Set<String> ownerP = owner == null || s.owner().they() ? Set.of()
                : s.owner().pronoun().equals("he") ? Set.of("he", "him", "his", "himself") : Set.of("she", "her", "hers", "herself");
        TreeSet<String> hit = new TreeSet<>();
        List<String> all = WordGate.sentences(text);
        for (int i = 0; i < all.size(); i++) {
            String sn = all.get(i);
            List<String> named = WordGate.namesExact(sn, names);
            if (named.isEmpty()) continue;
            boolean ownerNear = owner != null && (named.stream().anyMatch(n -> n.equalsIgnoreCase(owner))
                    || (i > 0 && WordGate.namesExact(all.get(i - 1), names).stream().anyMatch(n -> n.equalsIgnoreCase(owner))));
            Set<String> keys = new HashSet<>();
            for (String n : named) { keys.add(WordGate.nameKey(n)); keys.add(WordGate.nameKey(n).replace("_", "")); }
            // "Sol_Dig won, and a hen handed over her own egg": the pronoun is the animal's only in a clause that names no player
            for (String clause : sn.split("[,;:()\u2014\u2013]")) {
                List<String> toks = WordGate.rawTokens(clause);
                boolean animalLast = false, playerHere = toks.stream().anyMatch(keys::contains);
                for (String x : toks) {
                    if (ANIMALS.contains(x)) animalLast = !playerHere;
                    else if (PRONOUNS.contains(x) && !animalLast && !(ownerNear && ownerP.contains(x))) hit.add(x);
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (String h : hit) out.add("gender (\"" + h + "\")");
        return out;
    }

    /** A word that says a person is a man, a woman, a boy, a girl or a guy ("adult" and "kid" are the family check's). Compounds pass: Enderman, Pac-Man, man-made. */
    private static final Pattern PERSON_NOUN = Pattern.compile("(?<![\\w-])(?:m[ae]n|wom[ae]n|boys?|girls?|guys?|gentlem[ae]n|lady|ladies)(?![\\w-])");
    /** Names, idioms and interjections that hold one but call no one anything. */
    private static final Pattern NOT_PERSON = Pattern.compile("(?<![\\w-])(?:no\\s+man['’]?s\\s+land|iron\\s+man|lady\\s+luck|(?:aw|oh)\\s+(?:man|boy))(?![\\w-])");

    /**
     * "gender (\"man\")": a word from PERSON_NOUN anywhere in the paper, about the owner too (owner.pronoun is for pronouns only).
     * Player names are cut first, and a word the facts hold (a build's name, a death line, the owner's lore) is theirs.
     */
    static List<String> personProblems(Settings s, JsonObject data, String text, java.util.Collection<String> names) {
        Set<String> held = new HashSet<>();
        for (Matcher m = PERSON_NOUN.matcher(WordGate.norm(String.join(" | ", Lexicon.heldText(s, data)))); m.find(); ) held.add(m.group());
        TreeSet<String> hit = new TreeSet<>();
        for (Matcher m = PERSON_NOUN.matcher(NOT_PERSON.matcher(WordGate.withoutNames(text, names)).replaceAll(" ")); m.find(); )
            if (!held.contains(m.group())) hit.add(m.group());
        List<String> out = new ArrayList<>();
        for (String h : hit) out.add("gender (\"" + h + "\")");
        return out;
    }

    /**
     * The checks that need no data, for the edition and for printed mail alike: banned phrases (quoted, up to 20), then
     * "time of day" (a clock time), "hidden player" (never quoted), never-mention ("phrase") for short never-mention entries (up to
     * four words; longer ones are topics only the AI can judge), and Gate C. text = plain text, entities decoded.
     * names = known player names (Gate C masks them). settings null = chicken ban list, nothing hidden.
     */
    public static List<String> textProblems(String text, Settings settings, java.util.Collection<String> names) {
        return textProblems(text, settings, names, lifted(settings));
    }

    /** As above, with the floor words to lift given: none for a player's own letter (the tone lifts words only for what the paper and the desk write). */
    static List<String> textProblems(String text, Settings settings, java.util.Collection<String> names, java.util.Collection<String> lifted) {
        String low = " " + Py.lower(Py.collapse(text)) + " ";
        List<String> phrases = new ArrayList<>();
        List<String> list = settings == null ? defaultBanned() : bannedPhrases(settings);
        for (String b : list) {
            Pattern p = Pattern.compile("(?<![a-z])" + Pattern.quote(b) + "(?![a-z])");
            if (p.matcher(low).find()) phrases.add("\"" + b + "\"");
        }
        List<String> out = new ArrayList<>(phrases.subList(0, Math.min(20, phrases.size())));
        if (CLOCK.matcher(text).find()) out.add("time of day");
        if (settings != null) {
            // never quoted: the name would then be in the rewrite prompt
            if (!WordGate.namesIn(text, settings.privacy().hidePlayers()).isEmpty()) out.add("hidden player");
            for (String nm : StylePrompt.neverMention(settings)) {
                String k = Py.lower(Py.collapse(nm.strip()));
                if (k.isEmpty() || k.split(" ").length > 4) continue;
                if (Pattern.compile("(?<![a-z0-9])" + Pattern.quote(k) + "(?![a-z0-9])").matcher(low).find()) out.add("never-mention (\"" + nm.strip() + "\")");
            }
        }
        out.addAll(GateC.problems(text, names));
        // the floor: never printed at any setting. Only the owner's own lore, retold about the owner, may use its cheating words.
        boolean own = settings != null && settings.owner() != null && StylePrompt.hasOwner(settings);
        List<String> lore = own ? settings.owner().lore() : List.of();
        List<String> floor = WordGate.floor(text, names, lore, own ? StylePrompt.owner(settings) : "", lifted);
        for (String f : floor) out.add((WordGate.FLOOR_FAMILY.contains(f) ? "family" : "floor") + " (\"" + f + "\")");
        // family words the mail desk already refuses (father, son, adult, kid...): matched exactly, names removed first
        Matcher fm = MailDesk.FAMILY.matcher(WordGate.withoutNames(text, names));
        java.util.Set<String> seen = new java.util.LinkedHashSet<>(floor);
        while (fm.find()) {
            String w = Py.lower(fm.group()).replaceAll("\\s+", " ");
            if (seen.add(w)) out.add("family (\"" + w + "\")");
        }
        return out;
    }

    /**
     * The floor words this server's tone lifts for the paper:
     * trash talk at savage for teens and adults, "sus" at savage for adults, drinking for adults. The rest of the floor
     * (sexual, slurs, drugs, self-harm, looks, family, name jokes) and every other gate hold at every setting.
     */
    public static Set<String> lifted(Settings s) {
        Set<String> out = new HashSet<>();
        if (s == null || s.tone() == null) return out;
        String aud = s.tone().audience(), spice = s.tone().spice();
        boolean savage = spice.equals("savage");
        if (savage && !aud.equals("all-ages")) out.addAll(WordGate.TRASH_TALK);
        if (savage && aud.equals("adults")) out.addAll(WordGate.SUSPICION);
        if (aud.equals("adults")) { out.addAll(WordGate.DRINK); out.addAll(WordGate.DRINK_EMOJI); }
        return out;
    }

    private static final Pattern H3 = Pattern.compile("(?is)<h3[^>]*>(.*?)</h3>");

    /** The html of the h3 sections whose heading contains one of the titles (lowercase), each up to the next h3. */
    static String sections(String html, String... titles) {
        StringBuilder out = new StringBuilder();
        Matcher m = H3.matcher(html);
        List<int[]> heads = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) { heads.add(new int[]{m.start(), m.end()}); names.add(m.group(1).replaceAll("<[^>]+>", "").toLowerCase(Locale.ROOT)); }
        for (int i = 0; i < heads.size(); i++) {
            int i0 = i;
            if (Arrays.stream(titles).noneMatch(t -> names.get(i0).contains(t))) continue;
            int end = i + 1 < heads.size() ? heads.get(i + 1)[0] : html.length();
            out.append(html, heads.get(i)[1], end).append('\n');
        }
        return out.toString();
    }

    private static List<String> defaultBanned() {
        List<String> out = new ArrayList<>(BANNED_ALWAYS);
        out.addAll(BANNED_CHICKEN);
        out.addAll(BANNED_TIME);
        return out;
    }

    /** The shell-friendly line Python printed: problems joined by spaces ("" = fine). */
    public static String line(List<String> problems) {
        return String.join(" ", problems);
    }
}
