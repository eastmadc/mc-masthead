package io.github.eastmadc.masthead.construction;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eastmadc.masthead.config.Settings;
import io.github.eastmadc.masthead.facts.ConstructionFacts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The opening paper (alpha.21 Part O): survey.json checks, one listing for a survey build and the tracked builds mostly
 * inside it, permits for the whole register (kept across re-runs), up to 5 stories, practice runs keeping nothing, the
 * facts filter, and the next day back to normal. Made-up players and places only.
 */
public final class OpeningTest {
    static int ok = 0;

    static void t(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
        ok++;
    }

    static final String B1 = "TestBuilder1", B2 = "TestBuilder2", B3 = "TestBuilder3", B4 = "TestBuilder4";
    // made-up places: a house at x 4076..4086 has one cube column in chunk 254 and two in chunk 255; z 8192.. is chunk 512
    static final int HX = 4076, HY = 70, HZ = 8192;

    public static void main(String[] args) throws Exception {
        parsing();
        merging();
        picking();
        files();
        unreadable();
        detail();
        surveyNames();
        heldSecret();
        secretAfter();
        secretJoined();
        surveySecretAfter();
        detailLowered();
        System.out.println("OpeningTest: " + ok + " checks passed");
    }

    static String uuid(ConstructionTest.Sc s, String n) { return s.id(n).toString(); }

    static String row(String id, String world, String chunks, String builders, String kind, String blocks) {
        return "{\"id\":\"" + id + "\",\"world\":\"" + world + "\",\"chunks\":" + chunks + ",\"y\":[60,90],\"builders\":" + builders + ",\"kind\":\"" + kind
                + "\",\"blocks\":" + blocks + "}";
    }

    static String file(String... rows) { return "{\"version\":1,\"scanned\":\"2026-11-01\",\"builds\":[" + String.join(",", rows) + "]}"; }

    // ------------------------------------------------------------ survey.json
    static void parsing() {
        String u = "\"00000000-0000-0000-0000-00000000000a\"";
        List<String> notes = new ArrayList<>();
        t(Survey.parse(null, notes).isEmpty() && notes.isEmpty(), "no file: no survey, no note");
        List<Survey.Build> b = Survey.parse(file(
                "{\"id\":\"S1\",\"world\":\"overworld\",\"chunks\":[[1,2],[1,3]],\"y\":[60,90],\"builders\":[" + u + "],\"kind\":\"library\","
                        + "\"blocks\":{\"bookshelf\":64,\"lectern\":2},\"villagers\":{\"librarian\":3},\"spawners\":{\"spider\":1},\"extra\":{\"x\":1}}",
                row("S2", "overworld", "[[1,2]]", "[" + u + "]", "structure", "{\"stone\":5}"),
                row("S3", "overworld", "[[1,2]]", "[" + u + "]", "build", "{\"stone\":5}"),
                row("S4", "overworld", "[[1,2]]", "[" + u + "]", "house", "{\"stone\":2000000}"),
                row("S5", "overworld", "[[1,2]]", "[" + u + "]", "house", "{\"stone\":0}"),
                row("S6", "overworld", "[[1,2]]", "[\"not-a-uuid\"]", "house", "{\"stone\":5}"),
                row("S7", "moon", "[[1,2]]", "[" + u + "]", "house", "{\"stone\":5}"),
                row("S8", "overworld", "[[1]]", "[" + u + "]", "house", "{\"stone\":5}"),
                row("S1", "overworld", "[[1,2]]", "[" + u + "]", "house", "{\"stone\":5}"),
                row("bad id!", "overworld", "[[1,2]]", "[" + u + "]", "house", "{\"stone\":5}"),
                row("S9", "nether", "[[1,2]]", "[" + u + "]", "house", "{\"Stone\":5}"),
                row("S10", "end", "[[1,2]]", "[]", "house", "{\"stone\":5}")), notes);
        t(b.size() == 1 && b.get(0).id().equals("S1") && b.get(0).total() == 66 && b.get(0).villagers().get("librarian") == 3
                && b.get(0).spawners().get("spider") == 1 && b.get(0).chunks().size() == 2, "one good row, unknown fields ignored: " + b);
        for (String why : List.of("S2 dropped (unknown kind)", "S3 dropped (unknown kind)", "S4 dropped (bad counts)", "S5 dropped (bad counts)",
                "S6 dropped (bad builders)", "S7 dropped (bad world)", "S8 dropped (bad chunks)", "S1 dropped (id used twice)", "row 10 dropped (bad id)",
                "S9 dropped (bad counts)", "S10 dropped (bad builders)"))
            t(notes.contains("survey: " + why), "owner note: " + why + " in " + notes);
        t(notes.stream().noneMatch(n -> n.matches(".*\\d+\\s*,\\s*\\d+.*")), "notes never carry chunks");
        notes.clear();
        t(Survey.parse("{\"version\":2,\"builds\":[]}", notes).isEmpty() && notes.size() == 1, "another version: no survey, one note");
        notes.clear();
        t(Survey.parse("not json", notes).isEmpty() && notes.size() == 1, "broken file: no survey, one note");
    }

    // ------------------------------------------------------------ one listing, privacy, permits
    static ConstructionTest.Sc opening() {
        ConstructionTest.Sc s = new ConstructionTest.Sc().notice(B1, B2, B3, B4);
        s.prefs.opened = null;
        return s;
    }

