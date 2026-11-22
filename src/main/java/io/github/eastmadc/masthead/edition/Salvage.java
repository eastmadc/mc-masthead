package io.github.eastmadc.masthead.edition;

import io.github.eastmadc.masthead.config.Settings;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The last try before the plain edition (without it, one bad number could ship a stats table). When the
 * rewrites still fail the fact check, the sentences that fail it on their own are cut from their paragraph or list item (the whole
 * item goes only when none of it passes, or its tags cross a sentence), and the rest is checked again as a whole by the same full
 * gate. A paper comes back only if it then passes every check, keeps its headline, touches at most MAX_DROPS items, keeps at least
 * 3/4 of its words and leaves no section empty but the construction one. Otherwise null, and the plain edition runs as before.
 * (Dropping whole items would delete a one-paragraph owner's desk over one bad sentence.)
 */
public final class Salvage {
    private Salvage() {}

    static final int MAX_DROPS = 4;
    private static final Pattern BLOCK = Pattern.compile("(?is)<(p|li)\\b[^>]*>.*?</\\1>");
    private static final Pattern H3 = Pattern.compile("(?is)<h3\\b[^>]*>.*?</h3>");
    private static final Pattern H2 = Pattern.compile("(?is)<h2\\b[^>]*>.*?</h2>");

    public static String drop(JsonObject facts, String draft, LocalDate date, Settings s) {
        if (draft == null || draft.isBlank()) return null;
        Matcher h = H2.matcher(draft);
        if (!h.find() || !FactCheck.problems(facts, h.group(), date, s).isEmpty()) return null;   // no headline, or a bad one: nothing to save
        StringBuilder out = new StringBuilder();
        Matcher m = BLOCK.matcher(draft);
        int last = 0, drops = 0;
        while (m.find()) {
            out.append(draft, last, m.start());
            String head = "";   // the block's section heading, on its own line: some checks only run under it (classic Blotter order)
            for (Matcher h3 = H3.matcher(draft).region(0, m.start()); h3.find(); ) head = h3.group() + "\n";
            if (FactCheck.problems(facts, head + m.group(), date, s).isEmpty()) out.append(m.group());
            else {
                if (++drops > MAX_DROPS) return null;
                out.append(cut(facts, head, m.group(), m.group(1), date, s));
            }
            last = m.end();
        }
        out.append(draft.substring(last));
        if (drops == 0) return null;   // the problem is spread over the page, not in one item
        String kept = out.toString().replaceAll("(?is)<(ul|ol)\\b[^>]*>\\s*</\\1>", "");
        Matcher empty = EMPTY_H3.matcher(kept);   // a section left empty: only the construction one may go
        String cons = s == null || s.construction() == null ? null : s.construction().title();
        while (empty.find()) if (cons == null || !Py.stripTags(empty.group(), " ").contains(cons)) return null;
        kept = EMPTY_H3.matcher(kept).replaceAll("");
        if (words(kept) * 4 < words(draft) * 3 || !FactCheck.problems(facts, kept, date, s).isEmpty()) return null;
        return kept;
    }

    private static final Pattern EMPTY_H3 = Pattern.compile("(?is)<h3\\b[^>]*>[^<]*(?:<(?!/h3)[^>]*>[^<]*)*</h3>\\s*(?=<h3\\b|\\z)");
    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z0-9]+)\\b[^>]*?(/?)>");
    /** A sentence that leans on the one before it ("They never touched the ground."): it goes when that one is cut. */
    private static final Pattern LEANS = Pattern.compile("^\\W*(?:he|she|they|them|their|his|her|it|its|that|this|those|these|which)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * A failing block with its failing sentences cut: the sentences that pass the fact check on their own stay, in order; a sentence
     * right after a cut one goes too if it opens on a pronoun (it would point at the wrong player). "" when nothing passes, or when a
     * tag crosses a sentence (the cut would break the page).
     */
    private static String cut(JsonObject facts, String head, String block, String tag, LocalDate date, Settings s) {
        int open = block.indexOf('>') + 1, close = block.lastIndexOf("</");
        if (open <= 0 || close < open) return "";
        List<String> parts = WordGate.sentences(block.substring(open, close));
        StringBuilder kept = new StringBuilder();
        boolean prevCut = false;
        for (String p : parts) {
            if (!balanced(p)) return "";
            boolean ok = !(prevCut && LEANS.matcher(Py.stripTags(p, " ").strip()).find())
                    && FactCheck.problems(facts, head + "<" + tag + ">" + p + "</" + tag + ">", date, s).isEmpty();
            if (ok) kept.append(kept.isEmpty() ? "" : " ").append(p.strip());
            prevCut = !ok;
        }
        return kept.isEmpty() ? "" : block.substring(0, open) + kept + block.substring(close);
    }

    /** Whether every tag opened in the text is closed in it, in order (br and self-closing tags aside). */
    private static boolean balanced(String html) {
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        Matcher m = TAG.matcher(html);
        while (m.find()) {
            String name = m.group(2).toLowerCase(java.util.Locale.ROOT);
            if (name.equals("br") || !m.group(3).isEmpty()) continue;
            if (m.group(1).isEmpty()) stack.push(name);
            else if (stack.isEmpty() || !stack.pop().equals(name)) return false;
        }
        return stack.isEmpty();
    }

    private static int words(String html) {
        String t = Py.stripTags(html, " ").trim();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }
}
