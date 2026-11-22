package io.github.eastmadc.masthead.facts;

import io.github.eastmadc.masthead.config.Settings;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Golden test without JUnit: build the facts for a day and compare them key by key with the Python data/DATE.json.
 *
 *   java GoldenTest DATA_FOLDER EXPECTED.json DATE [--time HH:MM] [--access-log PATH] [--public-url URL]
 *                   [--skip key,key] [--only key,key]
 *
 * Settings are the test paper's (owner TownFounder with the Python OWNER_LORE, watch animal chicken).
 * Values are compared exactly, including order and number text (5 vs 5.0). recent_editions is compared with whitespace
 * collapsed. Exit code 0 = no differences in the compared keys.
 */
public final class GoldenTest {
    static final List<String> OWNER_LORE = List.of(
            "The Torch Trail: TownFounder lit every cave under spawn with one long torch trail and walked out with a stack of diamonds. Nobody needs x-ray with that many torches.",
            "TownFounder once flooded the pumpkin patch at spawn while digging a canal, and spent the evening bailing it out with buckets.",
            "The first lighthouse on the sand bar is his, and he repaints it every time someone brings it up.",
            "His parrot has outlived three of his horses.");

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: GoldenTest DATA_FOLDER EXPECTED.json DATE [--time HH:MM] [--access-log PATH] [--public-url URL] [--skip k,k] [--only k,k]");
            System.exit(2);
        }
        Path data = Path.of(args[0]), expectedFile = Path.of(args[1]);
        LocalDate date = LocalDate.parse(args[2]);
        LocalTime time = LocalTime.of(5, 30);
        String accessLog = "", publicUrl = "";
        Set<String> skip = new LinkedHashSet<>(List.of("server", "mailbag_candidates", "cold_cases", "recent_words")), only = null;
        for (int i = 3; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--time" -> time = LocalTime.parse(args[i + 1]);
                case "--access-log" -> accessLog = args[i + 1];
                case "--public-url" -> publicUrl = args[i + 1];
                case "--skip" -> skip.addAll(List.of(args[i + 1].split(",")));
                case "--only" -> only = new LinkedHashSet<>(List.of(args[i + 1].split(",")));
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        Settings d = Settings.defaults();
        Settings s = new Settings(d.paper(), d.tone(), new Settings.Owner("TownFounder", d.owner().deskTitle(), OWNER_LORE, true, "he"),
                d.privacy(), d.schedule(), d.writer(),
                new Settings.Web(d.web().mode(), d.web().bind(), d.web().port(), d.web().folder(), publicUrl, accessLog),
                new Settings.Features(true, true, !accessLog.isEmpty(), true, 3, ""), d.alerts());

        JsonObject got = new FactsBuilder(data, s).build(date, time);
        JsonObject want = JsonParser.parseString(Files.readString(expectedFile, StandardCharsets.UTF_8)).getAsJsonObject();

        Gson g = new Gson();
        List<String> diffs = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(want.keySet());
        keys.addAll(got.keySet());
        int same = 0;
        for (String k : keys) {
            if (skip.contains(k) || (only != null && !only.contains(k))) {
                System.out.println("  skip  " + k);
                continue;
            }
            JsonElement w = want.get(k), o = got.get(k);
            if (k.equals("recent_editions")) {
                w = squash(w);
                o = squash(o);
            }
            List<String> here = new ArrayList<>();
            diff(k, w, o, here, g);
            if (here.isEmpty()) {
                same++;
                System.out.println("  same  " + k);
            } else {
                System.out.println("  DIFF  " + k + " (" + here.size() + ")");
                here.stream().limit(10).forEach(x -> System.out.println("          " + x));
                diffs.addAll(here);
            }
        }
        System.out.println(same + " keys identical, " + diffs.size() + " differences");
        System.exit(diffs.isEmpty() ? 0 : 1);
    }

    /** recent_editions with all whitespace runs collapsed and ends trimmed. */
    private static JsonElement squash(JsonElement e) {
        if (e == null || !e.isJsonArray()) return e;
        JsonArray a = new JsonArray();
        for (JsonElement x : e.getAsJsonArray()) a.add(x.getAsString().replaceAll("\\s+", " ").strip());
        return a;
    }

    /** Exact comparison: keys and their order, array order, and the literal JSON text of every value. */
    private static void diff(String path, JsonElement w, JsonElement o, List<String> out, Gson g) {
        if (w == null || o == null) {
            if (w != o) out.add(path + ": " + (w == null ? "missing in Python, Java has " : "missing in Java, Python has ") + cut(g.toJson(w == null ? o : w)));
            return;
        }
        if (w.isJsonObject() && o.isJsonObject()) {
            List<String> wk = new ArrayList<>(w.getAsJsonObject().keySet()), ok = new ArrayList<>(o.getAsJsonObject().keySet());
            int before = out.size();
            Set<String> all = new LinkedHashSet<>(wk);
            all.addAll(ok);
            for (String k : all) diff(path + "." + k, w.getAsJsonObject().get(k), o.getAsJsonObject().get(k), out, g);
            if (out.size() == before && !wk.equals(ok)) out.add(path + ": key order differs: python " + wk + " java " + ok);
            return;
        }
        if (w.isJsonArray() && o.isJsonArray()) {
            JsonArray wa = w.getAsJsonArray(), oa = o.getAsJsonArray();
            if (wa.size() != oa.size()) {
                out.add(path + ": length python " + wa.size() + " java " + oa.size() + "\n            python " + cut(g.toJson(wa)) + "\n            java   " + cut(g.toJson(oa)));
                return;
            }
            for (int i = 0; i < wa.size(); i++) diff(path + "[" + i + "]", wa.get(i), oa.get(i), out, g);
            return;
        }
        String ws = g.toJson(w), os = g.toJson(o);
        if (!ws.equals(os)) out.add(path + ": python " + cut(ws) + " java " + cut(os));
    }

    private static String cut(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