    static JsonObject cons(ConstructionTest.Sc s) { return s.res.out().getAsJsonObject("construction"); }

    static JsonObject rowWith(ConstructionTest.Sc s, String builder) {
        if (!cons(s).has("survey")) return null;
        for (JsonElement e : cons(s).getAsJsonArray("survey")) if (e.getAsJsonObject().getAsJsonArray("builders").toString().contains(builder)) return e.getAsJsonObject();
        return null;
    }

    static List<Survey.Build> survey(String json) { return Survey.parse(json, new ArrayList<>()); }

    static JsonElement field(String json, String key) {
        JsonElement e = JsonParser.parseString(json).getAsJsonObject().get(key);
        return e == null ? com.google.gson.JsonNull.INSTANCE : e;
    }

    static void merging() {
        // majority inside: the tracked house joins the survey build (one listing, both crews, the survey's kind and features)
        ConstructionTest.Sc s = opening();
        ConstructionTest.house(s, B1, HX, HY, HZ);
        s.survey = survey(file(row("S1", "overworld", "[[255,512]]", "[\"" + uuid(s, B3) + "\"]", "library",
                "{\"bookshelf\":64,\"oak_planks\":300}").replace("\"kind\"", "\"spawners\":{\"spider\":1},\"kind\"")));
        s.fold();
        JsonObject c = cons(s);
        JsonArray rows = c.getAsJsonArray("survey");
        t(c.get("opening").getAsBoolean() && rows.size() == 1, "one listing for the survey build and the house inside it: " + rows);
        JsonObject r = rows.get(0).getAsJsonObject();
        t(r.getAsJsonArray("builders").toString().equals("[\"" + B1 + "\",\"" + B3 + "\"]") && r.get("kind").getAsString().equals("library")
                && r.get("existing").getAsBoolean() && r.get("blocks_total").getAsInt() == 364, "survey view, crews joined: " + r);
        t(r.toString().contains("\"bookshelves\"") && !r.toString().contains("spawners"), "features from the survey; standard detail leaves the spawner out: " + r);
        SiteIndex.Site house = s.idx.sites.firstEntry().getValue();
        t(house.permit != null && house.permit == r.get("permit").getAsInt() && s.prefs.surveyPermits.get("S1") == house.permit, "the house takes the listing's permit");
        t("2026-11-02".equals(s.prefs.opened), "the opening is remembered");
        t(s.entries().size() == 1 && s.entry(0).get("tag").getAsString().equals("survey"), "the story is the survey listing: " + s.entries());
        // the next day is a daily paper again: the house is never "new permit" news, no survey
        s.box(B1, "oak_planks", HX, HY + 6, HZ, HX + 10, HY + 6, HZ + 8);
        s.fold();
        t(!cons(s).get("opening").getAsBoolean() && !cons(s).has("survey"), "next day: no opening, no survey");
        for (JsonElement e : s.entries()) t(!e.getAsJsonObject().get("tag").getAsString().equals("permit"), "next day: no new permit for a registered build");

        // a minority inside: the house stays its own row, the survey build is listed apart
        s = opening();
        ConstructionTest.house(s, B1, HX, HY, HZ);
        s.survey = survey(file(row("S1", "overworld", "[[254,512]]", "[\"" + uuid(s, B3) + "\"]", "library", "{\"bookshelf\":64}")));
        s.fold();
        rows = cons(s).getAsJsonArray("survey");
        t(rows.size() == 2 && rowWith(s, B1) != null && !rowWith(s, B1).has("existing") && rowWith(s, B3).getAsJsonArray("builders").size() == 1
                && rowWith(s, B1).get("permit").getAsInt() != rowWith(s, B3).get("permit").getAsInt(), "touching only: two rows, two permits: " + rows);
        // another dimension never merges
        s = opening();
        ConstructionTest.house(s, B1, HX, HY, HZ);
        s.survey = survey(file(row("S1", "nether", "[[255,512]]", "[\"" + uuid(s, B3) + "\"]", "library", "{\"bookshelf\":64}")));
        s.fold();
        t(cons(s).getAsJsonArray("survey").size() == 2, "nether survey build does not hold an overworld house");

        // privacy: a private builder, an unknown builder, a secret tracked build inside: the survey build is dropped with a note
        s = opening();
        s.prefs.players.get(uuid(s, B4)).level = "private";
        ConstructionTest.house(s, B2, HX, HY, HZ);
        s.reqAt(B2, "secret", HX + 2, HY + 1, HZ + 2);
        s.survey = survey(file(row("P1", "overworld", "[[9,9]]", "[\"" + uuid(s, B4) + "\"]", "house", "{\"stone\":500}"),
                row("P2", "overworld", "[[9,9]]", "[\"00000000-0000-0000-0000-0000000000ff\"]", "house", "{\"stone\":500}"),
                row("P3", "overworld", "[[255,512]]", "[\"" + uuid(s, B3) + "\"]", "house", "{\"stone\":500}")));
        s.fold();
        t(!cons(s).has("survey") || cons(s).getAsJsonArray("survey").isEmpty(), "nothing private is listed: " + cons(s));
        String al = s.res.alerts().toString();
        t(al.contains("survey: P1 dropped (a builder the paper may not name)") && al.contains("survey: P2 dropped (a builder the paper may not name)")
                && al.contains("survey: P3 dropped (holds a tracked build the paper may not print)"), "owner notes: " + al);
        t(!s.res.out().toString().contains(B4), "a private builder's name is nowhere in the out file");
        // a hidden player is never named either
        s = opening();
        s.hidden = Set.of(B3.toLowerCase());
        s.survey = survey(file(row("H1", "overworld", "[[9,9]]", "[\"" + uuid(s, B3) + "\"]", "house", "{\"stone\":500}")));
        s.fold();
        t(!s.res.out().toString().contains(B3), "hidden survey builder: dropped");
        // a build tracked before its builder was hidden, inside a survey build: the survey build is dropped with a note
        // (the crew list already leaves hidden players out, so the check reads every player of the build)
        s = new ConstructionTest.Sc().notice(B1, B2, B3, B4);
        ConstructionTest.house(s, B1, HX, HY, HZ);
        s.fold();   // a daily paper tracks it
        s.prefs.opened = null;
        s.hidden = Set.of(B1.toLowerCase());
        for (int i = 0; i < 5; i++) s.place(B4, "oak_planks", HX + 2 + i, HY + 5, HZ + 2);   // someone else's blocks: the crew is worked out again
        s.survey = survey(file(row("H2", "overworld", "[[255,512]]", "[\"" + uuid(s, B3) + "\"]", "house", "{\"stone\":500}")));
        s.fold();
        t(!s.res.out().toString().contains(B1) && rowWith(s, B3) == null
                && s.res.alerts().toString().contains("survey: H2 dropped (holds a tracked build the paper may not print)"), "hidden builder's build inside: dropped: " + cons(s) + s.res.alerts());

        // any overlap vetoes, not only a build mostly inside: a secret or private build only touching the survey build
        String drop = "survey: M1 dropped (holds a tracked build the paper may not print)";
        s = opening();
        ConstructionTest.house(s, B2, HX, HY, HZ);
        s.reqAt(B2, "secret", HX + 2, HY + 1, HZ + 2);
        s.survey = survey(file(row("M1", "overworld", "[[254,512]]", "[\"" + uuid(s, B3) + "\"]", "house", "{\"stone\":500,\"beacon\":2}")));
        s.fold();
        t(rowWith(s, B3) == null && s.res.alerts().toString().contains(drop), "a secret build only touching: dropped: " + cons(s) + s.res.alerts());
        s = opening();
        s.prefs.players.get(uuid(s, B2)).level = "private";
        ConstructionTest.house(s, B2, HX, HY, HZ);
        s.survey = survey(file(row("M1", "overworld", "[[254,512]]", "[\"" + uuid(s, B3) + "\"]", "house", "{\"stone\":500}")));
        s.fold();
        t(rowWith(s, B3) == null && s.res.alerts().toString().contains(drop), "a private build only touching: dropped: " + cons(s) + s.res.alerts());
        // a retired build counts too
        s = new ConstructionTest.Sc().notice(B1, B2, B3, B4);
        ConstructionTest.house(s, B2, HX, HY, HZ);
        s.fold();
        SiteIndex.Site old = s.idx.sites.firstEntry().getValue();
        old.retired = true;
        old.retiredOn = "2026-11-02";
        s.prefs.players.get(uuid(s, B2)).level = "private";
        s.prefs.opened = null;
        s.survey = survey(file(row("M1", "overworld", "[[255,512]]", "[\"" + uuid(s, B3) + "\"]", "house", "{\"stone\":500}")));
        s.fold();
        t(rowWith(s, B3) == null && s.res.alerts().toString().contains(drop), "a private retired build inside: dropped: " + cons(s) + s.res.alerts());

        // a practice run (print false) works it all out and keeps nothing
        s = opening();
        s.c = new Settings.Construction("Planning & Zoning", false, "public", true, 3, 64, "blotter", 100, 15, 30, List.of(), 20000, List.of());
        ConstructionTest.house(s, B1, HX, HY, HZ);
        s.survey = survey(file(row("S1", "overworld", "[[255,512]]", "[\"" + uuid(s, B3) + "\"]", "library", "{\"bookshelf\":64}")));
        s.fold();
        t(cons(s).get("opening").getAsBoolean() && cons(s).getAsJsonArray("survey").size() == 1, "practice: the full opening is worked out");
        t(s.prefs.opened == null && s.prefs.surveyPermits.isEmpty() && s.prefs.nextPermit == 1 && s.idx.sites.firstEntry().getValue().permit == null,
                "practice: nothing kept");
    }

