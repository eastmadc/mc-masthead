package io.github.eastmadc.masthead.facts;

import io.github.eastmadc.masthead.config.Settings;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/** PRIV-R2-3: with construction news on, the stats never hand the writer a player's placed blocks. */
public final class ReviewRound2FactsTest {
    static void t(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("r2-facts");
        LocalDate day = LocalDate.of(2026, 11, 3);
        for (int i = 0; i < 2; i++) {
            Path s = Files.createDirectories(dir.resolve("stats").resolve(day.minusDays(1 - i).toString()));
            Files.writeString(s.resolve("usercache.json"), "[{\"uuid\":\"00000000-0000-0000-0000-00000000000a\",\"name\":\"PrivateAlex\"}]");
            int n = 100 * (1 + 9 * i);
            Files.writeString(s.resolve("00000000-0000-0000-0000-00000000000a.json"), "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":" + (1200 * (60 + 60 * i))
                    + ",\"minecraft:nautilus_one_cm\":" + (250000 * i) + ",\"minecraft:happy_ghast_one_cm\":" + (90000 * i)
                    + ",\"minecraft:raid_win\":" + i + ",\"minecraft:bell_ring\":" + (2 * i) + ",\"minecraft:sleep_in_bed\":" + (3 * i)
                    + "},\"minecraft:used\":{\"minecraft:cobbled_deepslate\":" + n + ",\"minecraft:pale_moss_block\":" + n + ",\"minecraft:ender_pearl\":" + (i + 1) * 3
                    + "},\"minecraft:mined\":{\"minecraft:stone\":" + n + "},\"minecraft:broken\":{\"minecraft:iron_pickaxe\":" + (i + 1) + "}}}");
        }
        Set<String> blocks = Set.of("cobbled_deepslate", "pale_moss_block", "stone");
        Settings d = Settings.defaults();
        Settings.Owner o = d.owner();
        Settings owned = new Settings(d.paper(), d.tone(), new Settings.Owner("PrivateAlex", o.deskTitle(), o.lore(), o.hoursOk()), d.privacy(), d.schedule(),
                d.writer(), d.web(), d.features(), d.alerts(), d.construction());
        for (boolean print : List.of(false, true)) {
            Settings on = ConstructionFactsTest.with(owned, true, print);
            JsonObject f = new FactsBuilder(dir, on).writeTo(null).blockItems(blocks).build(day, LocalTime.of(5, 30));
            String today = f.getAsJsonObject("today").toString(), owner = f.getAsJsonObject("owner").toString();
            t(!today.contains("cobbled_deepslate") && !today.contains("pale_moss") && today.contains("ender_pearl"), "PRIV-R2-3 (print " + print + "): today " + today);
            t(!owner.contains("cobbled_deepslate") && !owner.contains("pale_moss") && owner.contains("ender_pearl"), "PRIV-R2-3 (print " + print + "): owner " + owner);
            JsonObject none = new FactsBuilder(dir, on).writeTo(null).blockItems(Set.of()).build(day, LocalTime.of(5, 30));
            String all = none.getAsJsonObject("today").toString() + none.getAsJsonObject("owner").getAsJsonObject("today");
            t(all.contains("\"used\":{}") && !all.contains("ender_pearl") && !all.contains("cobbled_deepslate"), "PRIV-R2-3: no block list from the server: every used map is emptied: " + all);
            t(!none.getAsJsonObject("owner").getAsJsonObject("season").toString().contains("cobbled_deepslate"), "PRIV-R2-3: no block list: top_used empty");
        }
        // the new mounts (nautilus, happy ghast) reach the writer (they were never read before); a bed count never does (beds are only broken beds)
        String rode = new FactsBuilder(dir, owned).writeTo(null).build(day, LocalTime.of(5, 30)).getAsJsonObject("today").toString();
        t(rode.contains("nautilus_m") && rode.contains("happy_ghast_m"), "mounts in today: " + rode);
        t(rode.contains("\"raids_won\":1") && rode.contains("\"bells_rung\":2") && rode.contains("\"broken\":{\"iron_pickaxe\":1}") && !rode.contains("slept"),
                "raids, bells and worn-out tools in today, no sleep count: " + rode);
        JsonObject off = new FactsBuilder(dir, owned).writeTo(null).blockItems(blocks).build(day, LocalTime.of(5, 30));
        t(off.getAsJsonObject("today").toString().contains("pale_moss_block"), "construction off: the stats are unchanged");
        // owner.name in another case than the in-game name still drops the owner's hours (owner.hours-ok: false)
        Settings lower = new Settings(d.paper(), d.tone(), new Settings.Owner("privatealex", o.deskTitle(), o.lore(), false), d.privacy(), d.schedule(),
                d.writer(), d.web(), d.features(), d.alerts(), d.construction());
        JsonObject lf = new FactsBuilder(dir, lower).writeTo(null).build(day, LocalTime.of(5, 30));
        t(lf.has("owner") && !lf.toString().replace("\"play_min\":[]", "").contains("play_min"), "owner.name case: hours stay: " + lf);
        System.out.println("ReviewRound2FactsTest: ok");
    }
}
