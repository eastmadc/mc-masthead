package io.github.eastmadc.masthead.edition;

import io.github.eastmadc.masthead.config.Settings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The small file for the in-game side (port of game_json): title, award winners, the edition as plain-text sections for
 * the newspaper book, and recently printed mail. Emoji outside Minecraft's font are dropped; everything is capped.
 */
public final class GameEdition {
    private GameEdition() {}

    static final Pattern BLOCK = Pattern.compile("<(h2|h3|p|li)>(.*?)</\\1>", Pattern.DOTALL);
    /** Longest paragraph carried into the book. The web page is never cut, so this only stops a runaway paragraph. */
    public static final int PARA_MAX = 2000;
    private static final Pattern ASTRAL = Pattern.compile("[\\x{10000}-\\x{10FFFF}\\x{FE0F}\\x{200D}\\x{A7}]");

    /** Plain text: tags removed, entities decoded, astral emoji / VS16 / ZWJ / the section sign (a colour code in game) removed, whitespace collapsed. */
    static String txt(String h) {
        return Py.strip(Py.collapse(ASTRAL.matcher(Py.unescape(Py.stripTags(h, ""))).replaceAll("")));
    }

    /**
     * game_json(). frag = the final (spliced) edition fragment; label = the eyebrow label; printed = printedHistory().
     * Serialize with {@link PyJson#dumps(JsonElement, boolean) PyJson.dumps(x, false)} to get Python's exact bytes.
     */
    public static JsonObject build(Settings s, LocalDate date, JsonObject data, String frag, String label, boolean usedAi, JsonArray printed) {
        String title = "";
        JsonArray sections = new JsonArray();
        Matcher m = BLOCK.matcher(frag);
        while (m.find()) {
            String tag = m.group(1);
            String t = Py.head(txt(m.group(2)), PARA_MAX);
            if (t.isEmpty()) continue;
            // a bold label over a table ("Permit tracker"): the book has no tables, so the label would stand alone
            if (tag.equals("p") && m.group(2).strip().matches("<strong>[^<]*</strong>") && frag.substring(m.end()).stripLeading().startsWith("<table")) continue;
            if (tag.equals("h2") && title.isEmpty()) title = Py.head(t, 80);
            else if (tag.equals("h2") || tag.equals("h3")) {
                JsonObject sec = new JsonObject();
                sec.addProperty("h", Py.head(t, 60));
                sec.add("p", new JsonArray());
                sections.add(sec);
            } else {
                if (sections.isEmpty()) {
                    JsonObject sec = new JsonObject();
                    sec.addProperty("h", "");
                    sec.add("p", new JsonArray());
                    sections.add(sec);
                }
                sections.get(sections.size() - 1).getAsJsonObject().getAsJsonArray("p").add((tag.equals("li") ? "- " : "") + t);
            }
        }
        JsonObject aw = PyJson.objOf(data, "awards"), old = PyJson.objOf(data, "sniffs");
        JsonObject out = new JsonObject();
        out.addProperty("date", date.toString());
        out.addProperty("label", Py.head(txt(label), 60));
        out.addProperty("title", title.isEmpty() ? s.paper().name() : title);
        out.addProperty("ai", usedAi);
        out.add("coolest", award(first(aw.get("golden_egg"), old.get("coolest"))));
        out.add("most_sus", award(first(aw.get("headless_chicken"), old.get("most_sus"))));
        JsonArray secs = new JsonArray();
        // InGame.loadToday keeps 20 sections: leave room for the AI footer
        for (int i = 0; i < Math.min(usedAi ? 19 : 20, sections.size()); i++) secs.add(sections.get(i));
        JsonObject foot = new JsonObject();
        foot.addProperty("h", "");
        foot.add("p", new JsonArray());
        foot.getAsJsonArray("p").add(Pages.AI_FOOTER);
        if (usedAi) secs.add(foot);
        out.add("sections", secs);
        JsonArray pr = new JsonArray();
        if (printed != null) for (int i = 0; i < Math.min(60, printed.size()); i++) pr.add(printed.get(i));
        out.add("printed", pr);
        return out;
    }

    private static JsonElement first(JsonElement a, JsonElement b) {
        return PyJson.truthy(a) ? a : b;
    }

    private static JsonElement award(JsonElement a) {
        if (!PyJson.truthy(a) || !a.isJsonObject() || !PyJson.truthy(a.getAsJsonObject().get("winner"))) return PyJson.nul();
        JsonObject o = new JsonObject();
        o.addProperty("winner", Py.head(PyJson.pyStr(a.getAsJsonObject().get("winner")), 16));
        JsonArray rs = new JsonArray();
        JsonArray src = PyJson.arr(a.getAsJsonObject().get("reasons"));
        for (int i = 0; i < Math.min(3, src.size()); i++) rs.add(Py.head(txt(PyJson.pyStr(src.get(i))), 120));
        o.add("reasons", rs);
        return o;
    }
}
