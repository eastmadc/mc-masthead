package io.github.eastmadc.masthead.edition;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Allow-list HTML sanitizer for writer output (port of the reference Python script's Clean / ReplyClean).
 *
 * <p>The output contains only the allowed tags, never with attributes; all text is escaped; the contents of script,
 * style, iframe, object, svg, math, template, head and title are dropped; unclosed tags are closed at the end.
 * The tokenizer follows CPython 3.12's html.parser (fed once, never closed: an unfinished tag or comment at the end
 * drops the rest of the input), so the result matches the production page byte for byte, except that invisible format
 * characters (zero-width space, soft hyphen, joiners, BOM) are dropped from the text: one inside a word would hide it from
 * the gates. A joiner between two emoji stays.
 */
public final class HtmlClean {
    private HtmlClean() {}

    /** Tags allowed in an edition. */
    public static final Set<String> ALLOWED = Set.of("h2", "h3", "p", "ul", "ol", "li", "strong", "em", "table", "thead", "tbody",
            "tr", "th", "td", "blockquote", "br");
    /** Tags allowed in an editor-desk reply. */
    public static final Set<String> REPLY_ALLOWED = Set.of("p", "strong", "em", "br");
    private static final Set<String> SKIP = Set.of("script", "style", "iframe", "object", "svg", "math", "template", "head", "title");
    private static final Set<String> CDATA = Set.of("script", "style", "xmp", "iframe", "noembed", "noframes");
    private static final Set<String> RCDATA = Set.of("textarea", "title");

    /** Clean an edition fragment. */
    public static String clean(String html) {
        return new Cleaner(ALLOWED).run(html);
    }

    /** Clean an editor-desk reply (only p, strong, em, br). */
    public static String cleanReply(String html) {
        return new Cleaner(REPLY_ALLOWED).run(html);
    }

    /** em and strong are drawn with no gap, so the gates read them with none ("d<em></em>ad" is "dad"). */
    private static final Pattern INLINE = Pattern.compile("(?i)</?(?:em|strong)\\s*/?>");
    /** Invisible format characters (zero-width space, soft hyphen, joiners, word joiner, BOM, bidi controls). */
    static final Pattern FORMAT = Pattern.compile("\\p{Cf}");

    /**
     * The text every gate reads, as a reader sees it: em and strong removed with no gap, every other tag a space, entities
     * decoded, invisible format characters dropped. Whitespace is kept (line breaks split sentences). Pass cleaned HTML: a tag
     * the cleaner drops is drawn with no gap too.
     */
    public static String checkText(String html) {
        return FORMAT.matcher(Py.unescape(Py.stripTags(INLINE.matcher(html).replaceAll(""), " "))).replaceAll("");
    }

    /** Text of a fragment: em and strong with no gap, other tags spaces, entities decoded, whitespace collapsed, trimmed. */
    public static String text(String html) {
        return Py.strip(Py.collapse(checkText(html)));
    }

    /** Format characters dropped from printed text, except a joiner between two emoji and the tag letters of a flag emoji. */
    static String dropFormat(String d) {
        if (!FORMAT.matcher(d).find()) return d;
        StringBuilder b = new StringBuilder(d.length());
        for (int i = 0; i < d.length(); ) {
            int c = d.codePointAt(i), n = Character.charCount(c);
            if (Character.getType(c) == Character.FORMAT) {
                boolean keep = false;
                if (c == 0x200D) {
                    int prev = i > 0 ? d.codePointBefore(i) : -1, next = i + n < d.length() ? d.codePointAt(i + n) : -1;
                    keep = emoji(prev) && emoji(next);
                } else if (c >= 0xE0020 && c <= 0xE007F) keep = i > 0 && (emoji(d.codePointBefore(i)) || (d.codePointBefore(i) >= 0xE0020 && d.codePointBefore(i) <= 0xE007F));
                if (keep) b.appendCodePoint(c);
            } else b.appendCodePoint(c);
            i += n;
        }
        return b.toString();
    }

    /** An emoji, or the variation selector or skin tone that ends one. */
    private static boolean emoji(int c) {
        if (c < 0) return false;
        int t = Character.getType(c);
        return t == Character.OTHER_SYMBOL || c == 0xFE0F || (c >= 0x1F3FB && c <= 0x1F3FF);
    }

    // ------------------------------------------------------------------------------------------------------------------

    private static final class Cleaner {
        final Set<String> allowed;
        final StringBuilder out = new StringBuilder();
        final Deque<String> open = new ArrayDeque<>();   // stack; top = last opened
        int skip = 0;
        String cdataElem = null;
        boolean escapable = true;
        Pattern cdataEnd = null;

