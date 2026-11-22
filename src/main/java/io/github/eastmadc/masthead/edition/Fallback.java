package io.github.eastmadc.masthead.edition;

import io.github.eastmadc.masthead.config.Settings;
import io.github.eastmadc.masthead.construction.Kinds;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The plain "just the facts" edition used when the writer is unavailable or a draft fails its checks (port of fallback).
 * The word gate runs here too: a death line or achievement with a listed word is left out.
 */
public final class Fallback {
    private Fallback() {}

    public static String edition(Settings s, JsonObject data) {
        List<String> names = new ArrayList<>();
        for (String n : PyJson.objOf(data, "season_totals").keySet()) if (Py.isName(n)) names.add(n);
        String lang = StylePrompt.language(s);
        java.util.function.Predicate<String> clean = t -> WordGate.bad(t, names, false, lang).isEmpty();
        StringBuilder p = new StringBuilder();
        p.append("<h2>").append(Py.escape(s.paper().name())).append("</h2>")
                .append(s.writer().mode().equals("none") ? "<p><em>(Just the facts today.)</em></p>" : "<p><em>(No robot-written paper today, so here are just the facts.)</em></p>");
        List<String> on = new ArrayList<>();
        for (JsonElement e : PyJson.arr(data.get("players_on"))) on.add(PyJson.pyStr(e));
        p.append("<h3>Who pulled up</h3><p>").append(on.isEmpty() ? "Nobody. The villagers had the server to themselves." : Py.escape(String.join(", ", on))).append("</p>");
        JsonObject t = PyJson.objOf(data, "today");
        if (!t.isEmpty()) {
            List<Map.Entry<String, JsonElement>> rows = new ArrayList<>(t.entrySet());
            rows.sort((a, b) -> Double.compare(PyJson.dbl(PyJson.obj(b.getValue()).get("play_min")), PyJson.dbl(PyJson.obj(a.getValue()).get("play_min"))));
            StringBuilder r = new StringBuilder();
            for (Map.Entry<String, JsonElement> e : rows) {
                JsonObject x = PyJson.obj(e.getValue());
                r.append("<tr><td>").append(Py.escape(e.getKey())).append("</td><td>").append(x.has("play_min") ? trunc(x.get("play_min")) : "-").append("</td><td>")
                        .append(trunc(x.get("mob_kills"))).append("</td><td>").append(trunc(x.get("blocks_mined"))).append("</td><td>")
                        .append(trunc(x.get("deaths"))).append("</td></tr>");
            }
            p.append("<h3>Highlights</h3><table><thead><tr><th>Player</th><th>Minutes</th><th>Mob kills</th><th>Blocks mined</th><th>Deaths</th></tr></thead><tbody>")
                    .append(r).append("</tbody></table>");
        }
        if (PyJson.truthy(data.get("deaths"))) {
            p.append("<h3>Kill feed</h3><ul>");
            int i = 0;
            for (JsonElement d : PyJson.arr(data.get("deaths"))) {
                if (i++ >= 15) break;
                if (!clean.test(PyJson.pyStr(d))) continue;
                p.append("<li>").append(Py.escape(PyJson.pyStr(d))).append("</li>");
            }
            p.append("</ul>");
        }
        if (PyJson.truthy(data.get("advancements"))) {
            p.append("<h3>Achievements unlocked</h3><ul>");
            int i = 0;
            for (JsonElement a : PyJson.arr(data.get("advancements"))) {
                if (i++ >= 20) break;
                var arr = PyJson.arr(a);
                if (arr.size() < 2 || !clean.test(PyJson.pyStr(arr.get(1)))) continue;
                p.append("<li>").append(Py.escape(PyJson.pyStr(arr.get(0)))).append(": ").append(Py.escape(PyJson.pyStr(arr.get(1)))).append("</li>");
            }
            p.append("</ul>");
        }
        JsonObject bld = PyJson.objOf(data, "building");
        if (!bld.isEmpty()) {
            p.append("<h3>Building Report</h3><p>").append(Py.escape("Blocks placed on the server: " + Py.comma(truncL(bld.get("blocks_today"))) + "."))
                    .append("</p><ul>");
            for (JsonElement e : PyJson.arr(bld.get("builders"))) {
                JsonObject x = PyJson.obj(e);
                List<String> mats = new ArrayList<>();
                for (JsonElement m : PyJson.arr(x.get("materials"))) mats.add(PyJson.pyStr(PyJson.obj(m).get("what")));
                p.append("<li>").append(Py.escape(PyJson.pyStr(x.get("name")) + ": " + Py.comma(truncL(x.get("blocks_today"))) + " blocks"
                        + (mats.isEmpty() ? "" : " (" + String.join(", ", mats) + ")"))).append("</li>");
            }
            p.append("</ul>");
        }
        if (data.has("construction")) p.append(construction(s, PyJson.objOf(data, "construction")));
        JsonObject srv = PyJson.objOf(data, "server");
        JsonObject sn = PyJson.objOf(data, "awards");
        String[][] awards = {{"golden_egg", s.paper().goodAward()}, {"headless_chicken", s.paper().badAward()}};
        for (String[] a : awards) {
            JsonElement w = sn.get(a[0]);
            p.append("<h3>").append(Py.escape(a[1])).append("</h3><p>");
            if (PyJson.truthy(w) && w.isJsonObject()) {
                List<String> reasons = new ArrayList<>();
                for (JsonElement r : PyJson.arr(w.getAsJsonObject().get("reasons"))) reasons.add(PyJson.pyStr(r));
                p.append(Py.escape(PyJson.pyStr(w.getAsJsonObject().get("winner")) + ": " + String.join("; ", reasons)));
            } else p.append("Nobody earned it today.");
            p.append("</p>");
        }
        JsonObject o = PyJson.objOf(PyJson.objOf(data, "owner"), "season");
        if (!o.isEmpty() && StylePrompt.hasOwner(s)) {
            p.append("<h3>Owner&#39;s Debrief: ").append(Py.escape(StylePrompt.owner(s))).append("</h3><p>")
                    .append(Py.escape("Season so far: " + trunc(o.get("deaths")) + " deaths, " + Py.comma(truncL(o.get("mob_kills"))) + " mob kills, "
                            + Py.comma(truncL(o.get("jumps"))) + " jumps, " + Py.comma(truncL(o.get("eggs"))) + " eggs thrown, "
                            + Py.comma(truncL(o.get("trades"))) + " villager trades."))
                    .append("</p>");
        }
        if (srv.has("tps_avg") || srv.has("tps_min"))   // no sample yet (a first paper): no "TPS avg ?, lowest ?"
            p.append("<h3>Server health</h3><p>").append(Py.escape("TPS avg " + q(srv.get("tps_avg")) + ", lowest " + q(srv.get("tps_min")) + (srv.has("backup") ? "; backup: " + q(srv.get("backup")) : "")))
                    .append("</p>");
        return p.toString();
    }

