package io.github.eastmadc.masthead.edition;

import io.github.eastmadc.masthead.config.Settings;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Puts checked mail into the edition (port of splice / mail_plug). */
public final class Splice {
    private Splice() {}

    public static final String LETTERS_HEADING = "📬 Letters to the Editor";
    private static final Pattern H3 = Pattern.compile("<h3>(.*?)</h3>", Pattern.DOTALL);

    /** splice() with the section names from settings (bad award heading, "&lt;Animal&gt; Watch"). */
    public static String apply(Settings s, String frag, List<String> letters, List<String> tips, String plug) {
        return apply(frag, letters, tips, plug, awardKey(s.paper().badAward()), StylePrompt.watchOn(s) ? StylePrompt.watchTitle(s) : null);
    }

    /**
     * Letters and anonymous news tips share the editor's section, right after the bad-award section (else before the
     * watch column, else after the front page, else at the end). With no printed mail, the plug line goes at the end.
     */
    public static String apply(String frag, List<String> letters, List<String> tips, String plug, String afterKey, String watchKey) {
        if (!letters.isEmpty() || !tips.isEmpty()) {
            Integer pos = afterKey == null || afterKey.isEmpty() ? null : endOf(frag, afterKey);
            if (pos == null && watchKey != null) pos = startOf(frag, watchKey);
            if (pos == null) pos = endOf(frag, "Front page");
            if (pos == null) pos = frag.length();
            frag = frag.substring(0, pos) + "<h3>" + LETTERS_HEADING + "</h3>"
                    + String.join("", letters) + String.join("", tips) + frag.substring(pos);
        } else if (plug != null && !plug.isEmpty()) {
            frag += "<p><em>" + Py.escape(plug, false) + "</em></p>";
        }
        return frag;
    }

    /** "🤡 Fumble of the Day" -> "Fumble of": the first two words, emoji/punctuation dropped. */
    static String awardKey(String award) {
        if (award == null) return "";
        int i = 0;
        while (i < award.length()) {
            int c = award.codePointAt(i);
            if (Character.isLetterOrDigit(c)) break;
            i += Character.charCount(c);
        }
        String[] w = award.substring(i).trim().split(" +");
        return w.length >= 2 ? w[0] + " " + w[1] : w[0];
    }

    private record H(int pos, String text) {}

    private static List<H> h3s(String frag) {
        List<H> out = new ArrayList<>();
        Matcher m = H3.matcher(frag);
        while (m.find()) out.add(new H(m.start(), Py.stripTags(m.group(1), "")));
        return out;
    }

    private static Integer endOf(String frag, String key) {
        List<H> hs = h3s(frag);
        for (int i = 0; i < hs.size(); i++)
            if (hs.get(i).text().contains(key)) return i + 1 < hs.size() ? hs.get(i + 1).pos() : frag.length();
        return null;
    }

    private static Integer startOf(String frag, String key) {
        for (H h : h3s(frag)) if (h.text().contains(key)) return h.pos();
        return null;
    }

    /**
     * mail_plug: a one-line reminder when no mail ran: the first 3 editions after the mailroom opened, then Mondays.
     * mailroomSince null = unknown (no plug).
     */
    public static String mailPlug(Settings s, LocalDate mailroomSince, LocalDate date) {
        if (mailroomSince == null) return "";
        long days = ChronoUnit.DAYS.between(mailroomSince, date);
        if (days >= 0 && days < 3) return "📬 New: " + s.paper().name() + " now takes letters. Type /letter in game to write to the editor.";
        return date.getDayOfWeek() == DayOfWeek.MONDAY ? "📬 The mailroom is open: type /letter in game." : "";
    }
}
