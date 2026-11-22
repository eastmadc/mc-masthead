package io.github.eastmadc.masthead.edition;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small helpers that behave exactly like the Python built-ins the reference Python script relies on:
 * str/float repr, round(), str.isspace/strip/isprintable/splitlines, html.escape/unescape, "{:,}" formatting.
 * Package-private: the edition code is a faithful port and these keep its outputs byte-identical.
 */
final class Py {
    private Py() {}

    // ---------------------------------------------------------------- numbers

    /** Python repr(float). */
    static String repr(double x) {
        if (Double.isNaN(x)) return "nan";
        if (Double.isInfinite(x)) return x > 0 ? "inf" : "-inf";
        if (x == 0) return (1 / x < 0) ? "-0.0" : "0.0";
        BigDecimal bd = new BigDecimal(Double.toString(Math.abs(x))).stripTrailingZeros();   // shortest round-trip digits (JDK 19+)
        String digits = bd.unscaledValue().toString();
        int decpt = digits.length() - bd.scale();          // value = 0.digits * 10^decpt
        int e = decpt - 1;
        String sign = x < 0 ? "-" : "";
        if (e >= -4 && e < 16) {
            String plain = bd.toPlainString();
            if (plain.indexOf('.') < 0) plain += ".0";
            return sign + plain;
        }
        String mant = digits.substring(0, 1) + (digits.length() > 1 ? "." + digits.substring(1) : "");
        String exp = String.format(Locale.ROOT, "%02d", Math.abs(e));
        return sign + mant + "e" + (e < 0 ? "-" : "+") + exp;
    }

    /** Python round(x) for a float: an int, ties to even. */
    static BigInteger roundInt(double x) {
        return new BigDecimal(Math.rint(x)).toBigInteger();
    }

