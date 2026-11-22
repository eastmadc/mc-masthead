package io.github.eastmadc.masthead.facts;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Small helpers that copy Python's behaviour exactly, so the facts match the reference script number for number. */
final class Py {
    private Py() {}

    /** Player names: Java edition names, or Bedrock names with a leading dot. */
    static final Pattern NAME = Pattern.compile("\\.?[A-Za-z0-9_]{3,16}");

    static boolean isName(String s) {
        return s != null && NAME.matcher(s).matches();
    }

    /** Python str.isprintable() for one code point. */
    static boolean printable(int cp) {
        if (cp == ' ') return true;
        return switch (Character.getType(cp)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
                 Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR -> false;
            default -> true;
        };
    }

    /** Python clean(s, n): non-printable characters become spaces, then keep the first n code points. */
    static String clean(String s, int n) {
        StringBuilder b = new StringBuilder();
        int count = 0;
        for (int i = 0; i < s.length() && count < n; count++) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (printable(cp)) b.appendCodePoint(cp); else b.append(' ');
        }
        return b.toString();
    }

    /** First n code points (Python s[:n]). */
    static String head(String s, int n) {
        if (s.codePointCount(0, s.length()) <= n) return s;
        return s.substring(0, s.offsetByCodePoints(0, n));
    }

    static int len(String s) {
        return s.codePointCount(0, s.length());
    }

    /** Python round(x, 1): exact half-even rounding of the binary value. */
    static double round1(double x) {
        return Double.parseDouble(new BigDecimal(x).setScale(1, RoundingMode.HALF_EVEN).toPlainString());
    }

    /** Python round(x) on a float: half-even, returns an int. */
    static long round(double x) {
        return (long) Math.rint(x);
    }

    /** Python round(v) for an int-or-float value. */
    static long round(Number v) {
        return v instanceof Double d ? round(d.doubleValue()) : v.longValue();
    }

    /** Python f'{n:,}'. */
    static String commas(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** A JSON number as Python would load it: Long for integers, Double for anything with a dot or exponent. */
    static Number number(JsonPrimitive p) {
        String t = p.getAsString();
        if (t.matches("-?\\d+")) {
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException e) {
                return p.getAsDouble();
            }
        }
        return p.getAsDouble();
    }

    static boolean isNumber(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    static JsonPrimitive json(Number n) {
        return new JsonPrimitive(n);
    }

    /** Reads at most cap bytes of a UTF-8 file (bad bytes become U+FFFD). Missing file = null. */
    static String readCapped(Path f, int cap) {
        if (!Files.isRegularFile(f)) return null;
        try (InputStream in = Files.newInputStream(f)) {
            return new String(in.readNBytes(cap), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Lines of a capped file (Python splitlines on \n, \r\n, \r). */
    static List<String> lines(Path f, int cap, int maxLines) {
        String s = readCapped(f, cap);
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String l : s.split("\r\n|\r|\n", -1)) {
            if (out.size() >= maxLines) break;
            out.add(l);
        }
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    /** One JSON line, or null if it is not valid JSON. */
    static JsonElement parse(String line) {
        try {
            return JsonParser.parseString(line);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True if text names any hidden player (whole word, any case). */
    static boolean mentions(String text, Set<String> hiddenLower) {
        if (hiddenLower.isEmpty()) return false;
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9_.]+")) {
            if (hiddenLower.contains(w) || hiddenLower.contains(w.replaceAll("^\\.+|\\.+$", ""))) return true;
        }
        return false;
    }
}