    /** The construction section, built only from allowed fields (no tracker, no critic). */
    static String construction(Settings s, JsonObject c) {
        StringBuilder b = new StringBuilder("<h3>").append(Py.escape(s.construction().title())).append("</h3><ul>");
        int items = 0;
        if (PyJson.truthy(c.get("opening"))) {
            items++;
            String cm = StylePrompt.commission(s);
            long n = truncL(c.get("survey_builds"));
            b.append("<li>").append(Py.escape(Character.toUpperCase(cm.charAt(0)) + cm.substring(1) + " is now open."
                    + (n > 0 ? " Its first full survey of the town registered " + Py.comma(n) + (n == 1 ? " build" : " builds") + ", all listed on the Town Survey page." : "")))
                    .append("</li>");
        }
        for (JsonElement el : PyJson.arr(c.get("entries"))) {
            if (!el.isJsonObject()) continue;
            String line = entryLine(el.getAsJsonObject());
            if (line != null) line += inside(el.getAsJsonObject());
            if (line != null) { b.append("<li>").append(Py.escape(line)).append("</li>"); items++; }
        }
        return items == 0 ? "" : b.append("</ul>").toString();   // a tracker-only Sunday: no empty heading
    }

    static String entryLine(JsonObject e) {
        String tag = PyJson.pyStr(e.get("tag")), kind = PyJson.pyStr(e.get("kind"));
        List<String> bs = new ArrayList<>();
        for (JsonElement x : PyJson.arr(e.get("builders"))) bs.add(PyJson.pyStr(x));
        String crew = String.join(", ", bs), permit = "Permit #" + truncL(e.get("permit"));
        if (e.get("name") != null && !e.get("name").isJsonNull()) permit += " \"" + PyJson.pyStr(e.get("name")) + "\"";
        long total = truncL(e.get("blocks_total"));
        boolean existing = PyJson.truthy(e.get("existing"));
        String article = kindArticle(kind);
        return switch (tag) {
            case "survey" -> "ON THE REGISTER: " + permit + ", " + crew + ": " + article + " of " + Py.comma(total) + " blocks, standing before the Commission opened.";
            case "permit" -> existing ? "PERMIT FILED (ADDITION): " + permit + ", " + crew + ": " + Py.comma(total) + " blocks added."
                    : "PERMIT FILED: " + permit + ", " + crew + ". What the Commission believes is " + article + ": " + Py.comma(total) + " blocks"
                    + (PyJson.arr(e.get("materials")).isEmpty() ? "" : ", mostly " + PyJson.pyStr(PyJson.obj(PyJson.arr(e.get("materials")).get(0)).get("what"))) + ".";
            case "new_resident" -> "PERMIT FILED (NEW RESIDENT): " + permit + ", " + crew + " broke ground on a first home: " + Py.comma(total) + " blocks.";
            case "rebuild" -> "PERMIT RE-FILED: " + permit + " (" + crew + ") is back under construction: " + Py.comma(total) + " blocks.";
            case "progress" -> "DAY " + truncL(e.get("day")) + ": " + permit + " (" + crew + ") " + (existing ? "has added " : "passed ")
                    + Py.comma(truncL(e.get("milestone_blocks"))) + " blocks: " + Py.comma(total) + (existing ? " added in all." : " in all.");
            case "ribbon" -> "RIBBON CUTTING: " + permit + " (" + crew + ") is finished: " + Py.comma(total) + " blocks.";
            case "incident" -> {
                JsonObject inc = PyJson.objOf(e, "incident");
                String cause = switch (PyJson.pyStr(inc.get("cause"))) {
                    case "creeper" -> "a creeper";
                    case "tnt" -> "TNT";
                    case "fire" -> "a fire";
                    case "wither" -> "a wither";
                    default -> "an explosion";
                };
                yield "INCIDENT REPORT: " + cause + " removed " + Py.comma(truncL(inc.get("blocks"))) + " blocks from " + permit + " (" + crew + ").";
            }
            case "public_works" -> {
                List<String> parts = new ArrayList<>();
                for (Map.Entry<String, JsonElement> pe : PyJson.objOf(e, "parts").entrySet()) parts.add(truncL(pe.getValue()) + " " + pe.getKey().replace('_', ' '));
                yield "PUBLIC WORKS: " + permit + " (" + crew + "): " + (parts.isEmpty() ? Py.comma(total) + " blocks" : String.join(", ", parts)) + ".";
            }
            case "demolition" -> "DEMOLITION NOTICE: " + permit + " (" + crew + ") was taken down by its builder" + (bs.size() > 1 ? "s." : ".");
            case "renovation" -> "RENOVATION: " + permit + " (" + crew + ") was renovated.";
            default -> null;
        };
    }