        Cleaner(Set<String> allowed) {
            this.allowed = allowed;
        }

        // ---- handlers (Clean / ReplyClean)

        void startTag(String tag) {
            boolean considered = allowed.contains(tag) || SKIP.contains(tag);   // ReplyClean ignores everything else
            if (!considered) return;
            if (SKIP.contains(tag)) {
                skip++;
                return;
            }
            if (allowed.contains(tag) && skip == 0) {
                out.append('<').append(tag).append('>');
                if (!tag.equals("br")) open.push(tag);
            }
        }

        void endTag(String tag) {
            boolean considered = allowed.contains(tag) || SKIP.contains(tag);
            if (!considered) return;
            if (SKIP.contains(tag)) {
                skip = Math.max(0, skip - 1);
                return;
            }
            if (allowed.contains(tag) && !tag.equals("br") && skip == 0 && open.contains(tag)) {
                while (!open.isEmpty()) {
                    String t = open.pop();
                    out.append("</").append(t).append('>');
                    if (t.equals(tag)) break;
                }
            }
        }

        void data(String d) {
            if (skip == 0) out.append(Py.escape(dropFormat(d), false));   // what the gates read is what the page shows
        }

        String result() {
            while (!open.isEmpty()) out.append("</").append(open.pop()).append('>');
            return out.toString();
        }

        // ---- tokenizer (CPython 3.12 HTMLParser.goahead with convert_charrefs=True, end=False)

        String run(String raw) {
            String s = raw;
            int n = s.length();
            int i = 0;
            while (i < n) {
                int j;
                if (cdataElem == null) {
                    j = s.indexOf('<', i);
                    if (j < 0) {
                        int amppos = s.lastIndexOf('&');
                        if (amppos >= Math.max(i, n - 34) && !hasSpaceOrSemi(s, amppos)) break;   // maybe a cut charref: wait
                        j = n;
                    }
                } else {
                    Matcher m = cdataEnd.matcher(s);
                    if (m.find(i)) j = m.start();
                    else break;
                }
                if (i < j) {
                    if (escapable) data(Py.unescape(s.substring(i, j)));
                    else data(s.substring(i, j));
                }
                i = j;
                if (i == n) break;
                // s[i] == '<'
                int k;
                if (i + 1 < n && isAsciiLetter(s.charAt(i + 1))) k = parseStartTag(s, i);
                else if (s.startsWith("</", i)) k = parseEndTag(s, i);
                else if (s.startsWith("<!--", i)) k = parseComment(s, i);
                else if (s.startsWith("<?", i)) k = findAfter(s, '>', i + 2);
                else if (s.startsWith("<!", i)) k = parseDeclaration(s, i);
                else if (i + 1 < n) {
                    data("<");
                    k = i + 1;
                } else break;
                if (k < 0) break;   // unfinished construct: Python waits for more input that never comes
                i = k;
            }
            return result();
        }

        private static boolean hasSpaceOrSemi(String s, int from) {
            for (int p = from; p < s.length(); p++) {
                char c = s.charAt(p);
                if (c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == ' ' || c == ';') return true;
            }
            return false;
        }

        private static int findAfter(String s, char c, int from) {
            int p = s.indexOf(c, from);
            return p < 0 ? -1 : p + 1;
        }

        private int parseComment(String s, int i) {
            int n = s.length();
            for (int p = i + 4; p < n; p++) {   // commentclose: --!?>
                if (s.startsWith("--", p)) {
                    if (s.startsWith("-->", p)) return p + 3;
                    if (s.startsWith("--!>", p)) return p + 4;
                }
            }
            if (s.startsWith(">", i + 4)) return i + 5;            // commentabruptclose: -?>
            if (s.startsWith("->", i + 4)) return i + 6;
            return -1;
        }

        private int parseDeclaration(String s, int i) {
            if (s.startsWith("<![CDATA[", i)) {
                int j = s.indexOf("]]>", i + 9);
                return j < 0 ? -1 : j + 3;
            }
            if (s.length() >= i + 9 && s.substring(i, i + 9).toLowerCase(Locale.ROOT).equals("<!doctype")) return findAfter(s, '>', i + 9);
            if (s.startsWith("<![", i)) return findAfter(s, '>', i + 3);
            return findAfter(s, '>', i + 2);   // bogus comment
        }

