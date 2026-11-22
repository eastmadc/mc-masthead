package io.github.eastmadc.masthead.construction;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eastmadc.masthead.writer.Spend;
import io.github.eastmadc.masthead.writer.Writer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.eastmadc.masthead.construction.ConstructionTest.ALEX;
import static io.github.eastmadc.masthead.construction.ConstructionTest.STEVE;
import static io.github.eastmadc.masthead.construction.ConstructionTest.X;
import static io.github.eastmadc.masthead.construction.ConstructionTest.Y;
import static io.github.eastmadc.masthead.construction.ConstructionTest.Z;

/**
 * The kinds desk (AI build kinds) and how the fold uses its answers: parsing, vetoes, the 30-card cap, the card's privacy,
 * the label > AI > rules > guess order, the two-edition wait, the cache on the site, and a regression fixture of 22
 * builds (numbers and block keys only) with recorded AI answers. Plain main(), a stub writer, no network.
 */
public final class KindDeskTest {
    static int ok = 0;

    static void t(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
        ok++;
    }

    /** A writer that answers from a string (or fails), and remembers what it was sent. */
    static final class Stub implements Writer {
        String reply;
        Exception fail;
        int calls;
        String system, user;

        Stub(String reply) { this.reply = reply; }

        @Override public String name() { return "stub"; }

        @Override public String write(String system, String user, Duration timeout) throws Exception {
            calls++;
            this.system = system;
            this.user = user;
            if (fail != null) throw fail;
            return reply;
        }
    }

    public static void main(String[] args) throws Exception {
        parsing();
        foldOrder();
        cardsAndPrivacy();
        previewNeverSaves();
        reprintAfterListChange();
        kindsNeverBuild();
        budget();
        fixture();
        System.out.println("KindDeskTest: " + ok + " checks passed");
    }

    static final Path GOLDEN = Path.of("src/test/golden/kinds");

