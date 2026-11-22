package io.github.eastmadc.masthead.construction;

import io.github.eastmadc.masthead.config.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * players.json: each player's choice (public or private), when they got the one-time notice, and what the paper has
 * already done with them (first printed build, recent entries, critic picks). No positions. Field names are the JSON keys.
 */
final class Prefs {
    static final int VERSION = 1;

    int version = VERSION;
    int nextPermit = 1;
    String opened;                                   // date of the first edition that printed the section
    TreeMap<String, Integer> surveyPermits = new TreeMap<>();   // survey.json id -> permit number, given on the opening day
    List<Row> register;                              // the opening's Town Survey rows, builders by UUID (null: no opening yet)
    String surveyLost;                               // date the owner was told survey.json is gone after the opening (null: it reads)
    TreeMap<String, P> players = new TreeMap<>();    // player UUID -> P
    List<String> optedApplied = new ArrayList<>();   // old files only: opted-in names once written into level (read() undoes it)

    static final class P {
        String name;
        String level;            // public | private, as the player typed it; null = construction.opted-in, else default-level
        String noticed;          // date the one-time notice was sent (the earliest wins)
        boolean hadSite;         // has had a printed build
        List<String> featured = new ArrayList<>();   // dates of editions with an entry naming them
        String critic;           // last date the Architecture Critic picked their build
        boolean secretNext;      // a forgotten build of theirs was secret: their next "existing" build starts secret
    }

    /** One Town Survey row: the page draws it again on every paper, with the names the paper may print that day. */
    static final class Row {
        int permit;
        List<String> builders = new ArrayList<>();   // UUIDs
        String kind;                                 // at the opening's construction.detail (a lower one is applied when drawn)
        List<Features.F> features = new ArrayList<>();
        int blocksTotal;
        boolean existing;
        String name;                                 // the survey's build name (null: none; older files have none)
    }

    P get(String uuid) { return players.computeIfAbsent(uuid, k -> new P()); }

    /** The player's level now: their own choice, else the live construction.opted-in list, else default-level. */
    String level(String uuid, Settings.Construction c) {
        P p = players.get(uuid);
        return p == null ? c.defaultLevel() : Levels.level(p.level, p.name, c);
    }

    static Prefs read(String json) throws java.io.IOException {
        Prefs p = SiteIndex.GSON.fromJson(json, Prefs.class);
        if (p == null || p.version != VERSION) throw new java.io.IOException("version");
        // older jars wrote opted-in as the player's own "public": undo it, so taking a name off the list makes them private again
        if (!p.optedApplied.isEmpty()) {
            for (P x : p.players.values())
                if ("public".equals(x.level) && x.name != null && p.optedApplied.contains(x.name.toLowerCase(Locale.ROOT))) x.level = null;
            p.optedApplied.clear();
        }
        return p;
    }

    String write() { return SiteIndex.GSON.toJson(this); }

    Prefs copy() throws java.io.IOException { return read(write()); }
}