    // ------------------------------------------------------------ up to 5 stories, kinds spread, one per crew
    static void picking() {
        ConstructionTest.Sc s = opening();
        String[] kinds = {"spider_farm", "spider_farm", "library", "melon_farm", "trading_hall", "house", "greenhouse"};
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < kinds.length; i++) {
            String n = "TestBuilder" + (10 + i);
            s.notice(n);
            rows.add(row("K" + i, "overworld", "[[" + i + ",0]]", "[\"" + uuid(s, n) + "\"]", kinds[i], "{\"stone\":" + (9000 - i * 1000) + "}"));
        }
        rows.add(row("K9", "overworld", "[[50,0]]", "[\"" + uuid(s, "TestBuilder10") + "\"]", "house", "{\"stone\":9501}"));   // same crew as K0
        s.survey = survey(file(rows.toArray(new String[0])));
        s.fold();
        JsonArray e = s.entries();
        Set<String> picked = new java.util.HashSet<>();
        for (JsonElement x : e) picked.add(x.getAsJsonObject().get("kind").getAsString());
        t(e.size() == 5, "opening: 5 stories whatever max-entries is: " + e.size());
        t(picked.size() == 5, "five different kinds first: " + picked);
        t(cons(s).getAsJsonArray("survey").size() == 8, "every build on the page");
        int crew10 = 0;
        for (JsonElement x : e) if (x.toString().contains("TestBuilder10")) crew10++;
        t(crew10 == 1, "one story per crew");
        // permits 1..8, one each
        Set<Integer> p = new java.util.HashSet<>();
        for (JsonElement x : cons(s).getAsJsonArray("survey")) p.add(x.getAsJsonObject().get("permit").getAsInt());
        t(p.equals(Set.of(1, 2, 3, 4, 5, 6, 7, 8)) && s.prefs.nextPermit == 9, "every listing has its own permit: " + p);
    }

    // ------------------------------------------------------------ files: re-runs, previews, the facts filter
    static void files() throws Exception {
        ConstructionIOTest.Rig r = new ConstructionIOTest.Rig();
        Files.createDirectories(r.dir);
        r.io.dims = Map.of(ConstructionTest.Sc.WORLD.toString(), "overworld");
        for (String n : List.of(B1, B3)) r.req(n, "noticed", null);
        ConstructionTest.house(r.sc, B1, HX, HY, HZ);
        r.sc.place(B3, "oak_planks", HX + 500, HY, HZ);   // a name on file (players.json learns names from building)
        r.flush(ConstructionIOTest.D1);
        Files.writeString(r.dir.resolve("survey.json"), file(
                row("S1", "overworld", "[[1,1]]", "[\"" + r.sc.id(B3) + "\"]", "library", "{\"bookshelf\":64}"),
                row("S2", "overworld", "[[2,1]]", "[\"" + r.sc.id(B3) + "\"]", "nope", "{\"bookshelf\":64}")));
        // a preview keeps nothing, but shows the opening
        Path pv = r.fold(ConstructionIOTest.D1, false);
        t(pv.getFileName().toString().equals("preview.json") && Files.readString(pv).contains("\"survey\"") && r.read("players.json") == null,
                "preview: preview.json has the survey, nothing saved");
        Path out = r.fold(ConstructionIOTest.D1, true);
        String first = Files.readString(out);
        t(first.contains("\"opening\":true") && first.contains("\"survey\""), "published opening");
        t(r.read("players.json").contains("\"survey_permits\":{\"S1\":") && r.read("players.json").contains("\"opened\":\"" + ConstructionIOTest.D1 + "\""),
                "survey permits and the opening are kept: " + r.read("players.json") + " " + first);
        t(Files.readString(r.dir.resolve("alerts").resolve(ConstructionIOTest.D1 + ".json")).contains("survey: S2 dropped (unknown kind)"), "owner note in alerts");
        t(Construction.ownerLines(r.dir.getParent(), ConstructionIOTest.D1).contains("survey: S2 dropped (unknown kind)"), "owner lines read it");
        String firstPlayers = r.read("players.json");
        t(Files.readString(r.fold(ConstructionIOTest.D1, true)).equals(first), "a re-run of the opening gives the same numbers");
        String keep = r.read("players.json");
        t(field(keep, "survey_permits").equals(field(firstPlayers, "survey_permits")) && field(keep, "register").equals(field(firstPlayers, "register"))
                && field(keep, "survey_permits").getAsJsonObject().size() == 1, "a re-run of the opening (from the .prev files) keeps the same permits and register: " + keep);
        // the register is kept by UUID, never by name
        String reg = field(keep, "register").toString();
        t(field(keep, "register").getAsJsonArray().size() == 2 && reg.contains(r.sc.id(B3).toString()) && !reg.contains(B3) && !reg.contains(B1), "register by UUID: " + reg);
        // the facts filter: the survey passes (names checked), the writer sees how many
        Set<String> players = Set.of(B1, B3);
        ConstructionFacts.Result f = ConstructionFacts.read(out, players, Set.of(), false, 3);
        JsonObject c = f.construction();
        t(c.getAsJsonArray("survey").size() == 2 && c.get("survey_builds").getAsInt() == 2 && c.toString().contains("\"kind\":\"library\""), "facts: survey rows pass: " + c);
        t(ConstructionFacts.read(out, Set.of(B1), Set.of(), false, 3).construction().getAsJsonArray("survey").size() == 1, "facts: an unknown name drops the row");
        t(ConstructionFacts.read(out, players, Set.of(B3), false, 3).construction().getAsJsonArray("survey").size() == 1, "facts: a hidden name drops the row");
        // the next date, with a new row in survey.json (a second opening attempt): back to normal, the file is not read again
        r.sc.box(B1, "oak_planks", HX, HY + 6, HZ, HX + 10, HY + 6, HZ + 8);
        r.flush(ConstructionIOTest.D2);
        Files.writeString(r.dir.resolve("survey.json"), file(
                row("S0", "overworld", "[[3,1]]", "[\"" + r.sc.id(B3) + "\"]", "tower", "{\"stone\":640}"),
                row("S1", "overworld", "[[1,1]]", "[\"" + r.sc.id(B3) + "\"]", "library", "{\"bookshelf\":64}")));
        String next = Files.readString(r.fold(ConstructionIOTest.D2, true));
        t(next.contains("\"opening\":false") && !next.contains("\"survey\"") && !next.contains("\"tag\":\"permit\""), "next day: daily paper: " + next);
        String after = r.read("players.json");
        t(field(after, "survey_permits").equals(field(keep, "survey_permits")) && field(after, "register").equals(field(keep, "register"))
                && field(after, "next_permit").equals(field(keep, "next_permit")), "survey permits and register never change after the opening: " + after);
        // the Town Survey page's rows by today's name rules: hidden or forgotten builders' rows go
        JsonObject now = Construction.register(r.dir.getParent(), r.c, List.of(), ConstructionIOTest.D2);
        t(now.get("opened").getAsString().equals(ConstructionIOTest.D1.toString()) && now.getAsJsonObject("construction").getAsJsonArray("survey").size() == 2
                && now.toString().contains("\"builders\":[\"" + B3 + "\"]") && now.toString().contains("\"builders\":[\"" + B1 + "\"]"), "register rows by name: " + now);
        t(Construction.register(r.dir.getParent(), r.c, List.of(B3.toUpperCase(java.util.Locale.ROOT)), ConstructionIOTest.D2).getAsJsonObject("construction").getAsJsonArray("survey").size() == 1,
                "a hidden builder's row is left out");
        r.io.forget(r.sc.id(B3));
        t(!field(r.read("players.json"), "register").toString().contains(r.sc.id(B3).toString()) && !field(r.read("players.prev.json"), "register").toString().contains(r.sc.id(B3).toString())
                && Construction.register(r.dir.getParent(), r.c, List.of(), ConstructionIOTest.D2).getAsJsonObject("construction").getAsJsonArray("survey").size() == 1,
                "a forgotten builder's rows are gone from the register");
        // a crafted file: survey rows only on the opening, closed fields, known kinds, opening allows 5 entries
        Path tmp = Files.createTempFile("cons", ".json");
        String entry = "{\"tag\":\"survey\",\"permit\":1,\"builders\":[\"TestBuilder1\"],\"kind\":\"library\",\"blocks_total\":66}";
        String rowOk = "{\"permit\":1,\"builders\":[\"TestBuilder1\"],\"kind\":\"spider_farm\",\"blocks_total\":66,\"features\":[{\"feature\":\"spider_spawners\",\"n\":1}],\"existing\":true,\"x\":5}";
        String rowBad = "{\"permit\":2,\"builders\":[\"TestBuilder1\"],\"kind\":\"structure\",\"blocks_total\":66}";
        String rowFeat = "{\"permit\":3,\"builders\":[\"TestBuilder1\"],\"kind\":\"house\",\"blocks_total\":66,\"features\":[{\"feature\":\"secret_base\",\"n\":1}]}";
        Files.writeString(tmp, "{\"construction\":{\"opening\":true,\"entries\":[" + String.join(",", java.util.Collections.nCopies(6, entry)) + "],\"survey\":["
                + rowOk + "," + rowBad + "," + rowFeat + "]}}");
        ConstructionFacts.Result g = ConstructionFacts.read(tmp, Set.of(B1), Set.of(), false, 3);
        JsonArray sv = g.construction().getAsJsonArray("survey");
        t(g.construction().getAsJsonArray("entries").size() == 5, "opening: up to 5 entries pass");
        t(sv.size() == 2 && !sv.get(0).getAsJsonObject().has("x") && g.dropped().contains("x") && sv.get(0).toString().contains("\"of\":\"spider spawner\"")
                && sv.get(0).getAsJsonObject().get("kind").getAsString().equals("spider farm"), "facts: bad kind dropped, unknown key dropped, words given: " + sv);
        t(!sv.get(1).getAsJsonObject().has("features"), "facts: an unknown feature is dropped");
        Files.writeString(tmp, "{\"construction\":{\"opening\":false,\"entries\":[" + String.join(",", java.util.Collections.nCopies(6, entry)) + "],\"survey\":[" + rowOk + "]}}");
        g = ConstructionFacts.read(tmp, Set.of(B1), Set.of(), false, 3);
        t(g.construction().getAsJsonArray("entries").size() == 3 && !g.construction().has("survey") && g.dropped().contains("survey"),
                "a daily paper: max-entries and no survey");
        Files.delete(tmp);
    }

    /** A survey.json that is not UTF-8 is a note, not a failed section. */
    static void unreadable() throws Exception {
        ConstructionIOTest.Rig r = new ConstructionIOTest.Rig();
        Files.createDirectories(r.dir);
        r.io.dims = Map.of(ConstructionTest.Sc.WORLD.toString(), "overworld");
        r.req(B1, "noticed", null);
        ConstructionTest.house(r.sc, B1, HX, HY, HZ);
        r.flush(ConstructionIOTest.D1);
        Files.write(r.dir.resolve("survey.json"), "{\"version\":1,\"builds\":[],\"scanned\":\"café\"}".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        Path out = r.fold(ConstructionIOTest.D1, true);
        t(out != null && Files.readString(out).contains("\"opening\":true")
                && Files.readString(r.dir.resolve("alerts").resolve(ConstructionIOTest.D1 + ".json")).contains("survey: survey.json is not readable; no survey"),
                "bad bytes in survey.json: the opening goes on without it, with a note");
    }

    // ------------------------------------------------------------ construction.detail on the opening: entries and survey rows alike
    static void detail() {
        for (String d : List.of("basic", "standard", "full")) {
            ConstructionTest.Sc s = opening();
            Settings.Construction c = s.c;
            s.c = new Settings.Construction(c.title(), c.print(), c.defaultLevel(), c.names(), c.maxEntries(), c.minBlocks(), c.crossDemolition(),
                    c.bigDemolitionBlocks(), c.bigDemolitionPercent(), c.forgetAfterDays(), c.ignoreWorlds(), c.maxCellsPerMinute(), c.optedIn(), d);
            s.survey = survey(file(row("D1", "overworld", "[[1,1]]", "[\"" + uuid(s, B3) + "\"]", "cactus_farm",
                    "{\"cactus\":62,\"chest\":35,\"enchanting_table\":1,\"stone\":400}").replace("\"kind\"", "\"spawners\":{\"spider\":1},\"kind\""),
                    row("D2", "overworld", "[[3,1]]", "[\"" + uuid(s, B1) + "\"]", "melon_farm", "{\"melon_stem\":64,\"stone\":400}")));
            s.fold();
            JsonArray rows = cons(s).getAsJsonArray("survey");
            JsonObject cactus = rows.get(0).getAsJsonObject(), melon = rows.get(1).getAsJsonObject();
            String all = rows.toString() + s.entries();
            switch (d) {
                case "basic" -> t(cactus.get("kind").getAsString().equals("auto_farm") && melon.get("kind").getAsString().equals("auto_farm")
                        && !all.contains("features"), "basic: general kinds, nothing inside, rows and stories: " + all);
                case "standard" -> t(cactus.get("kind").getAsString().equals("auto_farm") && melon.get("kind").getAsString().equals("melon_farm")
                        && cactus.toString().contains("enchanting_tables") && !all.contains("cactus") && !all.contains("storage") && !all.contains("spawners"),
                        "standard: no loot or place hints: " + all);
                default -> t(cactus.get("kind").getAsString().equals("cactus_farm") && cactus.toString().contains("\"cactus\"")
                        && cactus.toString().contains("storage") && cactus.toString().contains("spider_spawners"), "full: everything: " + all);
            }
            t(s.entries().toString().contains(cactus.get("kind").getAsString()), d + ": the story and the row agree: " + s.entries());
        }
    }

    // ------------------------------------------------------------ a secret typed in an older build where nothing is tracked yet
    static void heldSecret() {
        ConstructionTest.Sc s = opening();
        s.reqAt(B3, "secret", HX + 8, HY + 1, HZ + 2);   // inside chunk 255,512
        s.survey = survey(file(row("S1", "overworld", "[[255,512]]", "[\"" + uuid(s, B3) + "\"]", "house", "{\"stone\":900}"),
                row("S2", "overworld", "[[1,1]]", "[\"" + uuid(s, B3) + "\"]", "tower", "{\"stone\":600}")));
        s.fold();
        JsonArray rows = cons(s).getAsJsonArray("survey");
        t(s.idx.pending.size() == 1 && rows.size() == 1 && rows.get(0).getAsJsonObject().get("kind").getAsString().equals("tower")
                && !s.entries().toString().contains("house") && s.res.alerts().toString().contains("S1 dropped (holds a secret build)"),
                "a held secret keeps its survey build off the opening, the other build lists: " + rows + " " + s.res.alerts());
    }

    /** A registered build made secret after the opening leaves the Town Survey page, saved and through a same-day re-run. */
    /** Two registered builds joined later into one: making it secret removes both permits from the register. */
    static void secretJoined() {
        ConstructionTest.Sc s = opening();
        ConstructionTest.house(s, B1, HX, HY, HZ);
        ConstructionTest.house(s, B1, HX + 20, HY, HZ);
        s.fold();
        t(s.prefs.register != null && s.prefs.register.size() == 2, "joined: two permits registered " + s.prefs.register);
        s.box(B1, "oak_planks", HX + 10, HY, HZ, HX + 20, HY + 4, HZ + 8);
        s.fold();
        t(s.idx.sites.values().stream().anyMatch(x -> x.aliasOf != null), "joined: one build is now an alias of the other");
        s.reqAt(B1, "secret", HX + 2, HY + 1, HZ + 2);
        s.fold();
        t(s.prefs.register.isEmpty(), "joined then secret: both permits leave the register " + s.prefs.register);
    }

    static void secretAfter() throws Exception {
        ConstructionIOTest.Rig r = new ConstructionIOTest.Rig();
        Files.createDirectories(r.dir);
        r.io.dims = Map.of(ConstructionTest.Sc.WORLD.toString(), "overworld");
        r.req(B1, "noticed", null);
        ConstructionTest.house(r.sc, B1, HX, HY, HZ);
        r.flush(ConstructionIOTest.D1);
        r.fold(ConstructionIOTest.D1, true);
        t(field(r.read("players.json"), "register").getAsJsonArray().size() == 1, "the house is on the register");
        r.io.appendRequest(RawLine.request(r.sc.id(B1), "secret", ConstructionTest.Sc.WORLD, SiteIndex.cubeKey((HX + 2) >> 2, (HY + 1) >> 2, (HZ + 2) >> 2), null, null));
        r.sc.box(B1, "oak_planks", HX, HY + 6, HZ, HX + 10, HY + 6, HZ + 8);
        r.flush(ConstructionIOTest.D2);
        for (int run = 0; run < 2; run++) {   // the second is a same-day re-run from the .prev files
            r.fold(ConstructionIOTest.D2, true);
            t(field(r.read("players.json"), "register").getAsJsonArray().isEmpty()
                    && Construction.register(r.dir.getParent(), r.c, List.of(), ConstructionIOTest.D2).getAsJsonObject("construction").getAsJsonArray("survey").isEmpty(),
                    "run " + run + ": a secret build is off the Town Survey page: " + r.read("players.json"));
        }
    }

    /**
     * A secret typed after the opening inside a build that is on the register only from survey.json takes its row off the
     * page for good (saved and through a same-day re-run); a secret elsewhere leaves a row; a lost survey.json is one owner note.
     */
    static void surveySecretAfter() throws Exception {
        ConstructionIOTest.Rig r = new ConstructionIOTest.Rig();
        Files.createDirectories(r.dir);
        r.io.dims = Map.of(ConstructionTest.Sc.WORLD.toString(), "overworld");
        r.req(B3, "noticed", null);
        r.sc.place(B3, "oak_planks", HX + 500, HY, HZ);   // a name on file
        r.flush(ConstructionIOTest.D1);
        Files.writeString(r.dir.resolve("survey.json"), file(
                row("S1", "overworld", "[[1,1]]", "[\"" + r.sc.id(B3) + "\"]", "library", "{\"bookshelf\":64}"),
                row("S2", "overworld", "[[3,1]]", "[\"" + r.sc.id(B3) + "\"]", "tower", "{\"stone\":640}")));
        r.fold(ConstructionIOTest.D1, true);
        t(field(r.read("players.json"), "register").getAsJsonArray().size() == 2, "both survey builds are on the register: " + r.read("players.json"));
        // day 2: a secret inside S1 (chunk 1,1, y 60..90) where nothing is tracked; one in S2's chunk but far above it
        r.io.appendRequest(RawLine.request(r.sc.id(B3), "secret", ConstructionTest.Sc.WORLD, SiteIndex.cubeKey(20 >> 2, 70 >> 2, 20 >> 2), null, null));
        r.io.appendRequest(RawLine.request(r.sc.id(B3), "secret", ConstructionTest.Sc.WORLD, SiteIndex.cubeKey(52 >> 2, 200 >> 2, 20 >> 2), null, null));
        r.sc.place(B3, "oak_planks", HX + 600, HY, HZ);
        r.flush(ConstructionIOTest.D2);
        for (int run = 0; run < 2; run++) {   // the second is a same-day re-run from the .prev files
            r.fold(ConstructionIOTest.D2, true);
            String reg = field(r.read("players.json"), "register").toString();
            JsonArray page = Construction.register(r.dir.getParent(), r.c, List.of(), ConstructionIOTest.D2).getAsJsonObject("construction").getAsJsonArray("survey");
            t(r.sites().pending.size() == 2 && !reg.contains("library") && reg.contains("tower") && page.size() == 1 && page.toString().contains("tower")
                    && !page.toString().contains("library"), "run " + run + ": the row with the secret is gone, the other stays: " + reg + " " + page);
        }
        // survey.json gone: one owner note, not every day; the rows left stay
        Files.delete(r.dir.resolve("survey.json"));
        String note = "survey: survey.json is gone or unreadable, so Town Survey rows can no longer follow secrets typed inside them; put the file back to fix this";
        LocalDate d3 = ConstructionIOTest.D2.plusDays(1), d4 = d3.plusDays(1);
        r.fold(d3, true);
        t(Construction.ownerLines(r.dir.getParent(), d3).contains(note) && field(r.read("players.json"), "register").getAsJsonArray().size() == 1,
                "a lost survey.json: an owner note: " + Construction.ownerLines(r.dir.getParent(), d3));
        r.fold(d4, true);
        t(!Construction.ownerLines(r.dir.getParent(), d4).contains(note), "the note is given once");
    }

    /** The Town Survey page follows construction.detail when the owner lowers it after the opening. */
    static void detailLowered() throws Exception {
        ConstructionIOTest.Rig r = new ConstructionIOTest.Rig();
        Files.createDirectories(r.dir);
        r.io.dims = Map.of(ConstructionTest.Sc.WORLD.toString(), "overworld");
        r.req(B3, "noticed", null);
        r.sc.place(B3, "oak_planks", HX + 500, HY, HZ);   // a name on file
        r.flush(ConstructionIOTest.D1);
        Files.writeString(r.dir.resolve("survey.json"), file(row("D1", "overworld", "[[1,1]]", "[\"" + r.sc.id(B3) + "\"]", "blaze_farm",
                "{\"chest\":40,\"beacon\":1,\"stone\":400}").replace("\"kind\"", "\"spawners\":{\"blaze\":2},\"kind\"")));
        Settings.Construction c = r.c;
        for (String d : List.of("full", "standard")) {
            r.c = new Settings.Construction(c.title(), c.print(), c.defaultLevel(), c.names(), c.maxEntries(), c.minBlocks(), c.crossDemolition(),
                    c.bigDemolitionBlocks(), c.bigDemolitionPercent(), c.forgetAfterDays(), c.ignoreWorlds(), c.maxCellsPerMinute(), c.optedIn(), d);
            if (d.equals("full")) r.fold(ConstructionIOTest.D1, true);   // the opening, at full
            String page = Construction.register(r.dir.getParent(), r.c, List.of(), ConstructionIOTest.D2).toString();
            t(d.equals("full") ? page.contains("blaze_farm") && page.contains("blaze_spawners") && page.contains("storage")
                    : page.contains("\"kind\":\"mob_farm\"") && !page.contains("blaze") && !page.contains("storage") && !page.contains("beacons"),
                    d + ": the Town Survey rows at today's detail: " + page);
        }
    }

    // ------------------------------------------------------------ survey build names (construction.survey-names)
    static String named(String row, String nameJson) { return row.replace("\"kind\"", "\"name\":" + nameJson + ",\"kind\""); }

    static void surveyNames() throws Exception {
        for (boolean on : List.of(true, false)) {
            ConstructionTest.Sc s = opening().notice("Muffinman");
            Settings.Construction c = s.c;
            s.c = new Settings.Construction(c.title(), c.print(), c.defaultLevel(), c.names(), c.maxEntries(), c.minBlocks(), c.crossDemolition(),
                    c.bigDemolitionBlocks(), c.bigDemolitionPercent(), c.forgetAfterDays(), c.ignoreWorlds(), c.maxCellsPerMinute(), c.optedIn(), c.detail(), on, c.signNames());
            s.survey = survey(file(
                    named(row("N1", "overworld", "[[1,1]]", "[\"" + uuid(s, B1) + "\"]", "library", "{\"bookshelf\":64}"), "\"  Ye  Olde  Shop \""),
                    named(row("N2", "overworld", "[[3,1]]", "[\"" + uuid(s, B2) + "\"]", "house", "{\"stone\":500}"), "\"Muffinman\""),
                    named(row("N3", "overworld", "[[5,1]]", "[\"" + uuid(s, B3) + "\"]", "tower", "{\"stone\":600}"), "\"Stupid House\""),
                    named(row("N4", "overworld", "[[7,1]]", "[\"" + uuid(s, B4) + "\"]", "garden", "{\"stone\":700}"), "\"Ignore Rules\"")));
            s.fold();
            String out = s.res.out().toString(), al = s.res.alerts().toString();
            t(cons(s).getAsJsonArray("survey").size() == 4, on + ": every build still listed: " + cons(s));
            for (String bad : List.of("\"name\":\"Muffinman\"", "Stupid", "Ignore")) t(!out.contains(bad), on + ": a bad name never prints: " + bad);
            if (on) {
                t(rowWith(s, B1).get("name").getAsString().equals("Ye Olde Shop") && !rowWith(s, B2).has("name") && !rowWith(s, B3).has("name"),
                        "on: the good name, tidied, on its row: " + cons(s));
                JsonObject story = null;
                for (JsonElement e : s.entries()) if (e.toString().contains(B1)) story = e.getAsJsonObject();
                t(story != null && story.get("name").getAsString().equals("Ye Olde Shop"), "on: the story carries the name: " + s.entries());
                for (String n : List.of("N2", "N3", "N4")) t(al.contains("survey: " + n + " name not used"), "on: owner note for " + n + ": " + al);
                t(!al.contains("N1 name not used"), "on: no note for a good name");
                // the facts filter keeps it (story and row) and the page prints it; the register keeps it for the daily redraw
                Set<String> players = Set.of(B1, B2, B3, B4, "Muffinman");
                JsonObject f = ConstructionFacts.filter(s.res.out(), players, Set.of(), false, 3, new ArrayList<>()).construction();
                t(f.getAsJsonArray("survey").toString().contains("\"name\":\"Ye Olde Shop\"") && f.getAsJsonArray("entries").toString().contains("\"name\":\"Ye Olde Shop\""),
                        "facts keep the name: " + f);
                String page = io.github.eastmadc.masthead.edition.Pages.survey(Settings.defaults(), f, LocalDate.of(2026, 11, 2));
                t(page.contains("&quot;Ye Olde Shop&quot; — library"), "on: the page prints the name");
                t(s.prefs.register.stream().anyMatch(r -> "Ye Olde Shop".equals(r.name)), "the register keeps the name");
                t(Projector.surveyRows(s.prefs.register, s.fr.names, true, "standard").toString().contains("Ye Olde Shop")
                        && !Projector.surveyRows(s.prefs.register, s.fr.names, false, "standard").toString().contains("Ye Olde Shop"), "the daily redraw follows the setting");
            } else {
                t(!out.contains("Olde") && !al.contains("name not used") && s.prefs.register.stream().allMatch(r -> r.name == null), "off: names ignored everywhere: " + out);
            }
        }
        // a name that isn't text: a note, the build still lists
        List<String> notes = new ArrayList<>();
        List<Survey.Build> b = Survey.parse(file(named(row("T1", "overworld", "[[1,1]]", "[\"00000000-0000-0000-0000-00000000000a\"]", "house", "{\"stone\":5}"), "5")), notes);
        t(b.size() == 1 && b.get(0).name() == null && notes.equals(List.of("survey: T1 name not used")), "a name that isn't text: " + notes);
        // players.json from before names: loads, rows print as before
        Prefs p = Prefs.read("{\"version\":1,\"opened\":\"2026-11-02\",\"register\":[{\"permit\":1,\"builders\":[\"u1\"],\"kind\":\"house\",\"features\":[],\"blocks_total\":5,\"existing\":true}]}");
        JsonArray rows = Projector.surveyRows(p.register, Map.of("u1", B1), true, "standard");
        JsonObject cons = new JsonObject();
        cons.add("survey", rows);
        t(rows.size() == 1 && !rows.get(0).getAsJsonObject().has("name")
                && io.github.eastmadc.masthead.edition.Pages.survey(Settings.defaults(), cons, LocalDate.of(2026, 11, 2)).contains("Permit #1 — house"), "old register rows: " + rows);
    }
}