    /** Python round(x, n) for a float: a float, correctly rounded (ties to even on the exact binary value). */
    static double round(double x, int n) {
        if (Double.isNaN(x) || Double.isInfinite(x) || x == 0) return x;
        return new BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** Python f"{n:,}" for an int. */
    static String comma(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    static String comma(BigInteger n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** Python f"{int(round(v)):,}" */
    static String commaRound(double v) {
        return comma(roundInt(v));
    }

    /** Python f"{v:,.1f}" */
    static String comma1(double v) {
        BigDecimal b = new BigDecimal(v).setScale(1, RoundingMode.HALF_EVEN);
        BigInteger ip = b.abs().toBigInteger();
        String frac = b.abs().toPlainString();
        frac = frac.substring(frac.indexOf('.'));
        return (b.signum() < 0 ? "-" : "") + comma(ip) + frac;
    }

    /** Python f"{v:,}" for a float. */
    static String commaRepr(double v) {
        String r = repr(v);
        if (r.indexOf('e') >= 0 || r.indexOf('n') >= 0) return r;
        boolean neg = r.startsWith("-");
        String body = neg ? r.substring(1) : r;
        int dot = body.indexOf('.');
        return (neg ? "-" : "") + comma(new BigInteger(body.substring(0, dot))) + body.substring(dot);
    }

    /** Python f"{v:.0f}" */
    static String fixed0(double v) {
        return new BigDecimal(v).setScale(0, RoundingMode.HALF_EVEN).toPlainString();
    }

    // ---------------------------------------------------------------- characters

    /** Python str.isspace() for one code point (also what re's \s matches in a str pattern). */
    static boolean isSpace(int c) {
        return (c >= 0x09 && c <= 0x0d) || (c >= 0x1c && c <= 0x20) || c == 0x85 || c == 0xa0 || c == 0x1680
                || (c >= 0x2000 && c <= 0x200a) || c == 0x2028 || c == 0x2029 || c == 0x202f || c == 0x205f || c == 0x3000;
    }

    /** Python str.isprintable() for one code point. */
    static boolean isPrintable(int c) {
        if (c == ' ') return true;
        int t = Character.getType(c);
        return !(t == Character.CONTROL || t == Character.FORMAT || t == Character.SURROGATE || t == Character.PRIVATE_USE
                || t == Character.UNASSIGNED || t == Character.LINE_SEPARATOR || t == Character.PARAGRAPH_SEPARATOR
                || t == Character.SPACE_SEPARATOR);
    }

    /** Python str.isalnum() for one code point. */
    static boolean isAlnum(int c) {
        int t = Character.getType(c);
        return Character.isLetter(c) || t == Character.DECIMAL_DIGIT_NUMBER || t == Character.LETTER_NUMBER
                || t == Character.OTHER_NUMBER;
    }

    /** Python str.strip() */
    static String strip(String s) {
        int a = 0, b = s.length();
        while (a < b) {
            int c = s.codePointAt(a);
            if (!isSpace(c)) break;
            a += Character.charCount(c);
        }
        while (b > a) {
            int c = s.codePointBefore(b);
            if (!isSpace(c)) break;
            b -= Character.charCount(c);
        }
        return s.substring(a, b);
    }

    static String rstrip(String s) {
        int b = s.length();
        while (b > 0) {
            int c = s.codePointBefore(b);
            if (!isSpace(c)) break;
            b -= Character.charCount(c);
        }
        return s.substring(0, b);
    }

    /** Python re.sub(r'\s+', ' ', s) */
    static String collapse(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean inWs = false;
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (isSpace(c)) {
                if (!inWs) out.append(' ');
                inWs = true;
            } else {
                out.appendCodePoint(c);
                inWs = false;
            }
        }
        return out.toString();
    }

    /** Python len(s) (code points). */
    static int len(String s) {
        return s.codePointCount(0, s.length());
    }

    /** Python s[:n] (code points). */
    static String head(String s, int n) {
        if (n <= 0) return "";
        if (s.length() <= n) return s;   // fast path: fewer chars than n means fewer code points too
        int cps = s.codePointCount(0, s.length());
        if (cps <= n) return s;
        return s.substring(0, s.offsetByCodePoints(0, n));
    }

    /** Python lower(). */
    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** The reference Python script's clean(s, n): unprintable characters become spaces, then cut to n characters. */
    static String clean(String s, int n) {
        StringBuilder out = new StringBuilder(Math.min(s.length(), n * 2 + 2));
        int count = 0;
        for (int i = 0; i < s.length() && count < n; ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (isPrintable(c)) out.appendCodePoint(c); else out.append(' ');
            count++;
        }
        return out.toString();
    }

    /** Python str.splitlines() */
    static List<String> splitlines(String s) {
        List<String> out = new ArrayList<>();
        int start = 0, i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            boolean br = c == '\n' || c == '\r' || c == 0x0b || c == 0x0c || c == 0x1c || c == 0x1d || c == 0x1e || c == 0x85
                    || c == 0x2028 || c == 0x2029;
            if (br) {
                out.add(s.substring(start, i));
                if (c == '\r' && i + 1 < n && s.charAt(i + 1) == '\n') i++;
                start = i + 1;
            }
            i++;
        }
        if (start < n) out.add(s.substring(start));
        return out;
    }

    // ---------------------------------------------------------------- html

    /** Python html.escape(s, quote) */
    static String escape(String s, boolean quote) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append(quote ? "&quot;" : "\"");
                case '\'' -> out.append(quote ? "&#x27;" : "'");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    static String escape(String s) {
        return escape(s, true);
    }

    private static final Pattern CHARREF = Pattern.compile("&(#[0-9]+;?|#[xX][0-9a-fA-F]+;?|[^\\t\\n\\f <&#;]{1,32};?)");

    /** Python html.unescape(s) (full HTML5 table, same numeric rules). */
    static String unescape(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = CHARREF.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        int last = 0;
        while (m.find()) {
            out.append(s, last, m.start());
            out.append(replaceCharref(m.group(1)));
            last = m.end();
        }
        out.append(s, last, s.length());
        return out.toString();
    }

    private static String replaceCharref(String s) {
        if (s.charAt(0) == '#') {
            BigInteger num;
            if (s.length() > 1 && (s.charAt(1) == 'x' || s.charAt(1) == 'X')) num = new BigInteger(stripSemi(s.substring(2)), 16);
            else num = new BigInteger(stripSemi(s.substring(1)));
            if (num.compareTo(BigInteger.valueOf(0x10FFFF)) > 0) return "�";
            int n = num.intValue();
            String inv = Entities.INVALID_CHARREFS.get(n);
            if (inv != null) return inv;
            if (n >= 0xD800 && n <= 0xDFFF) return "�";
            if (Entities.INVALID_CODEPOINTS.contains(n)) return "";
            return new String(Character.toChars(n));
        }
        String v = Entities.HTML5.get(s);
        if (v != null) return v;
        // longest matching prefix, measured in code points as Python does
        int cps = s.codePointCount(0, s.length());
        for (int x = cps - 1; x > 1; x--) {
            String pre = s.substring(0, s.offsetByCodePoints(0, x));
            String pv = Entities.HTML5.get(pre);
            if (pv != null) return pv + s.substring(pre.length());
        }
        return "&" + s;
    }

    private static String stripSemi(String s) {
        int e = s.length();
        while (e > 0 && s.charAt(e - 1) == ';') e--;
        return s.substring(0, e);
    }

    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    /** re.sub(r'<[^>]+>', repl, s) */
    static String stripTags(String s, String repl) {
        return TAG.matcher(s).replaceAll(Matcher.quoteReplacement(repl));
    }

    // ---------------------------------------------------------------- misc

    /** Python's NAME regex (player names): ^\.?[A-Za-z0-9_]{3,16}$ (strict: no trailing newline accepted). */
    static final Pattern NAME = Pattern.compile("\\.?[A-Za-z0-9_]{3,16}");

    static boolean isName(String s) {
        return s != null && NAME.matcher(s).matches();
    }

    /** strftime('%a %b %-d, %Y') in the C locale; uk (paper.language en-GB): '%a %-d %b %Y'. */
    static String dayLabel(java.time.LocalDate d, boolean uk) {
        return d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH) + " " + monthDay(d, uk);
    }

    /** strftime('%b %-d, %Y'); uk: '%-d %b %Y' */
    static String monthDay(java.time.LocalDate d, boolean uk) {
        String mon = d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH);
        return uk ? d.getDayOfMonth() + " " + mon + " " + d.getYear() : mon + " " + d.getDayOfMonth() + ", " + d.getYear();
    }
}
