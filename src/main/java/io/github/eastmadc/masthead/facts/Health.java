package io.github.eastmadc.masthead.facts;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/** The 'server' block: TPS and peak players over the edition window from health/DATE.jsonl, plus an optional backup line. */
final class Health {
    private Health() {}

    static JsonObject read(Path dataFolder, LocalDate date, long lo, long hi, Path extraFile, String paperBuild) {
        double sum = 0, min = Double.MAX_VALUE;
        int count = 0;
        long peak = -1;
        for (LocalDate day : List.of(date.minusDays(1), date, date.plusDays(1))) {
            for (String line : Py.lines(dataFolder.resolve("health").resolve(day + ".jsonl"), 8 << 20, 100000)) {
                JsonElement el = Py.parse(line);
                if (el == null || !el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                Double t = FactsBuilder.dbl(o.get("t")), tps = FactsBuilder.dbl(o.get("tps")), on = FactsBuilder.dbl(o.get("on"));
                if (t == null || !(lo <= t && t < hi)) continue;
                if (tps != null && tps >= 0 && tps <= 100) {
                    sum += tps;
                    min = Math.min(min, tps);
                    count++;
                }
                if (on != null) peak = Math.max(peak, on.longValue());
            }
        }
        JsonObject out = new JsonObject();
        if (count > 0) {
            out.add("tps_avg", tenth(sum / count));
            out.add("tps_min", tenth(min));
        }
        if (peak >= 0) out.addProperty("peak_players", peak);
        if (extraFile != null) {
            List<String> l = Py.lines(extraFile, 64 << 10, 1);
            if (!l.isEmpty() && !l.get(0).isBlank()) out.addProperty("backup", Py.clean(l.get(0).strip(), 200));
        }
        if (paperBuild != null && !paperBuild.isBlank()) out.addProperty("paper_build", Py.clean(paperBuild, 40));
        return out;
    }

    /** One decimal, printed like the reference (jq): 20 rather than 20.0. */
    private static com.google.gson.JsonPrimitive tenth(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.rint(r) ? new com.google.gson.JsonPrimitive((long) r) : new com.google.gson.JsonPrimitive(r);
    }
}
