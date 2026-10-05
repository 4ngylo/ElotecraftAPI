package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.util.Durations;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Typed view of config.yml, rebuilt on every reload. A bad value is logged with its path and replaced
 * by the built-in default, so a typo never stops the plugin.
 */
public record Settings(int countdownSeconds, Duration maxDuration, int endDelaySeconds, boolean bossBar,
                BossBar.Color bossBarColor, boolean logResults, Duration requestExpiry, Duration requestCooldown,
                Duration rematchWindow, boolean hunger, boolean naturalRegeneration, Set<String> allowedCommands,
                Reward winReward, Reward lossReward, Title.Times titleTimes, Effects effects,
                boolean breakArenaBlocks, int regenBlocksPerTick, Ranked ranked) {

    private static final long MILLIS_PER_TICK = 50;
    private static final int MAX_TITLE_TICKS = 200;
    private static final int MAX_ELO_RANGE = 5000;

    /** Elo rating of queue duels and how far apart two queued players may be rated. */
    public record Ranked(int kFactor, int range, int rangeGrowth, int rangeMax) {

        /** The rating gap allowed for a player who has waited {@code seconds}. */
        public int range(long seconds) {
            return (int) Math.min(range + rangeGrowth * seconds, rangeMax);
        }
    }

    /** Money and console commands for one outcome of a duel. */
    public record Reward(double money, List<String> commands) {

        public Reward {
            commands = List.copyOf(commands);
        }
    }

    public static Settings load(ConfigurationSection config, Logger logger) {
        return new Settings(
                integer(config, logger, "match.countdown-seconds", 5, 1, 30),
                duration(config, logger, "match.max-duration", Duration.ofMinutes(5), Duration.ofSeconds(10)),
                integer(config, logger, "match.end-delay-seconds", 4, 0, 30),
                config.getBoolean("match.boss-bar", true),
                bossBarColor(config, logger),
                config.getBoolean("match.log-results", true),
                duration(config, logger, "requests.expiry", Duration.ofSeconds(30), Duration.ofSeconds(5)),
                duration(config, logger, "requests.cooldown", Duration.ofSeconds(5), Duration.ZERO),
                duration(config, logger, "requests.rematch-window", Duration.ofSeconds(30), Duration.ofSeconds(5)),
                config.getBoolean("rules.hunger", false),
                config.getBoolean("rules.natural-regeneration", true),
                config.getStringList("rules.allowed-commands").stream()
                        .map(label -> label.strip().toLowerCase(Locale.ROOT).replaceFirst("^/", ""))
                        .filter(label -> !label.isEmpty())
                        .collect(Collectors.toUnmodifiableSet()),
                reward(config, logger, "rewards.win"),
                reward(config, logger, "rewards.loss"),
                Title.Times.times(
                        ticks(integer(config, logger, "titles.fade-in", 5, 0, MAX_TITLE_TICKS)),
                        ticks(integer(config, logger, "titles.stay", 30, 0, MAX_TITLE_TICKS)),
                        ticks(integer(config, logger, "titles.fade-out", 10, 0, MAX_TITLE_TICKS))),
                Effects.load(config.getConfigurationSection("effects"), logger),
                config.getBoolean("build.break-arena-blocks", false),
                integer(config, logger, "regen.blocks-per-tick", 2000, 1, 100_000),
                new Ranked(
                        integer(config, logger, "ranked.k-factor", 32, 1, 100),
                        integer(config, logger, "ranked.range", 100, 0, MAX_ELO_RANGE),
                        integer(config, logger, "ranked.range-growth", 10, 0, 1000),
                        integer(config, logger, "ranked.range-max", 1000, 0, MAX_ELO_RANGE)));
    }

    private static int integer(ConfigurationSection config, Logger logger, String path, int fallback, int min, int max) {
        int value = config.getInt(path, fallback);
        if (!config.isInt(path) || value < min || value > max) {
            logger.warning("config.yml " + path + " must be a whole number from " + min + " to " + max + "; using " + fallback);
            return fallback;
        }
        return value;
    }

    private static Duration duration(ConfigurationSection config, Logger logger, String path, Duration fallback, Duration min) {
        String raw = config.getString(path, "");
        try {
            Duration value = Durations.parse(raw);
            if (value.compareTo(min) >= 0) {
                return value;
            }
        } catch (IllegalArgumentException e) {
            // Logged below with the same message as a too-short value.
        }
        logger.warning("config.yml " + path + " '" + raw + "' must be a duration of at least "
                + Durations.format(min) + " (e.g. 30s, 5m); using " + Durations.format(fallback));
        return fallback;
    }

    private static BossBar.Color bossBarColor(ConfigurationSection config, Logger logger) {
        String raw = config.getString("match.boss-bar-color", "RED");
        try {
            return BossBar.Color.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warning("config.yml match.boss-bar-color '" + raw + "' is not a boss bar color; using RED");
            return BossBar.Color.RED;
        }
    }

    private static Reward reward(ConfigurationSection config, Logger logger, String path) {
        double money = config.getDouble(path + ".money", 0);
        if (!Double.isFinite(money) || money < 0) {
            logger.warning("config.yml " + path + ".money must be 0 or more; using 0");
            money = 0;
        }
        return new Reward(money, config.getStringList(path + ".commands"));
    }

    private static Duration ticks(int ticks) {
        return Duration.ofMillis(ticks * MILLIS_PER_TICK);
    }
}
