package io.github.eastmadc.masthead.facts;

import io.github.eastmadc.masthead.config.Settings;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Synthetic checks for the parts the golden test cannot reach: hidden players, a non-chicken watch animal, the mailroom,
 * health, and the built-in access log. Plain main(); exit 1 on the first failure. Usage: java SmokeTest [TMP_DIR]
 */
public final class SmokeTest {
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path dir = args.length > 0 ? Files.createDirectories(Path.of(args[0])) : Files.createTempDirectory("facts-smoke");
        LocalDate day = LocalDate.of(2026, 11, 8);   // a Sunday: weekly edition
        LocalTime at = LocalTime.of(5, 30);
        ZoneId zone = ZoneId.of("UTC");
        long noon = LocalDateTime.of(day.minusDays(1), LocalTime.NOON).atZone(zone).toEpochSecond();

        String cache = "[{\"uuid\":\"00000000-0000-0000-0000-00000000000a\",\"name\":\"Alice_1\"},"
                + "{\"uuid\":\"00000000-0000-0000-0000-00000000000b\",\"name\":\"Bob_22\"},"
                + "{\"uuid\":\"00000000-0000-0000-0000-00000000000c\",\"name\":\"Hidden_x\"}]";
        for (int i = 0; i < 2; i++) {
            Path s = Files.createDirectories(dir.resolve("stats").resolve(day.minusDays(1 - i).toString()));
            Files.writeString(s.resolve("usercache.json"), cache);
            for (String u : List.of("a", "b", "c")) {
                int play = 1200 * (60 + 120 * i), cows = 2 + 5 * i;
                Files.writeString(s.resolve("00000000-0000-0000-0000-00000000000" + u + ".json"),
                        "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":" + play + ",\"minecraft:deaths\":" + (i * 3)
                                + "},\"minecraft:killed\":{\"minecraft:cow\":" + cows + ",\"minecraft:chicken\":9},"
                                + "\"minecraft:used\":{\"minecraft:egg\":" + (50 + 200 * i) + "}}}");
            }
        }
        Files.createDirectories(dir.resolve("events"));
        Files.writeString(dir.resolve("events").resolve(day.minusDays(1) + ".jsonl"), String.join("\n",
                "{\"t\":" + noon + ",\"k\":\"join\",\"p\":\"Alice_1\"}",
                "{\"t\":" + noon + ",\"k\":\"join\",\"p\":\"Hidden_x\"}",
                "{\"t\":" + (noon + 5) + ",\"k\":\"death\",\"p\":\"Alice_1\",\"msg\":\"Alice_1 was slain by Hidden_x\",\"killer\":\"Hidden_x\"}",
                "{\"t\":" + (noon + 6) + ",\"k\":\"death\",\"p\":\"Bob_22\",\"msg\":\"Bob_22 was slain by Alice_1 using [Rude <b>Name</b>]\",\"killer\":\"Alice_1\"}",
                "{\"t\":" + (noon + 7) + ",\"k\":\"death\",\"p\":\"Bob_22\",\"msg\":\"Bob_22 fell from a high place\",\"killer\":\"\"}",
                "{\"t\":" + (noon + 8) + ",\"k\":\"adv\",\"p\":\"Bob_22\",\"title\":\"Diamonds!\"}",
                // T5: lines from the 2.0.0 recorder with a name tag / renamed item are dropped; a 2.0.1 ("v":2) line is kept
                "{\"t\":" + (noon + 9) + ",\"k\":\"death\",\"p\":\"Alice_1\",\"msg\":\"Alice_1 was slain by base 1200 -340\",\"killer\":\"\"}",
                "{\"t\":" + (noon + 10) + ",\"k\":\"death\",\"p\":\"Alice_1\",\"msg\":\"Alice_1 was impaled by Drowned with [Sharp Thing]\",\"killer\":\"\"}",
                "{\"t\":" + (noon + 11) + ",\"k\":\"death\",\"p\":\"Alice_1\",\"msg\":\"Alice_1 was slain by Zombie\",\"killer\":\"\",\"v\":2}",
                "{\"t\":" + (noon - 86400) + ",\"k\":\"join\",\"p\":\"Bob_22\"}",   // outside the window
                "not json") + "\n");
        Files.createDirectories(dir.resolve("blotter"));
        Files.writeString(dir.resolve("blotter").resolve(day.minusDays(1) + ".jsonl"),
                "{\"t\":" + noon + ",\"k\":\"broke_bed\",\"a\":\"Bob_22\",\"b\":\"Alice_1\",\"x\":\"\",\"n\":1}\n"
                        + "{\"t\":" + noon + ",\"k\":\"broke_bed\",\"a\":\"Hidden_x\",\"b\":\"Alice_1\",\"x\":\"\",\"n\":1}\n");
        Files.createDirectories(dir.resolve("mailroom"));
        Files.writeString(dir.resolve("mailroom").resolve(day + ".jsonl"),
                "{\"id\":\"" + day + "-0000abcd\",\"t\":1,\"name\":\"Bob_22\",\"kind\":\"letter\",\"text\":\"  my   wheat farm is trash \"}\n"
                        + "{\"id\":\"" + day + "-0000abce\",\"t\":1,\"name\":\"Hidden_x\",\"kind\":\"letter\",\"text\":\"hello there\"}\n");
        // 5 days old: read only when it is on the carry list (the desk could not read it earlier)
        Files.writeString(dir.resolve("mailroom").resolve(day.minusDays(5) + ".jsonl"),
                "{\"id\":\"" + day.minusDays(5) + "-0000abcf\",\"t\":1,\"name\":\"Alice_1\",\"kind\":\"letter\",\"text\":\"an old letter\",\"wb\":true}\n");
        Files.createDirectories(dir.resolve("health"));
        Files.writeString(dir.resolve("health").resolve(day.minusDays(1) + ".jsonl"),
                "{\"t\":" + noon + ",\"tps\":19.94,\"on\":2}\n{\"t\":" + (noon + 60) + ",\"tps\":17.06,\"on\":3}\n");
        Files.writeString(dir.resolve("backup.txt"), "newest backup 1 h old\nsecond line\n");
        String ts = LocalDateTime.of(day.minusDays(1), LocalTime.NOON).toString().replace('T', ' ') + ":00";
        Files.writeString(dir.resolve(FactsBuilder.BUILTIN_ACCESS_LOG), String.join("\n",
                "[" + ts + "][INFO] 1.2.3.4 \"GET / HTTP/1.1\" 200 OK",
                "[" + ts + "][INFO] 1.2.3.4 \"GET /players.html HTTP/1.1\" 200 OK",
                "[" + ts + "][INFO] 5.6.7.8 \"GET /owner.html HTTP/1.1\" 304 Not Modified",
                "[" + ts + "][INFO] 127.0.0.1 \"GET / HTTP/1.1\" 200 OK",
                "[" + ts + "][INFO] 9.9.9.9 \"GET /secret HTTP/1.1\" 200 OK") + "\n");
        Files.writeString(dir.resolve(FactsBuilder.IP_PLAYERS), "# 2026-11-05\n5.6.7.8 Bob_22\n# 2026-10-01\n9.9.9.9 Carol_3\n");   // 2.0.2 format: date lines

