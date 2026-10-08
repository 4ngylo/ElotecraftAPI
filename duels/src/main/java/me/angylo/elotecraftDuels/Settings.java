package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.util.Durations;
import me.angylo.elotecraftDuels.event.HostedEvent;
import me.angylo.elotecraftDuels.stats.Divisions;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Typed view of config.yml, rebuilt on every reload. A bad value is logged with its path and replaced
 * by the built-in default, so a typo never stops the plugin.
 */
public record Settings(int countdownSeconds, Duration maxDuration, int endDelaySeconds, int roundDelaySeconds, boolean bossBar,
                BossBar.Color bossBarColor, boolean logResults, boolean arrowHealth, Duration requestExpiry, Duration requestCooldown,
                Duration rematchWindow, boolean hunger, boolean naturalRegeneration, Set<String> allowedCommands,
                Reward winReward, Reward lossReward, Title.Times titleTimes, Effects effects,
                boolean breakArenaBlocks, int regenBlocksPerTick, boolean voidEliminates, String arenasWorld,
                int pregenSpacing, int maxCopies, int partyMaxSize, Duration partyInviteExpiry, boolean partyFriendlyFire, Duration kitEditorTimeout, Ranked ranked,
                Sidebars sidebars, int hologramLines, LobbyItems lobbyItems, Events events, Cosmetics cosmetics, Bets bets,
                CustomKitOptions customKits, Modes modes) {

    private static final long MILLIS_PER_TICK = 50;
    private static final int MAX_TITLE_TICKS = 200;
    private static final int MAX_ELO_RANGE = 5000;
    private static final int MAX_HOLOGRAM_LINES = 50;
    private static final int MAX_DAILY_RANKED = 1000;
    private static final int MAX_EVENT_PLAYERS = 100;
    private static final int MAX_TOURNAMENT_REPLAYS = 10;
    private static final int MAX_CUSTOM_KITS = 9;
    private static final int MAX_MODE_RADIUS = 16;
    private static final double MAX_BORDER_DAMAGE = 20;
    private static final int MINUTES_PER_DAY = 24 * 60;
    private static final double MIN_BET = 0.01;
    private static final double MAX_BET = 1_000_000_000;
    private static final double DEFAULT_MIN_BET = 10;
    private static final double DEFAULT_MAX_BET = 100_000;
    private static final double MAX_PERCENT = 100;
    private static final String DEFAULT_ARENAS_WORLD = "duels_arenas";
    private static final Pattern WORLD_NAME = Pattern.compile("[a-z0-9_-]{1,64}");

    /** Elo rating of queue duels and how far apart two queued players may be rated. */
    /** @param divisions rating bands shown with ratings; {@link Divisions#NONE} when off */
    /** @param dailyLimit ranked duels a player may start a day; 0 for no limit */
    public record Ranked(int kFactor, int range, int rangeGrowth, int rangeMax, Divisions divisions, int dailyLimit) {

        /** The rating gap allowed for a player who has waited {@code seconds}. */
        public int range(long seconds) {
            return (int) Math.min(range + rangeGrowth * seconds, rangeMax);
        }
    }

    /**
     * Which sidebars duels shows: one during fights, one with stats elsewhere, in {@code lobbyWorlds}
     * (empty: every world but the arenas world). With the fight one, {@code healthBelowName} shows health under names.
     */
    public record Sidebars(boolean match, boolean lobby, Set<String> lobbyWorlds, boolean healthBelowName) {

        public Sidebars {
            lobbyWorlds = Set.copyOf(lobbyWorlds);
        }
    }

    /** The hotbar items of the lobby (menus.yml {@code lobby-items}), in {@code worlds}; for practice servers. */
    public record LobbyItems(boolean enabled, Set<String> worlds) {

        public LobbyItems {
            worlds = Set.copyOf(worlds);
        }
    }

    /** Whether {@code world} is a lobby for a feature limited to {@code worlds}: those, or with none every world but the arenas one. */
    public boolean isLobby(String world, Set<String> worlds) {
        return worlds.isEmpty() ? !world.equals(arenasWorld) : worlds.contains(world);
    }

    /**
     * Player-hosted events: how many may join, how long they wait, how often the event is announced, the
     * prize of each winner and the border the host may turn on.
     */
    public record Events(int minPlayers, int maxPlayers, Duration waitTime, Duration announceInterval,
                         Duration hostCooldown, boolean broadcastResult, Reward reward, Border border, List<Scheduled> schedule,
                         int tournamentReplays) {

        public Events {
            schedule = List.copyOf(schedule);
        }
    }

    /**
     * The bridge kit mode: a fighter scores within {@code goalRadius} blocks (across) of the other side's goal point, and
     * nobody places blocks within {@code protectRadius} blocks of a spawn or goal, so they cannot be walled off.
     */
    public record Modes(int goalRadius, int protectRadius) {
    }

    /**
     * Kits players build themselves ({@code /duel customkit}) from the items of {@code baseKit}, which also gives them
     * its rules, arenas and permission; empty when off. Each player keeps {@code slots} of them, named in duels by
     * {@code displayName} with {@code <player>} and {@code <slot>}.
     */
    public record CustomKitOptions(String baseKit, int slots, String displayName) {

        public boolean enabled() {
            return !baseKit.isEmpty();
        }
    }

    /**
     * Money bets on duels ({@code /duel <player> <kit> bet <amount>}): the stake each player puts in, from {@code min}
     * to {@code max}, and the {@code tax} percent of the pot the server keeps.
     */
    public record Bets(boolean enabled, double min, double max, double tax) {
    }

    /** An event the server hosts every day at {@code at} (server time, to the minute). */
    public record Scheduled(LocalTime at, String kit, HostedEvent.Mode mode) {
    }

    /**
     * The border of an event: it starts around the arena, waits {@code delay} into the fight, then closes
     * in to {@code minSize} blocks across over {@code shrinkTime}; outside it fighters lose {@code damage}
     * health a second.
     */
    public record Border(Duration delay, Duration shrinkTime, int minSize, double damage) {
    }

    /** Money and console commands for one outcome of a duel. */
    public record Reward(double money, List<String> commands) {

        public static final Reward NONE = new Reward(0, List.of());

        public Reward {
            commands = List.copyOf(commands);
        }

        /** Both rewards: the money added up, then this one's commands and {@code other}'s. */
        public Reward plus(Reward other) {
            return new Reward(money + other.money, Stream.concat(commands.stream(), other.commands.stream()).toList());
        }

        /**
         * Reads {@code money} and {@code commands} under {@code path}; money below 0 becomes 0 with a warning.
         *
         * @param file the file {@code config} is from, named in the warning
         */
        public static Reward load(ConfigurationSection config, Logger logger, String file, String path) {
            double money = config.getDouble(path + ".money", 0);
            if (!Double.isFinite(money) || money < 0) {
                String fullPath = config.getCurrentPath() == null || config.getCurrentPath().isEmpty()
                        ? path : config.getCurrentPath() + "." + path;
                logger.warning(file + " " + fullPath + ".money must be 0 or more; using 0");
                money = 0;
            }
            return new Reward(money, config.getStringList(path + ".commands"));
        }
    }

    public static Settings load(ConfigurationSection config, Logger logger) {
        return new Settings(
                integer(config, logger, "match.countdown-seconds", 5, 1, 30),
                duration(config, logger, "match.max-duration", Duration.ofMinutes(5), Duration.ofSeconds(10)),
                integer(config, logger, "match.end-delay-seconds", 4, 0, 30),
                integer(config, logger, "match.round-delay-seconds", 3, 1, 30),
                config.getBoolean("match.boss-bar", true),
                bossBarColor(config, logger),
                config.getBoolean("match.log-results", true),
                config.getBoolean("match.arrow-health", true),
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
                config.getBoolean("rules.void-eliminates", true),
                worldName(config, logger),
                integer(config, logger, "arenas.pregen-spacing", 64, 16, 1024),
                integer(config, logger, "arenas.max-copies", 32, 1, 256),
                integer(config, logger, "parties.max-size", 8, 2, 100),
                duration(config, logger, "parties.invite-expiry", Duration.ofSeconds(60), Duration.ofSeconds(5)),
                config.getBoolean("parties.friendly-fire", false),
                duration(config, logger, "kit-editor.timeout", Duration.ofMinutes(5), Duration.ofSeconds(30)),
                new Ranked(
                        integer(config, logger, "ranked.k-factor", 32, 1, 100),
                        integer(config, logger, "ranked.range", 100, 0, MAX_ELO_RANGE),
                        integer(config, logger, "ranked.range-growth", 10, 0, 1000),
                        integer(config, logger, "ranked.range-max", 1000, 0, MAX_ELO_RANGE),
                        divisions(config, logger),
                        integer(config, logger, "ranked.daily-limit", 0, 0, MAX_DAILY_RANKED)),
                new Sidebars(
                        config.getBoolean("sidebar.match", true),
                        config.getBoolean("sidebar.lobby", false),
                        Set.copyOf(config.getStringList("sidebar.lobby-worlds")),
                        config.getBoolean("sidebar.health-below-name", true)),
                integer(config, logger, "holograms.lines", 10, 1, MAX_HOLOGRAM_LINES),
                new LobbyItems(config.getBoolean("lobby-items.enabled", false),
                        Set.copyOf(config.getStringList("lobby-items.worlds"))),
                events(config, logger),
                Cosmetics.load(config, logger),
                bets(config, logger),
                new CustomKitOptions(config.getString("custom-kits.base-kit", "").strip().toLowerCase(Locale.ROOT),
                        integer(config, logger, "custom-kits.slots", 3, 1, MAX_CUSTOM_KITS),
                        config.getString("custom-kits.display-name", "<yellow><player>'s custom kit <slot>")),
                new Modes(integer(config, logger, "modes.bridge.goal-radius", 2, 0, MAX_MODE_RADIUS),
                        integer(config, logger, "modes.bridge.protect-radius", 3, 0, MAX_MODE_RADIUS)));
    }

    private static Events events(ConfigurationSection config, Logger logger) {
        int min = integer(config, logger, "events.min-players", 2, 2, MAX_EVENT_PLAYERS);
        int max = integer(config, logger, "events.max-players", 16, 2, MAX_EVENT_PLAYERS);
        if (max < min) {
            logger.warning("config.yml events.max-players must be at least events.min-players; using " + min);
            max = min;
        }
        double damage = config.getDouble("events.border.damage", 1);
        if (!Double.isFinite(damage) || damage < 0 || damage > MAX_BORDER_DAMAGE) {
            logger.warning("config.yml events.border.damage must be from 0 to " + MAX_BORDER_DAMAGE + "; using 1");
            damage = 1;
        }
        return new Events(min, max,
                duration(config, logger, "events.wait-time", Duration.ofMinutes(2), Duration.ofSeconds(10)),
                duration(config, logger, "events.announce-interval", Duration.ofSeconds(30), Duration.ofSeconds(5)),
                duration(config, logger, "events.host-cooldown", Duration.ofMinutes(5), Duration.ZERO),
                config.getBoolean("events.broadcast-result", true),
                reward(config, logger, "events.reward"),
                new Border(
                        duration(config, logger, "events.border.delay", Duration.ofSeconds(60), Duration.ZERO),
                        duration(config, logger, "events.border.shrink-time", Duration.ofMinutes(2), Duration.ofSeconds(1)),
                        integer(config, logger, "events.border.min-size", 10, 1, 1000),
                        damage),
                schedule(config, logger),
                integer(config, logger, "events.tournament-replays", 1, 0, MAX_TOURNAMENT_REPLAYS));
    }

    /** {@code events.schedule}: entries without a valid time, kit or mode are logged and left out. */
    private static List<Scheduled> schedule(ConfigurationSection config, Logger logger) {
        List<Scheduled> schedule = new ArrayList<>();
        for (Map<?, ?> entry : config.getMapList("events.schedule")) {
            try {
                if (!(entry.get("kit") instanceof String kit)) {
                    throw new IllegalArgumentException();
                }
                // YAML reads an unquoted 20:00 as the number 1200 (minutes, base 60).
                LocalTime at = switch (entry.get("at")) {
                    case String text -> LocalTime.parse(text).truncatedTo(ChronoUnit.MINUTES);
                    case Integer minutes when minutes >= 0 && minutes < MINUTES_PER_DAY -> LocalTime.of(minutes / 60, minutes % 60);
                    case null, default -> throw new IllegalArgumentException();
                };
                Object mode = entry.get("mode");
                schedule.add(new Scheduled(at, kit,
                        mode == null ? HostedEvent.Mode.FFA : HostedEvent.Mode.valueOf(mode.toString().toUpperCase(Locale.ROOT))));
            } catch (DateTimeParseException | IllegalArgumentException e) {
                logger.warning("config.yml events.schedule: " + entry + " needs at: \"HH:mm\", a kit and a mode (ffa, teams,"
                        + " tournament or sumo); left out");
            }
        }
        return schedule;
    }

    /** {@code ranked.divisions}: entries without a name or a whole-number {@code min} are logged and left out. */
    private static Divisions divisions(ConfigurationSection config, Logger logger) {
        List<Divisions.Division> divisions = new ArrayList<>();
        for (Map<?, ?> entry : config.getMapList("ranked.divisions")) {
            if (entry.get("name") instanceof String name && !name.isBlank() && entry.get("min") instanceof Integer min) {
                divisions.add(new Divisions.Division(name, min, seasonReward(entry, logger)));
            } else {
                logger.warning("config.yml ranked.divisions: " + entry + " needs a name and a whole-number min; left out");
            }
        }
        return new Divisions(divisions);
    }

    /** A division's {@code season-reward}: money and commands like {@code rewards.win}; none if unset. */
    private static Reward seasonReward(Map<?, ?> division, Logger logger) {
        if (!(division.get("season-reward") instanceof Map<?, ?> raw)) {
            return Reward.NONE;
        }
        ConfigurationSection holder = new MemoryConfiguration();
        holder.createSection("season-reward", raw);
        return Reward.load(holder, logger, "config.yml ranked.divisions " + division.get("name"), "season-reward");
    }

    private static Bets bets(ConfigurationSection config, Logger logger) {
        double min = number(config, logger, "bets.min", DEFAULT_MIN_BET, MIN_BET, MAX_BET);
        double max = number(config, logger, "bets.max", DEFAULT_MAX_BET, MIN_BET, MAX_BET);
        if (max < min) {
            logger.warning("config.yml bets.max must be at least bets.min; using " + min);
            max = min;
        }
        return new Bets(config.getBoolean("bets.enabled", true), min, max, number(config, logger, "bets.tax", 0, 0, MAX_PERCENT));
    }

    private static double number(ConfigurationSection config, Logger logger, String path, double fallback, double min, double max) {
        double value = config.getDouble(path, fallback);
        if (!config.isDouble(path) && !config.isInt(path) || !Double.isFinite(value) || value < min || value > max) {
            logger.warning("config.yml " + path + " must be a number from " + min + " to " + max + "; using " + fallback);
            return fallback;
        }
        return value;
    }

    private static int integer(ConfigurationSection config, Logger logger, String path, int fallback, int min, int max) {
        int value = config.getInt(path, fallback);
        if (!config.isInt(path) || value < min || value > max) {
            logger.warning("config.yml " + path + " must be a whole number from " + min + " to " + max + "; using " + fallback);
            return fallback;
        }
        return value;
    }

    private static String worldName(ConfigurationSection config, Logger logger) {
        String raw = config.getString("arenas.world", DEFAULT_ARENAS_WORLD).strip();
        if (!WORLD_NAME.matcher(raw).matches()) {
            logger.warning("config.yml arenas.world '" + raw + "' must be 1 to 64 lowercase letters, digits, - or _; using "
                    + DEFAULT_ARENAS_WORLD);
            return DEFAULT_ARENAS_WORLD;
        }
        return raw;
    }

    private static Duration duration(ConfigurationSection config, Logger logger, String path, Duration fallback, Duration min) {
        // Without a default argument, so a key missing from an older config.yml falls back to the bundled one.
        String raw = Objects.requireNonNullElse(config.getString(path), "");
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
        return Reward.load(config, logger, "config.yml", path);
    }

    private static Duration ticks(int ticks) {
        return Duration.ofMillis(ticks * MILLIS_PER_TICK);
    }
}