    /** The 22 fixture cards, in order (ids A..V), with key info as the server would give it. */
    static List<KindDesk.Card> fixtureCards() throws Exception {
        JsonArray a = JsonParser.parseString(Files.readString(GOLDEN.resolve("cards.json"))).getAsJsonArray();
        Map<String, Integer> ki = new HashMap<>();
        List<KindDesk.Card> out = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            JsonObject j = a.get(i).getAsJsonObject().deepCopy();
            t(j.remove("id").getAsString().equals(KindDesk.id(i)), "fixture ids are the desk's stand-in ids");
            for (String k : j.getAsJsonObject("materials_net").keySet())
                ki.put(k, KeyRules.info(k, Set.of("wheat", "carrots", "potatoes", "beetroots", "melon_stem", "pumpkin_stem").contains(k) ? Set.of("crops") : Set.of()));
            out.add(KindDesk.card(100 + i, j, ki));
        }
        return out;
    }

    // ------------------------------------------------------------ the answer
    static void parsing() throws Exception {
        List<KindDesk.Card> cards = fixtureCards();
        for (String junk : new String[] {null, "", "I can't help with that.", "{not json", "[1,2]", "{\"A\":\"house\"}"})
            t(KindDesk.read(junk, cards).isEmpty(), "junk answer: nothing kept: " + junk);
        var got = KindDesk.read("Here you go:\n```json\n{\"A\": {\"kind\": \"Mob_Farm \", \"second\": \"tower\", \"confidence\": \"high\"}}\n```\nDone.", cards);
        t(got.size() == 1 && got.get(100).kind().equals("mob_farm") && got.get(100).confidence().equals("high"), "fenced JSON, any case: " + got);
        got = KindDesk.read("{\"A\":{\"kind\":\"castle\",\"second\":\"tower\",\"confidence\":\"high\"},\"E\":{\"kind\":\"mine_tunnel\",\"second\":\"build\"},"
                + "\"ZZ\":{\"kind\":\"house\"},\"W\":{\"kind\":\"house\"}}", cards);
        t(got.size() == 1 && got.get(100).kind().equals("tower") && got.get(100).confidence().equals("low"), "off-list kind: second, at low confidence: " + got);
        t(!got.containsKey(104), "off-list kind and second (\"build\"): no AI kind");
        // vetoes: one builder is never a base; 8 chests are not storage; a 9-high build is no tower
        got = KindDesk.read("{\"D\":{\"kind\":\"base\",\"second\":\"house\",\"confidence\":\"medium\"},\"E\":{\"kind\":\"base\",\"second\":\"storage\"},"
                + "\"I\":{\"kind\":\"tower\",\"second\":\"farm\",\"confidence\":\"sure\"},\"K\":{\"kind\":\"bee_farm\"},\"P\":{\"kind\":\"bee_farm\"},"
                + "\"R\":{\"kind\":\"farm\",\"second\":\"decoration\"},\"G\":{\"kind\":\"villager_breeder\"},\"B\":{\"kind\":\"base\"}}", cards);
        t(got.get(103).kind().equals("house") && got.get(103).confidence().equals("low"), "base veto -> second: " + got.get(103));
        t(!got.containsKey(104), "base veto, storage veto -> the rules decide");
        t(got.get(108).kind().equals("farm") && got.get(110).kind().equals("bee_farm") && !got.containsKey(115), "tower and bee farm vetoes");
        t(got.get(117).kind().equals("decoration") && got.get(106).kind().equals("villager_breeder") && got.get(101).kind().equals("base"), "crop veto; villagers never vetoed; 5 builders make a base");
        // the call: one write, every card, a failure is a note
        Stub w = new Stub("{\"A\":{\"kind\":\"mob_farm\",\"second\":\"tower\",\"confidence\":\"medium\"}}");
        List<String> notes = new ArrayList<>();
        var ask = KindDesk.ask(w, cards, Duration.ofSeconds(150), notes);
        t(w.calls == 1 && w.system.equals(KindDesk.RULES) && w.user.startsWith("BUILDS:\n[") && w.user.contains("\"id\":\"V\"") && ask.size() == 1,
                "one call for every card: " + ask);
        t(notes.equals(List.of("build kinds: AI named 1 of 22 builds; rules used for the rest")), "a short answer is a note: " + notes);
        w.fail = new java.io.IOException("timeout");
        notes.clear();
        t(KindDesk.ask(w, cards, Duration.ofSeconds(150), notes).isEmpty() && notes.equals(List.of("build kinds: AI unavailable (IOException); rules used")), "failure: a note");
        t(KindDesk.ask(w, List.of(), Duration.ofSeconds(150), notes).isEmpty() && w.calls == 2, "no cards: no call");
        for (String k : Kinds.ALL) t(KindDesk.RULES.contains("\n- " + k + ": "), "the prompt defines " + k);
        t(!KindDesk.RULES.contains("tunnel") && !KindDesk.RULES.contains("flying_machine") && !KindDesk.RULES.contains("evidence"), "R10/R11: dropped kinds, no evidence");
    }

    static KindDesk.Ask ask(String kind, SiteIndex.Site s) {
        return new KindDesk.Ask(kind, "medium", s.net, KindDesk.top5(s.mats), "");
    }

    // ------------------------------------------------------------ label > AI > rules > guess, and the waits
    static void foldOrder() throws Exception {
        ConstructionTest.Sc s = new ConstructionTest.Sc().notice(STEVE);
        ConstructionTest.house(s, STEVE, X, Y, Z);
        s.fold();
        SiteIndex.Site site = s.idx.sites.firstEntry().getValue();
        t(site.permit != null && site.kind.equals("house") && site.aiKind == null, "printed, rules say house");
        s.ai = Map.of(site.id, ask("base", site));
        s.fold();
        t(site.kind.equals("base") && site.aiKind.equals("base") && site.aiAsked.equals("2026-11-03") && site.aiTop5.size() == 5,
                "R3: the first AI kind applies at once, even on a printed build");
        t(!"high".equals(s.fr.out.get(site.id).confidence), "R1: an AI kind the rules don't back is hedged");
        s.ai = Map.of(site.id, ask("storage", site));
        s.fold();
        t(site.kind.equals("base") && "storage".equals(site.kindPending) && site.aiKind.equals("storage"), "R3: a later AI change waits");
        s.ai = Map.of();   // the re-ask failed: the saved AI kind still counts
        s.fold();
        t(site.kind.equals("storage") && site.aiKind.equals("storage"), "R3: it applies after two editions");
        // only a mob kind whose own mob is the top death, at medium or high confidence, skips the wait; these all wait
        List<String> top5 = KindDesk.top5(site.mats);
        for (KindDesk.Ask shut : List.of(new KindDesk.Ask("hoglin_farm", "medium", site.net, top5, ""),   // no deaths
                new KindDesk.Ask("mob_farm", "high", site.net, top5, "zombie"),                          // no death proves the general kind
                new KindDesk.Ask("hoglin_farm", "low", site.net, top5, "hoglin"),                        // a low answer
                new KindDesk.Ask("iron_farm", "high", site.net, top5, "cow"),
                new KindDesk.Ask("zombie_farm", "high", site.net, top5, "hoglin"),                       // another mob's deaths
                new KindDesk.Ask("house", "high", site.net, top5, "hoglin"))) {                          // not a farm
            s.ai = Map.of(site.id, shut);
            s.fold();
            t(site.kind.equals("storage") && shut.kind().equals(site.kindPending), "this answer waits two papers: " + shut + " printed " + site.kind);
        }
        // a printed build where hoglins die in numbers does not keep its old kind for one more paper
        s.ai = Map.of(site.id, new KindDesk.Ask("hoglin_farm", "medium", site.net, top5, "hoglin"));
        s.fold();
        t(site.kind.equals("hoglin_farm") && site.kindPending == null, "a mob farm kind its deaths show applies at once: " + site.kind);
        s.ai = Map.of(site.id, ask("storage", site));
        s.fold();
        s.ai = Map.of();
        s.fold();
        t(site.kind.equals("storage"), "back to storage for the checks below");
        s.fold();
        t(site.kind.equals("storage"), "R3: a failed re-ask keeps the old AI kind, never the rules'");
        s.req(STEVE, "kind", "tower");
        s.ai = Map.of(site.id, ask("garden", site));
        s.fold();
        t(site.kind.equals("tower") && "high".equals(s.fr.out.get(site.id).confidence) && site.aiKind.equals("storage"), "the builder's label beats the AI");
        s.ai = Map.of();

        // R9: an answer for a different build is ignored
        s = new ConstructionTest.Sc().notice(STEVE);
        ConstructionTest.house(s, STEVE, X, Y, Z);
        s.fold();
        site = s.idx.sites.firstEntry().getValue();
        s.ai = Map.of(site.id, new KindDesk.Ask("base", "high", site.net * 2, KindDesk.top5(site.mats), ""));
        s.fold();
        t(site.aiKind == null && site.kind.equals("house"), "R9: net more than 25% off: ignored");
        s.ai = Map.of(site.id, new KindDesk.Ask("base", "high", site.net, List.of("a", "b", "c", "d", "oak_planks"), ""));
        s.fold();
        t(site.aiKind == null && site.kind.equals("house"), "R9: under 3 of the top 5 materials the same: ignored");
        // R1: an AI kind the rules back at 1.5x for 2 folds is high
        s.ai = Map.of(site.id, ask("house", site));
        s.fold();
        s.ai = Map.of();
        s.fold();
        t(site.aiKind.equals("house") && "high".equals(s.fr.out.get(site.id).confidence), "R1: AI and rules agree: high");
        // F1: the AI's own "low" is kept, on the day of the answer and after
        s.ai = Map.of(site.id, new KindDesk.Ask("house", "low", site.net, KindDesk.top5(site.mats), ""));
        s.fold();
        String lowDay = s.fr.out.get(site.id).confidence;
        s.ai = Map.of();
        s.fold();
        t(site.kind.equals("house") && "low".equals(lowDay) && "low".equals(s.fr.out.get(site.id).confidence), "F1: a low AI answer stays low: " + lowDay + ", "
                + s.fr.out.get(site.id).confidence);

        // R4: a stored "build" is no kind; a shape guess is low
        s = new ConstructionTest.Sc().notice(STEVE);
        s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9);
        s.fold();
        site = s.idx.sites.firstEntry().getValue();
        t(site.kind.equals("house") && "low".equals(s.fr.out.get(site.id).confidence) && s.entry(0).get("kind").getAsString().equals("house"),
                "no rule: the shape's guess, at low confidence: " + site.kind);
        site.kind = "build";
        site.kindStreak = 7;
        site.kindPending = null;
        s.fold();
        t(site.kind.equals("house") && site.kindStreak == 1, "R4: a saved \"build\" counts as no kind");

        // R8: a taken-down build is never sent, and keeps its last kind
        s = new ConstructionTest.Sc().notice(STEVE);
        s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9);
        s.fold();
        site = s.idx.sites.firstEntry().getValue();
        s.boxBreak(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9, 500);
        s.fold();
        t(site.retired && site.kind.equals("house"), "taken down, kind kept");
        t(KindDesk.cards(s.idx, s.prefs, s.fr, cfg(s)).isEmpty(), "R8: a retired build is never sent");
        s.fold();
        t(s.fr.out.get(site.id).demolitionNow && s.entry(0).get("tag").getAsString().equals("demolition") && s.entry(0).get("kind").getAsString().equals("house")
                && KindDesk.cards(s.idx, s.prefs, s.fr, cfg(s)).isEmpty(), "R8: nor on its demolition notice, which keeps its kind");
    }

    static Fold.Cfg cfg(ConstructionTest.Sc s) {
        return new Fold.Cfg(s.c, s.hidden, s.day.minusDays(1), ConstructionTest.KEY_INFO);   // the date of the fold just run
    }

    // ------------------------------------------------------------ which builds, in what order, and what the card holds
    static void cardsAndPrivacy() throws Exception {
        ConstructionTest.Sc s = new ConstructionTest.Sc();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 35; i++) names.add(String.format("Stand_In%02d", i));
        s.notice(names.toArray(new String[0]));
        for (int i = 0; i < 35; i++) ConstructionTest.hut(s, names.get(i), X + 40 * i, Y, Z, "oak_planks");
        s.fold();
        List<KindDesk.Card> cards = KindDesk.cards(s.idx, s.prefs, s.fr, cfg(s));
        t(s.idx.sites.size() == 35 && cards.size() == KindDesk.MAX_CARDS, "R10: at most 30 cards: " + cards.size());
        Stub w = new Stub("{}");
        KindDesk.ask(w, cards, Duration.ofSeconds(1), new ArrayList<>());
        t(w.user.contains("\"id\":\"AD\"") && !w.user.contains("\"id\":\"AE\""), "R10: ids A..AD");
        // a build asked recently is not due; one that grew a lot is
        SiteIndex.Site one = s.idx.sites.firstEntry().getValue();
        one.aiKind = "house"; one.aiConf = "high"; one.aiNet = one.net; one.aiTop5 = KindDesk.top5(one.mats); one.aiAsked = s.day.minusDays(1).toString();
        LocalDate today = s.day;
        t(KindDesk.due(one, one.net, today), "an answer from the old 20-kind list is asked again");
        one.aiList = Kinds.LIST;
        t(!KindDesk.due(one, one.net, today) && KindDesk.due(one, one.net * 2, today) && KindDesk.due(one, one.net / 2, today), "R8: re-ask on 25% growth or shrinkage");
        t(!KindDesk.due(one, one.net, today.plusDays(12)) && KindDesk.due(one, one.net, today.plusDays(13)), "R8: re-ask after 14 days");
        one.aiConf = "low";
        t(KindDesk.due(one, one.net, today), "R8: a low answer is re-asked after a day");
        one.aiConf = "high";
        one.aiTop5 = List.of("oak_planks", "a", "b", "c", "d");
        t(KindDesk.due(one, one.net, today), "R8: re-ask when 2 of the top 5 materials changed");
        // a same-day reprint reuses today's answers, but not one from an older kinds list (it is asked again)
        one.aiAsked = today.toString();
        t(KindDesk.today(s.idx, today.toString(), Map.of(), true).containsKey(one.id), "a reprint reuses today's answer");
        one.aiList = Kinds.LIST - 1;
        t(!KindDesk.today(s.idx, today.toString(), Map.of(), true).containsKey(one.id), "a reprint asks again after the kinds list changed");
        t(KindDesk.today(s.idx, today.toString(), Map.of(), false).get(one.id).list() == Kinds.LIST - 1, "the real fold keeps that answer, under its own list");
        one.aiList = Kinds.LIST;

        // through the real practice fold: today's news first; the card holds numbers and block keys only
        ConstructionIOTest.Rig r = new ConstructionIOTest.Rig();
        LocalDate d1 = ConstructionIOTest.D1, d2 = ConstructionIOTest.D2;
        r.req(STEVE, "noticed", null);
        r.req(ALEX, "noticed", null);
        ConstructionTest.house(r.sc, STEVE, X, Y, Z);
        r.req(STEVE, "name", "Castle Grayskull");
        ConstructionTest.hut(r.sc, ALEX, X + 200, Y, Z + 300, "oak_planks");
        r.flush(d1);
        r.fold(d1, true);
        for (int i = 0; i < 20; i++) r.sc.place(STEVE, "oak_planks", X + i % 10, Y + 6, Z + 1 + i / 10);   // the house grows, with no news
        r.req(ALEX, "done", null);                                                                       // the hut has news (a ribbon)
        r.flush(d2);
        cards = practice(r, d2);
        SiteIndex idx = r.sites();
        t(cards.size() == 2 && idx.sites.get(cards.get(0).site()).crew.contains(r.sc.id(ALEX).toString()), "today's news first, before a later-grown build");
        t("Castle Grayskull".equals(idx.sites.get(cards.get(1).site()).name), "the house has a name");
        String text = KindDesk.prompt(cards.stream().map(KindDesk.Card::shown).toList());
        for (String bad : List.of(STEVE, ALEX, "Grayskull", String.valueOf(X), String.valueOf(Z), String.valueOf(X + 200), r.sc.id(STEVE).toString()))
            t(!text.contains(bad), "the card never holds " + bad);
        t(cards.get(1).shown().keySet().equals(Set.of("net_blocks", "height_blocks", "footprint_blocks", "occupied_4x4_columns", "dimension", "builders",
                "building_days", "pct_placed_blocks_in_slime_chunks", "blocks_placed_over_air_or_fluid", "water_buckets_poured", "materials_net")), "card keys");
        // a labelled build or a private builder's build is never sent
        r.req(STEVE, "kind", "house");
        r.req(ALEX, "private", null);
        t(practice(r, d2).isEmpty(), "labelled and private builds are not sent");
    }

    /** The kinds desk's practice fold on the real files (it writes nothing). */
    static List<KindDesk.Card> practice(ConstructionIOTest.Rig r, LocalDate d) {
        List<KindDesk.Card> cards = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        t(r.io.analyze(job(r, d, false, Map.of(), cards), notes) == null && notes.isEmpty(), "practice fold: " + notes);
        return cards;
    }

    // ------------------------------------------------------------ the files: a practice fold and a preview save nothing
    static void previewNeverSaves() throws Exception {
        ConstructionIOTest.Rig r = new ConstructionIOTest.Rig();
        r.req(STEVE, "noticed", null);
        ConstructionTest.house(r.sc, STEVE, X, Y, Z);
        LocalDate d1 = ConstructionIOTest.D1;
        r.flush(d1);
        List<KindDesk.Card> cards = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        t(r.io.analyze(job(r, d1, false, Map.of(), cards), notes) == null && cards.size() == 1 && notes.isEmpty(), "practice fold: one card");
        t(r.read("sites.json") == null && !Files.exists(r.dir.resolve("out").resolve("preview.json")), "practice fold: nothing written");
        Map<Integer, KindDesk.Ask> ai = KindDesk.ask(new Stub("{\"A\":{\"kind\":\"villager_breeder\",\"second\":\"house\",\"confidence\":\"medium\"}}"),
                cards, Duration.ofSeconds(1), notes);
        Path pv = r.io.analyze(job(r, d1, false, ai, null), notes);
        t(Files.readString(pv).contains("\"kind\":\"villager_breeder\"") && r.read("sites.json") == null, "a preview prints the answer but saves nothing");
        Path out = r.io.analyze(job(r, d1, true, ai, null), notes);
        String saved = r.read("sites.json");
        t(Files.readString(out).contains("\"kind\":\"villager_breeder\"") && saved.contains("\"ai_kind\":\"villager_breeder\"") && saved.contains("\"ai_asked\":\"" + d1 + "\""),
                "the published fold saves the AI kind on the build");
        r.req(STEVE, "done", null);   // a ribbon, so the next paper has an entry that shows the kind
        Path pv2 = r.io.analyze(job(r, d1.plusDays(1), false, Map.of(), null), notes);
        t(r.read("sites.json").equals(saved) && Files.readString(pv2).contains("villager_breeder"), "the next preview reads the saved kind and changes no file");
    }

    // ------------------------------------------------------------ a same-day reprint after the kinds list grew
    static void reprintAfterListChange() throws Exception {
        ConstructionIOTest.Rig r = new ConstructionIOTest.Rig();
        r.req(STEVE, "noticed", null);
        ConstructionTest.house(r.sc, STEVE, X, Y, Z);
        LocalDate d1 = ConstructionIOTest.D1;
        r.flush(d1);
        List<KindDesk.Card> cards = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        r.io.analyze(job(r, d1, false, Map.of(), cards), notes);
        r.io.analyze(job(r, d1, true, KindDesk.read("{\"A\":{\"kind\":\"villager_breeder\",\"confidence\":\"medium\"}}", cards), null), notes);
        String saved = r.read("sites.json"), now = "\"ai_list\":" + Kinds.LIST, old = "\"ai_list\":" + (Kinds.LIST - 1);
        t(saved.contains(now), "the morning paper saves the list it asked from");
        Files.writeString(r.dir.resolve("sites.json"), saved.replace(now, old));   // the plugin was updated: the morning's answer is from the older list
        cards.clear();
        r.io.analyze(job(r, d1, false, Map.of(), cards), notes);
        t(cards.size() == 1, "the reprint asks the build again: " + cards.size());
        Path out = r.io.analyze(job(r, d1, true, Map.of(), null), notes);   // no new answer came (the AI call failed)
        saved = r.read("sites.json");
        t(Files.readString(out).contains("\"kind\":\"villager_breeder\"") && saved.contains("\"ai_kind\":\"villager_breeder\""), "the reprint keeps the morning's kind");
        t(saved.contains(old), "still under the older list, so the build is asked again tomorrow");
        out = r.io.analyze(job(r, d1, true, KindDesk.read("{\"A\":{\"kind\":\"trading_hall\",\"confidence\":\"medium\"}}", cards), null), notes);
        saved = r.read("sites.json");
        t(Files.readString(out).contains("\"kind\":\"trading_hall\"") && saved.contains("\"ai_kind\":\"trading_hall\"") && saved.contains(now), "a new answer wins, under the new list");
        t(notes.isEmpty(), "no notes: " + notes);
    }

    static ConstructionIO.Job job(ConstructionIOTest.Rig r, LocalDate d, boolean publish, Map<Integer, KindDesk.Ask> ai, List<KindDesk.Card> cards) {
        return new ConstructionIO.Job(d, publish, r.c, r.hidden, ConstructionTest.KEY_INFO, d, new AtomicInteger(ConstructionIO.RUNNING), Map.of(), ai, cards);
    }

    // ------------------------------------------------------------ "build" never leaves Kinds
    static void kindsNeverBuild() {
        Random rnd = new Random(7);
        for (int i = 0; i < 20000; i++) {
            int[] v = new int[26];
            for (int j = 0; j < v.length; j++) v[j] = rnd.nextInt(j == 0 ? 6000 : 400);
            Kinds.M m = new Kinds.M(v[0] + 1, v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12], v[13], v[14], v[15], v[16], v[17],
                    v[18], v[19], v[20], v[21], v[22], v[23], v[24], v[25] % 101);
            String f = Kinds.fallback(m), k = Kinds.kind(m);
            if (!Kinds.ALL.contains(f) || !(k.equals("build") || Kinds.ALL.contains(k))) t(false, "a kind outside the list: " + f + " / " + k + " for " + m);
        }
        t(true, "fallback: always a listed kind");
        for (String k : Kinds.ALL) t(!Kinds.display(k).matches("(?i).*\\b(build|structure)\\b.*"), "display " + k);
    }

    // ------------------------------------------------------------ R7: a paid writer skips the kinds call when money is short
    static void budget() throws Exception {
        Path f = Files.createTempDirectory("spend").resolve("spend.json");
        Spend sp = new Spend(f, 10, m -> { });
        t(!sp.low() && !new Spend(f, 0, m -> { }).low(), "no ledger yet: room left; no budget: never low");
        Files.writeString(f, "{\"month\":\"" + YearMonth.now() + "\",\"usd\":8.99}");
        t(!sp.low(), "over 10% left");
        Files.writeString(f, "{\"month\":\"" + YearMonth.now() + "\",\"usd\":9.01}");
        t(sp.low(), "under 10% left");
        Files.writeString(f, "{\"month\":\"" + YearMonth.now() + "\",\"usd\":1,\"pending\":true}");
        t(sp.low(), "an unsettled request");
        Files.writeString(f, "broken");
        t(sp.low(), "an unreadable ledger");
    }

    // ------------------------------------------------------------ the 22 fixture builds (invented), with sample AI answers
    static void fixture() throws Exception {
        List<KindDesk.Card> cards = fixtureCards();
        // the sample answers use the first list's names; these four were renamed, the dropped ones (mine_tunnel, flying_machine) stay
        Map<String, String> renamed = Map.of("crop_farm", "farm", "villager_trading_hall", "trading_hall", "flower_garden", "garden", "redstone_machine", "redstone");
        Map<String, String> expect = Map.of(
                "run1.txt", "mob_farm base storage house house house house trading_hall farm auto_farm bee_farm mob_farm auto_farm farm bridge garden auto_farm "
                        + "decoration slime_farm decoration redstone decoration",
                "p4-r1.txt", "mob_farm base storage house house house trading_hall trading_hall farm auto_farm bee_farm item_collector auto_farm farm bridge garden "
                        + "auto_farm trading_hall slime_farm decoration redstone decoration",
                "p4-r2.txt", "mob_farm base storage house house house house trading_hall farm auto_farm bee_farm slime_farm auto_farm farm bridge garden auto_farm "
                        + "trading_hall slime_farm decoration redstone decoration");
        for (Map.Entry<String, String> e : expect.entrySet()) {
            String raw = Files.readString(GOLDEN.resolve(e.getKey()));
            for (Map.Entry<String, String> n : renamed.entrySet()) raw = raw.replace("\"" + n.getKey() + "\"", "\"" + n.getValue() + "\"");
            Map<Integer, KindDesk.Ask> got = KindDesk.read(raw, cards);
            List<String> kinds = new ArrayList<>();
            for (KindDesk.Card c : cards) kinds.add(got.containsKey(c.site()) ? got.get(c.site()).kind() : "-");
            t(String.join(" ", kinds).equals(e.getValue()), e.getKey() + ": " + kinds);
        }
        // the hard cases: a big one-builder home, the shared base, the chest hall
        Map<Integer, KindDesk.Ask> run1 = KindDesk.read(Files.readString(GOLDEN.resolve("run1.txt")), cards);
        t(run1.get(103).kind().equals("house") && cards.get(3).crew() == 1 && cards.get(3).net() == 1623, "1-builder 1623-block home: base vetoed, house");
        t(run1.get(101).kind().equals("base") && cards.get(1).crew() >= 3, "shared build: base");
        t(run1.get(102).kind().equals("storage") && cards.get(2).chests() >= 173, "173-chest build: storage");
        // the operator's check: GoldenTool prints this exact prompt
        String p = KindDesk.prompt(cards.stream().map(KindDesk.Card::shown).toList());
        t(p.startsWith(KindDesk.RULES + "\n\nBUILDS:\n[{\"id\":\"A\",\"net_blocks\":2868,") && p.contains("{\"id\":\"V\",") && !p.contains("\"id\":\"W\""), "the production prompt");
    }
}