        Settings d = Settings.defaults();
        Settings s = new Settings(
                new Settings.Paper("The Daily Moo", "Moo", "", "", "", "a", "b", "minecraft:cow"), new Settings.Tone("spicy", "pg13", "normal", List.of("animal-watch")),
                new Settings.Owner("Alice_1", "Desk", List.of("lore one"), true),
                new Settings.Privacy(List.of("hidden_X"), List.of()), new Settings.Schedule(at, "UTC"), d.writer(),
                new Settings.Web("builtin", "0.0.0.0", 8150, "", "http://example:8150/", ""),
                new Settings.Features(true, true, true, true, 3, "backup.txt"), d.alerts());
        JsonObject f = new FactsBuilder(dir, s).paperBuild("test-build").build(day, at);
        String all = FactsBuilder.GSON.toJson(f);

        check("weekly on Sunday", f.get("edition").getAsString().equals("weekly"));
        check("hidden player never appears", !all.toLowerCase().contains("hidden_x"));
        check("players_on = Alice + blotter actor Bob", f.get("players_on").toString().equals("[\"Alice_1\",\"Bob_22\"]"));
        check("weapon name cut; typed names dropped", f.get("deaths").toString()
                .equals("[\"Bob_22 was slain by Alice_1\",\"Bob_22 fell from a high place\",\"Alice_1 was slain by Zombie\"]"));
        check("pvp from killer field", f.get("pvp").toString().equals("[[\"Bob_22\",\"Alice_1\"]]"));
        JsonObject w = f.getAsJsonObject("chicken_watch");
        check("watch animal is cow", w.get("animal").getAsString().equals("cow"));
        check("cows counted, no eggs", w.getAsJsonObject("season").get("chickens_killed").getAsLong() == 14
                && !w.getAsJsonObject("season").has("eggs_thrown") && w.getAsJsonObject("today").get("chickens_killed").getAsLong() == 10);
        check("owner from settings", f.getAsJsonObject("owner").get("name").getAsString().equals("Alice_1")
                && f.getAsJsonObject("owner").get("played_today").getAsBoolean());
        check("mail: whitespace squashed, hidden sender dropped", f.get("mailbag_candidates").toString()
                .equals("[{\"id\":\"" + day + "-0000abcd\",\"from\":\"Bob_22\",\"kind\":\"letter\",\"text\":\"my wheat farm is trash\"}]"));
        check("server block", f.get("server").toString()
                .equals("{\"tps_avg\":18.5,\"tps_min\":17.1,\"peak_players\":3,\"backup\":\"newest backup 1 h old\",\"paper_build\":\"test-build\"}"));
        check("circulation", f.get("circulation").toString()
                .equals("{\"readers\":2,\"page_views\":3,\"pages\":{\"paper\":1,\"player cards\":1,\"owner's file\":1},\"who\":[\"Bob_22\",\"1 new\"]}"));
        String written = Files.readString(dir.resolve("data").resolve(day + ".json"));
        check("no addresses in data/DATE.json", !written.matches("(?s).*\\d+\\.\\d+\\.\\d+\\.\\d+.*"));
        // T7: the facts file on disk keeps no letters and no reader names (they stay in memory for the desk and the owner)
        check("data/DATE.json: no letters", !written.contains("mailbag_candidates") && !written.contains("wheat farm"));
        check("data/DATE.json: no reader names", !written.contains("\"who\"") && written.contains("\"page_views\""));
        check("in memory: letters and readers still there", f.has("mailbag_candidates") && f.getAsJsonObject("circulation").has("who"));
        check("data/DATE.json otherwise the same", written.equals(FactsBuilder.GSON.toJson(FactsBuilder.redacted(f))));
        Settings noWatch = new Settings(s.paper(), d.tone(), s.owner(), s.privacy(), s.schedule(), s.writer(), s.web(), s.features(), s.alerts());
        check("P4: no chicken_watch when animal-watch is not in tone.bits", !new FactsBuilder(dir, noWatch).paperBuild("test-build").build(day, at).has("chicken_watch"));
        // T5: only allow-listed fields carry free text
        List<String> free = freeText(f, "", new java.util.ArrayList<>());
        check("free text only in allow-listed fields " + free, free.isEmpty());
        JsonObject mutant = f.deepCopy();
        mutant.getAsJsonObject("server").addProperty("motd", "come to my base at spawn");
        mutant.add("pets", com.google.gson.JsonParser.parseString("[{\"name\": \"Mr Fluffy\"}]"));
        check("the free-text check catches a new free-text field", freeText(mutant, "", new java.util.ArrayList<>()).size() == 2);
        check("week block present", f.getAsJsonObject("week").has("Alice_1"));
        check("good award for the PvP win", f.getAsJsonObject("awards").getAsJsonObject("golden_egg").get("reasons").toString()
                .equals("[\"took down Bob_22 in PvP\"]"));
        // carried letters: the 5-day-old one only when on the carry list; "wb" travels with it
        Files.writeString(dir.resolve("mailbag-carry.txt"), day.minusDays(5) + "-0000abcf\n");
        JsonObject f2 = new FactsBuilder(dir, s).writeTo(null).build(day, at);
        String mc = f2.get("mailbag_candidates").toString();
        check("carried letter is read, with its worried mark", mc.contains("an old letter") && mc.contains("\"wb\":true"));
        Files.delete(dir.resolve("mailbag-carry.txt"));
        Files.delete(dir.resolve("data").resolve(day + ".json"));
        JsonObject f3 = new FactsBuilder(dir, s).writeTo(null).build(day, at);
        check("not carried: a 5-day-old letter is not read", !f3.get("mailbag_candidates").toString().contains("an old letter"));
        check("writeTo(null) writes no facts file", !Files.exists(dir.resolve("data").resolve(day + ".json")));
        // the old-line check keeps player names with digits
        check("old line: player names with digits are fine", !Events.typedText("Bob_22 was slain by Alice_1", "Bob_22", "Alice_1", java.util.Set.of("Bob_22", "Alice_1"))
                && Events.typedText("Bob_22 was slain by Fluffy99", "Bob_22", null, java.util.Set.of("Bob_22")));
        // a rewrite of an older facts file (what 2.0.0 left behind) drops letters and readers, and only once
        Path old = dir.resolve("data").resolve("2026-11-01.json");
        Files.writeString(old, "{\"date\": \"2026-11-01\", \"circulation\": {\"readers\": 1, \"who\": [\"Bob_22\"]}, \"mailbag_candidates\": [{\"text\": \"secret\"}]}");
        check("rewrite older facts file", FactsBuilder.rewriteRedacted(old) && !Files.readString(old).contains("secret")
                && !Files.readString(old).contains("Bob_22") && !FactsBuilder.rewriteRedacted(old));
        System.out.println(failures == 0 ? "smoke: all checks passed" : "smoke: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * Paths of free text (a space or other punctuation) that are not on the allow-list. Allowed: game-made death lines and
     * advancement titles, the last editions' text, the owner's lore, fixed owner/server lines, award reasons the plugin
     * writes, the letters for the editor desk (memory only, never written to disk, never shown to the main writer) and the
     * "N new" reader count.
     */
    static List<String> freeText(com.google.gson.JsonElement e, String path, List<String> out) {
        return freeText(e, path, out, e.isJsonObject() && e.getAsJsonObject().get("season_totals") instanceof JsonObject st ? st.keySet() : java.util.Set.of());
    }

    static List<String> freeText(com.google.gson.JsonElement e, String path, List<String> out, java.util.Set<String> players) {
        if (e.isJsonObject()) {
            for (var en : e.getAsJsonObject().entrySet()) {
                String k = players.contains(en.getKey()) ? "*" : en.getKey();   // player names as keys
                freeText(en.getValue(), path.isEmpty() ? k : path + "." + k, out, players);
            }
        } else if (e.isJsonArray()) {
            for (var x : e.getAsJsonArray()) freeText(x, path + "[]", out, players);
        } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            String v = e.getAsString();
            if (v.matches("[A-Za-z0-9_.:\\-]*")) return out;
            if (!path.matches("deaths\\[]|advancements\\[]\\[]|recent_editions\\[]|owner\\.(lore\\[]|role|note)|server\\.(backup|paper_build)"
                    + "|awards\\.[a-z_]+\\.(reasons\\[]|runners_up\\[]\\[])|mailbag_candidates\\[]\\.text|circulation\\.who\\[]")) out.add(path + "=" + v);
        }
        return out;
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
        if (!ok) failures++;
    }
}
