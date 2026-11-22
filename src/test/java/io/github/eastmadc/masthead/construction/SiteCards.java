package io.github.eastmadc.masthead.construction;

import io.github.eastmadc.masthead.config.Settings;
import io.github.eastmadc.masthead.config.SettingsLoader;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * For operators (GoldenTool site-cards): the kinds desk's practice fold on a COPY of a server's construction folder, so the
 * exact cards and prompt the plugin would send for DATE can be read before trusting an answer. The folder itself is never
 * changed. Differences from the plugin: block tags are guessed from key names (crops, slabs ...), and the slime chunk test
 * runs only for worlds given as UUID=SEED.
 */
public final class SiteCards {
    private SiteCards() {}

    public record Out(String prompt, List<String> lines) {}

    public static Out run(Path dir, LocalDate date, Map<String, Long> seeds) throws Exception {
        Path copy = Files.createTempDirectory("site-cards").resolve("construction");
        try (Stream<Path> all = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) all::iterator) {
                Path to = copy.resolve(dir.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to); else Files.copy(p, to);
            }
        }
        Path cfgFile = dir.toAbsolutePath().getParent().resolve("config.yml");
        Settings s = Files.isRegularFile(cfgFile) ? SettingsLoader.load(YamlConfiguration.loadConfiguration(cfgFile.toFile()), m -> { }, null) : Settings.defaults();
        Map<String, Integer> keyInfo = new HashMap<>();
        for (String name : List.of("sites.json", "sites.prev.json")) {
            Path f = copy.resolve(name);
            if (Files.isRegularFile(f)) for (SiteIndex.Site x : SiteIndex.read(Files.readString(f)).sites.values())
                for (String k : x.mats.keySet()) keyInfo.computeIfAbsent(k, kk -> KeyRules.info(kk, ConstructionTest.tags(kk)));
        }
        Set<String> hidden = Construction.lower(s.privacy().hidePlayers());
        ConstructionIO io = new ConstructionIO(copy, Logger.getAnonymousLogger());
        List<KindDesk.Card> cards = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        io.analyze(new ConstructionIO.Job(date, false, s.construction(), hidden, keyInfo, date, new AtomicInteger(ConstructionIO.RUNNING), seeds, Map.of(), cards), lines);
        io.exec.shutdownNow();
        for (int i = 0; i < cards.size(); i++) lines.add(KindDesk.id(i) + " = build " + cards.get(i).site());
        try (Stream<Path> all = Files.walk(copy.getParent())) { all.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        return new Out(KindDesk.prompt(cards.stream().map(KindDesk.Card::shown).toList()), lines);
    }

    /** A world given as UUID=SEED (never printed). */
    public static Map<String, Long> seeds(String[] args, int from) {
        Map<String, Long> out = new HashMap<>();
        for (int i = from; i < args.length; i++) {
            String[] kv = args[i].split("=", 2);
            out.put(kv[0], Long.parseLong(kv[1]));
        }
        return out;
    }

}
