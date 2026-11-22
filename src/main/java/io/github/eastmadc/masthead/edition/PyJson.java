package io.github.eastmadc.masthead.edition;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.internal.LazilyParsedNumber;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

/**
 * JSON the way Python's json module does it: strict parsing (json.loads), Python-compatible dumps (key order kept,
 * ensure_ascii, separators, float repr), and Python's int/float distinction and truthiness for Gson values.
 * Only Gson 2.11 APIs are used (that is what Paper ships).
 */
public final class PyJson {
    private PyJson() {}

    private static final TypeAdapter<JsonElement> ELEMENT = new Gson().getAdapter(JsonElement.class);

    /** Python json.loads: strict JSON, whole input. Throws IllegalArgumentException on anything else. */
    public static JsonElement loads(String s) {
        try {
            JsonReader r = new JsonReader(new StringReader(s));
            r.setStrictness(Strictness.STRICT);
            JsonElement e = ELEMENT.read(r);
            if (r.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("extra data");
            return e;
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("bad json: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- number typing

    /** True if this is a JSON number that Python would load as a float (or a Java Double/Float). */
    public static boolean isFloat(JsonPrimitive p) {
        Number n = p.getAsNumber();
        if (n instanceof Double || n instanceof Float || n instanceof BigDecimal) return true;
        if (n instanceof LazilyParsedNumber) {
            String t = n.toString();
            return t.indexOf('.') >= 0 || t.indexOf('e') >= 0 || t.indexOf('E') >= 0;
        }
        return false;
    }

    /** True for numbers (not booleans). */
    public static boolean isNum(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    public static boolean isStr(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    public static boolean isObj(JsonElement e) {
        return e != null && e.isJsonObject();
    }

    public static boolean isArr(JsonElement e) {
        return e != null && e.isJsonArray();
    }

    public static double dbl(JsonElement e) {
        return isNum(e) ? e.getAsDouble() : 0;
    }

    public static BigInteger bigInt(JsonPrimitive p) {
        Number n = p.getAsNumber();
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) return BigInteger.valueOf(n.longValue());
        if (n instanceof BigInteger b) return b;
        return new BigDecimal(n.toString()).toBigInteger();
    }

    /** Python truthiness of a JSON value (None, False, 0, 0.0, "", [], {} are false). */
    public static boolean truthy(JsonElement e) {
        if (e == null || e.isJsonNull()) return false;
        if (e.isJsonArray()) return !e.getAsJsonArray().isEmpty();
        if (e.isJsonObject()) return !e.getAsJsonObject().isEmpty();
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isString()) return !p.getAsString().isEmpty();
        return p.getAsDouble() != 0;
    }

    public static JsonObject obj(JsonElement e) {
        return isObj(e) ? e.getAsJsonObject() : new JsonObject();
    }

    public static JsonArray arr(JsonElement e) {
        return isArr(e) ? e.getAsJsonArray() : new JsonArray();
    }

    /** d.get(k) or {} */
    public static JsonObject objOf(JsonObject d, String k) {
        JsonElement e = d == null ? null : d.get(k);
        return truthy(e) && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    public static String strOr(JsonElement e, String dflt) {
        return isStr(e) ? e.getAsString() : dflt;
    }

    /** Python str(x) for a JSON value (repr for containers, like Python prints a dict). */
    public static String pyStr(JsonElement e) {
        if (e == null || e.isJsonNull()) return "None";
        if (isStr(e)) return e.getAsString();
        return pyRepr(e);
    }

    static String pyRepr(JsonElement e) {
        if (e == null || e.isJsonNull()) return "None";
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean() ? "True" : "False";
            if (p.isNumber()) return numStr(p);
            String s = p.getAsString();
            boolean dq = s.indexOf('\'') >= 0 && s.indexOf('"') < 0;
            char q = dq ? '"' : '\'';
            StringBuilder b = new StringBuilder().append(q);
            for (int i = 0; i < s.length(); ) {
                int c = s.codePointAt(i);
                i += Character.charCount(c);
                if (c == q || c == '\\') b.append('\\').appendCodePoint(c);
                else if (c == '\n') b.append("\\n");
                else if (c == '\r') b.append("\\r");
                else if (c == '\t') b.append("\\t");
                else if (!Py.isPrintable(c)) {
                    if (c < 0x100) b.append(String.format("\\x%02x", c));
                    else if (c < 0x10000) b.append(String.format("\\u%04x", c));
                    else b.append(String.format("\\U%08x", c));
                } else b.appendCodePoint(c);
            }
            return b.append(q).toString();
        }
        StringBuilder b = new StringBuilder();
        if (e.isJsonArray()) {
            b.append('[');
            boolean first = true;
            for (JsonElement x : e.getAsJsonArray()) {
                if (!first) b.append(", ");
                first = false;
                b.append(pyRepr(x));
            }
            return b.append(']').toString();
        }
        b.append('{');
        boolean first = true;
        for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
            if (!first) b.append(", ");
            first = false;
            b.append(pyRepr(new JsonPrimitive(en.getKey()))).append(": ").append(pyRepr(en.getValue()));
        }
        return b.append('}').toString();
    }

    /** str() of a JSON number as Python would hold it (int or float). */
    static String numStr(JsonPrimitive p) {
        if (isFloat(p)) return Py.repr(p.getAsDouble());
        return bigInt(p).toString();
    }

    // ---------------------------------------------------------------- dumps

    /** json.dumps(x, separators=(',', ':')) with ensure_ascii=True. */
    public static String dumpsCompact(JsonElement e) {
        StringBuilder b = new StringBuilder();
        dump(b, e, ",", ":", true);
        return b.toString();
    }

    /** json.dumps(x, ensure_ascii=asciiOnly) with the default separators (', ', ': '). */
    public static String dumps(JsonElement e, boolean asciiOnly) {
        StringBuilder b = new StringBuilder();
        dump(b, e, ", ", ": ", asciiOnly);
        return b.toString();
    }

    private static void dump(StringBuilder b, JsonElement e, String item, String kv, boolean ascii) {
        if (e == null || e.isJsonNull()) {
            b.append("null");
        } else if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) b.append(p.getAsBoolean() ? "true" : "false");
            else if (p.isNumber()) {
                if (isFloat(p)) {
                    double d = p.getAsDouble();
                    b.append(Double.isNaN(d) ? "NaN" : Double.isInfinite(d) ? (d > 0 ? "Infinity" : "-Infinity") : Py.repr(d));
                } else b.append(bigInt(p));
            } else str(b, p.getAsString(), ascii);
        } else if (e.isJsonArray()) {
            b.append('[');
            boolean first = true;
            for (JsonElement x : e.getAsJsonArray()) {
                if (!first) b.append(item);
                first = false;
                dump(b, x, item, kv, ascii);
            }
            b.append(']');
        } else {
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
                if (!first) b.append(item);
                first = false;
                str(b, en.getKey(), ascii);
                b.append(kv);
                dump(b, en.getValue(), item, kv, ascii);
            }
            b.append('}');
        }
    }

    private static void str(StringBuilder b, String s, boolean ascii) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20 || (ascii && c > 0x7e)) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }

    static JsonElement nul() {
        return JsonNull.INSTANCE;
    }
}