        private int parseStartTag(String s, int i) {
            int[] end = locateTagEnd(s, i + 1);
            int endpos = end[0];
            if (endpos <= 0 || s.charAt(endpos - 1) != '>') return -1;
            String tag = tagName(s, i + 1);
            boolean selfClosing = end[1] == 1;
            if (selfClosing) {
                startTag(tag);
                endTag(tag);
            } else {
                startTag(tag);
                if (CDATA.contains(tag) || tag.equals("plaintext")) setCdata(tag, false);
                else if (RCDATA.contains(tag)) setCdata(tag, true);
            }
            return endpos;
        }

        private int parseEndTag(String s, int i) {
            if (s.indexOf('>', i + 2) < 0) return -1;
            if (!(i + 2 < s.length() && isAsciiLetter(s.charAt(i + 2)))) {
                if (s.startsWith(">", i + 2)) return i + 3;
                return findAfter(s, '>', i + 2);   // bogus comment
            }
            int[] end = locateTagEnd(s, i + 2);
            int j = end[0];
            if (s.charAt(j - 1) != '>') return -1;
            endTag(tagName(s, i + 2));
            cdataElem = null;
            escapable = true;
            cdataEnd = null;
            return j;
        }

        private void setCdata(String tag, boolean esc) {
            cdataElem = tag;
            escapable = esc;
            cdataEnd = tag.equals("plaintext") ? Pattern.compile("\\z")
                    : Pattern.compile("</" + Pattern.quote(tag) + "(?=[\\t\\n\\r\\f />])", Pattern.CASE_INSENSITIVE);
        }

        private static boolean isAsciiLetter(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        }

        private static boolean ws(char c) {
            return c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == ' ';
        }

        /** tagfind_tolerant group 1, lowercased */
        private static String tagName(String s, int p) {
            int q = p + 1;
            while (q < s.length() && !(ws(s.charAt(q)) || s.charAt(q) == '/' || s.charAt(q) == '>')) q++;
            return Py.lower(s.substring(p, q));
        }

        /**
         * Hand-written equivalent of CPython's locatetagend regex (no regex recursion on long input), starting at the tag
         * name's first letter. Returns {end, selfClosing}: end is where the match stops (just after '>' when there is one);
         * selfClosing = the tag ends with a "/>" that is not part of an unquoted attribute value.
         */
        private static int[] locateTagEnd(String s, int p) {
            int n = s.length();
            p++;
            while (p < n && !(ws(s.charAt(p)) || s.charAt(p) == '/' || s.charAt(p) == '>')) p++;   // tag name
            boolean lastWasBare = false;
            while (p < n && (ws(s.charAt(p)) || s.charAt(p) == '/')) p++;
            while (p < n) {
                char prev = s.charAt(p - 1);
                char c = s.charAt(p);
                boolean prevOk = prev == '\'' || prev == '"' || ws(prev) || prev == '/';
                if (!prevOk || ws(c) || c == '/' || c == '>') break;
                // attribute name
                p++;
                while (p < n && !(ws(s.charAt(p)) || s.charAt(p) == '/' || s.charAt(p) == '=' || s.charAt(p) == '>')) p++;
                lastWasBare = false;
                // optional value
                int q = p;
                while (q < n && ws(s.charAt(q))) q++;
                if (q < n && s.charAt(q) == '=') {
                    int v = q + 1;
                    int wsStart = v;
                    while (v < n && ws(s.charAt(v))) v++;
                    int got = -1;
                    boolean bare = false;
                    if (v < n && (s.charAt(v) == '\'' || s.charAt(v) == '"')) {
                        int close = s.indexOf(s.charAt(v), v + 1);
                        if (close >= 0) got = close + 1;
                        else if (v > wsStart) {   // give back one space: an empty bare value matches before it
                            got = v - 1;
                            bare = true;
                        }
                    } else {
                        int e = v;
                        while (e < n && !(s.charAt(e) == '>' || ws(s.charAt(e)))) e++;
                        got = e;
                        bare = true;
                    }
                    if (got >= 0) {
                        p = got;
                        lastWasBare = bare && got > v;   // a non-empty unquoted value ends here (it may end in '/')
                    }
                }
                while (p < n && (ws(s.charAt(p)) || s.charAt(p) == '/')) {
                    p++;
                    lastWasBare = false;
                }
            }
            boolean self = false;
            if (p < n && s.charAt(p) == '>') {
                self = !lastWasBare && s.charAt(p - 1) == '/';
                p++;
            }
            return new int[]{p, self ? 1 : 0};
        }
    }
}
