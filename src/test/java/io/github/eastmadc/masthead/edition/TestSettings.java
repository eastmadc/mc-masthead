package io.github.eastmadc.masthead.edition;

import io.github.eastmadc.masthead.config.Settings;

import java.util.List;

/** Settings for a chicken-theme paper with an owner (the classic preset the golden tools were built against). */
final class TestSettings {
    private TestSettings() {}

    static final List<String> OWNER_LORE = List.of(
            "The Torch Trail: TownFounder lit every cave under spawn with one long torch trail and walked out with a stack of diamonds. Nobody needs x-ray with that many torches.",
            "TownFounder once flooded the pumpkin patch at spawn while digging a canal, and spent the evening bailing it out with buckets.",
            "The first lighthouse on the sand bar is his, and he repaints it every time someone brings it up.",
            "His parrot has outlived three of his horses.");

    static Settings coop() {
        return with(new Settings.Tone("spicy", "pg13", "normal", List.of("obituaries", "sports", "weather", "horoscopes", "animal-watch")));
    }

    static Settings with(Settings.Tone tone) {
        Settings d = Settings.defaults();
        return new Settings(
                new Settings.Paper("The Daily Cluck", "Henhouse", "Hot off the press before the rooster wakes up.",
                        "a small-town paper for a town that takes its chickens far too seriously", "friends who play on this server",
                        "🥚 Egg of the Day", "🐔 Flapping Fowl Award", "chicken"),
                tone, new Settings.Owner("TownFounder", "From the Coop Office", OWNER_LORE, true, "he"), d.privacy(), d.schedule(), d.writer(), d.web(),
                d.features(), d.alerts());
    }

    /** A different server: no owner, sheep watch, never-mention list. */
    static Settings other() {
        Settings d = Settings.defaults();
        return new Settings(new Settings.Paper("The Daily Block", "our Minecraft server", "", "a small-town paper for a blocky town",
                "friends aged 15+", "⭐ Star of the Day", "💥 Oops of the Day", "sheep"),
                new Settings.Tone("spicy", "pg13", "normal", List.of("obituaries", "sports", "weather", "horoscopes", "animal-watch")), new Settings.Owner("", "The Owner's Desk", List.of(), true),
                new Settings.Privacy(List.of(), List.of("anyone's real name")), d.schedule(), d.writer(), d.web(), d.features(), d.alerts());
    }
}
