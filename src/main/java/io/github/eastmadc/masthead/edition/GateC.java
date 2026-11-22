package io.github.eastmadc.masthead.edition;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gate C, the coordinates check: number shapes that could be a position, and the words chunk / coords / coordinates /
 * Y level. Runs on every edition draft and on every printed letter, tip and reply, so the "never say where anything is"
 * hard line never depends on the AI noticing. Known player names are masked first, so a player called x420 or Y2006 does
 * not trip it. A number hit is labelled "coordinates" and never quoted (the match could be a real position); a word hit
 * names the word, e.g. coordinates ("chunk").
 */
public final class GateC {
    private GateC() {}

    /** The words the paper may never use, in the order the prompt lists them. */
    public static final List<String> WORDS = List.of("chunk", "coord", "coords", "coordinates", "Y level");

    /**
     * x, y or z then a number in words ("x ninety", "x was ninety", "x at minus forty", "xninety", "x fourty"). "zone" is a word.
     * Wide on purpose: a missed position is worse than one rewrite ("Plan Z was one big mess" is caught too).
     * At most 8 number words in a row: an unbounded repeat recursed deep on very long text.
     */
    static final Pattern WORD_COORD = Pattern.compile("(?i)\\b(?!zones?\\b)[xyz](?:\\b[\\s:=~-]*(?:(?:is|at|of|equals|was|to)\\b[\\s:=~-]*){0,2})?"
            + "(?:(?:minus|negative)[\\s-]*)?(?:" + ConstructionWords.NUMBER_WORDS + "|fourty){1,8}s?\\b");

    /** The number shapes (construction spec §7.1, patterns 1-5 and 7-9), and positions written in words. */
    static final List<Pattern> NUMBERS = List.of(WORD_COORD,
            Pattern.compile("(?i)\\b[xyz]\\s*[:=]\\s*-?\\d{1,8}\\b"),
            Pattern.compile("(?<![\\d.,])(?<!\\d\\s)\\b[xXzZ]\\s?-?\\d{1,8}\\b"),
            Pattern.compile("\\(\\s*-?\\d{1,8}\\s*,\\s*-?\\d{1,8}(?:\\s*,\\s*-?\\d{1,8})?\\s*\\)"),
            Pattern.compile("(?<![\\w.,])-\\d{1,8}\\s*,\\s*-?\\d{1,8}|\\b\\d{1,8}\\s*,\\s*-\\d{1,8}"),
            Pattern.compile("(?i)\\bat\\s+-?\\d{1,8}\\s*,?\\s+-?\\d{1,8}\\b"),
            Pattern.compile("\\bY\\s?-?\\d{1,4}\\b"),
            Pattern.compile("-?\\d{1,8}\\s*/\\s*-?\\d{1,8}\\s*/\\s*-?\\d{1,8}"),
            Pattern.compile("(?<![\\d.,])-\\d{1,8}\\s+-?\\d{1,8}\\b|(?<![\\d.,])\\b\\d{1,8}\\s+-\\d{1,8}\\b"));

    /** Pattern 6, built from WORDS (a space in a word matches a space, a dash or nothing). */
    static final Pattern KEYWORD = Pattern.compile("(?i)\\b(?:coords?|coordinates|chunk|y[\\s-]?level)\\b");

    /**
     * The problems in a piece of plain text (tags already removed): "coordinates" once if any number shape matches, then
     * coordinates ("word") once per word found. names = known player names, masked first. Empty = fine.
     */
    public static List<String> problems(String text, Collection<String> names) {
        String t = mask(Normalizer.normalize(Py.unescape(text), Normalizer.Form.NFKC), names);
        List<String> out = new ArrayList<>();
        for (Pattern p : NUMBERS)
            if (p.matcher(t).find()) { out.add("coordinates"); break; }
        Matcher m = KEYWORD.matcher(t);
        while (m.find()) {
            String w = m.group().toLowerCase(Locale.ROOT);
            String label = "coordinates (\"" + (w.startsWith("y") ? "Y level" : w) + "\")";
            if (!out.contains(label)) out.add(label);
        }
        return out;
    }

    /** Each known name as a whole word, any case, becomes "Player" (no digit, not x/y/z, not a gate word). */
    static String mask(String text, Collection<String> names) {
        List<String> sorted = new ArrayList<>(names);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String n : sorted) {
            if (n == null || n.isEmpty()) continue;
            text = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(n) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE)
                    .matcher(text).replaceAll("Player");
        }
        return text;
    }
}