    /** " Inside: 17 lecterns for librarian trading, 3 beds." from the facts' features, or "". */
    static String inside(JsonObject e) {
        List<String> l = new ArrayList<>();
        for (JsonElement f : PyJson.arr(e.get("features"))) {
            JsonObject o = PyJson.obj(f);
            l.add(Py.comma(truncL(o.get("n"))) + " " + PyJson.pyStr(o.get("of")) + (o.has("for") ? " for " + PyJson.pyStr(o.get("for")) : ""));
        }
        return l.isEmpty() ? "" : " Inside: " + String.join(", ", l) + ".";
    }

    /** "a house", "an auto farm" (the award-text words for a kind, with their article). */
    private static String kindArticle(String kind) {
        String d = Kinds.display(kind);
        return ("aeiou".indexOf(d.charAt(0)) >= 0 ? "an " : "a ") + d;
    }

    private static String q(JsonElement e) {
        return e == null ? "?" : PyJson.pyStr(e);
    }

    /** int(x) of a number (truncates), 0 when missing. */
    static long truncL(JsonElement e) {
        if (!PyJson.isNum(e)) return 0;
        return PyJson.isFloat(e.getAsJsonPrimitive()) ? (long) e.getAsDouble() : PyJson.bigInt(e.getAsJsonPrimitive()).longValue();
    }

    private static String trunc(JsonElement e) {
        return String.valueOf(truncL(e));
    }
}
