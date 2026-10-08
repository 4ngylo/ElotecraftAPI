package me.angylo.elotecraftDuels.stats;

import me.angylo.elotecraftAPI.util.Durations;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.Settings.Reward;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import me.angylo.elotecraftDuels.match.Rewards;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Ends seasons: by {@code /duels season end confirm}, or by itself at the planned end while auto end is on. Archives
 * and resets ratings ({@link Seasons#end}), pays each player's division reward and tells everyone. Before a planned
 * end it warns admins ({@code seasons.admin-warnings}) and, when the season will end by itself, everyone
 * ({@code seasons.player-warnings}); once a planned end has passed without auto end, admins are told when they join.
 */
public final class SeasonEnder implements Listener {

    /** Who sees season warnings and may end a season. */
    public static final String PERMISSION = "duels.admin.season";
    /** How long a failed end waits before auto end tries again. */
    private static final Duration RETRY = Duration.ofMinutes(5);
    /** How long past the planned end auto end waits for ranked duels to finish, so they count in their own season. */
    private static final Duration RANKED_GRACE = Duration.ofMinutes(10);

    /**
     * What ending the season now would do.
     *
     * @param ratings   kit ratings that would be archived
     * @param divisions players per division, in the divisions' order; players below every division are left out
     * @param rewards   players who would be paid a division reward
     */
    public record Preview(int ratings, int players, Map<Divisions.Division, Integer> divisions, int rewards) {
    }

    private final Plugin plugin;
    private final Messages messages;
    private final Supplier<Settings> settings;
    private final Seasons seasons;
    private final StatsService stats;
    private final KitRegistry kits;
    private final Rewards rewards;
    private final BooleanSupplier rankedRunning;
    /** Warnings already sent, by season, planned end and how long before it, so each goes out once. */
    private final Set<String> warned = new HashSet<>();
    private boolean ending;
    /** When auto end may try again after a failed end. */
    private long retryAt;

    /** @param rankedRunning whether a ranked duel is being fought, which auto end waits for (up to {@link #RANKED_GRACE}) */
    public SeasonEnder(Plugin plugin, Messages messages, Supplier<Settings> settings, Seasons seasons, StatsService stats,
                       KitRegistry kits, Rewards rewards, BooleanSupplier rankedRunning) {
        this.plugin = plugin;
        this.messages = messages;
        this.settings = settings;
        this.seasons = seasons;
        this.stats = stats;
        this.kits = kits;
        this.rewards = rewards;
        this.rankedRunning = rankedRunning;
    }

    public boolean isEnding() {
        return ending;
    }

    /**
     * Ends the season running and tells {@code sender} how it went; does nothing (but say so) while one is ending.
     * Cannot be undone. Main thread only.
     */
    public void end(CommandSender sender) {
        if (ending) {
            messages.send(sender, "admin.season.busy");
            return;
        }
        ending = true;
        int running = seasons.current();
        messages.send(sender, "admin.season.ending", season(running));
        seasons.end(kits.names()).whenComplete((ended, error) -> {
            ending = false;
            if (error != null) {
                retryAt = System.currentTimeMillis() + RETRY.toMillis();
                plugin.getLogger().log(Level.SEVERE, "Could not end the duel season; nothing was changed", error);
                messages.send(sender, "admin.season.failed");
                return;
            }
            stats.seasonReset();
            int paid = payRewards(ended);
            plugin.getLogger().info("Ended duel season " + ended.season() + " (by " + sender.getName() + "): archived "
                    + ended.ratings() + " ratings, paid " + paid + " season rewards");
            for (Player online : Bukkit.getOnlinePlayers()) {
                messages.send(online, "season.ended", season(ended.season()),
                        Placeholder.unparsed("next", String.valueOf(ended.season() + 1)));
            }
            messages.send(sender, "admin.season.ended", season(ended.season()),
                    Placeholder.unparsed("ratings", String.valueOf(ended.ratings())), Placeholder.unparsed("rewards", String.valueOf(paid)));
        });
    }

    /** What ending the season now would archive and pay; completes on the main thread. */
    public CompletableFuture<Preview> preview() {
        Divisions divisions = settings.get().ranked().divisions();
        return seasons.ratings(seasons.current()).thenCombine(seasons.standings(kits.names()), (ratings, standings) -> {
            Map<Divisions.Division, Integer> counts = new LinkedHashMap<>();
            divisions.list().forEach(division -> counts.put(division, 0));
            int paid = 0;
            for (Seasons.Standing standing : standings) {
                Optional<Divisions.Division> division = divisions.of(standing.elo());
                if (division.isEmpty()) {
                    continue;
                }
                counts.merge(division.get(), 1, Integer::sum);
                if (!division.get().seasonReward().equals(Reward.NONE)) {
                    paid++;
                }
            }
            return new Preview(ratings.size(), standings.size(), counts, paid);
        });
    }

    /** Sends due warnings and ends a season whose planned end has passed while auto end is on; call every second. */
    public void tick() {
        CompletableFuture<Integer> ready = seasons.ready();
        Seasons.Info info = seasons.info();
        if (!ready.isDone() || ready.isCompletedExceptionally() || ending || !info.planned()) {
            return;
        }
        long now = System.currentTimeMillis();
        long left = info.endsAt() - now;
        if (left <= 0) {
            if (info.autoEnd() && now < retryAt) {
                return;
            }
            if (info.autoEnd() && rankedRunning.getAsBoolean() && -left < RANKED_GRACE.toMillis()) {
                if (warned.add(info.season() + ":" + info.endsAt() + ":waiting")) {
                    plugin.getLogger().info("Duel season " + info.season() + " reached its planned end; ending it once ranked duels finish");
                }
                return;
            }
            if (info.autoEnd()) {
                plugin.getLogger().info("Duel season " + info.season() + " reached its planned end; ending it");
                end(Bukkit.getConsoleSender());
            } else if (warned.add(info.season() + ":" + info.endsAt() + ":over")) {
                plugin.getLogger().warning("Duel season " + info.season() + " is past its planned end; /duels season end confirm ends it");
            }
            return;
        }
        Settings.SeasonOptions options = settings.get().seasons();
        if (due(info, options.adminWarnings(), left, "admin")) {
            TagResolver[] tags = tags(info, left);
            plugin.getLogger().info("Duel season " + info.season() + " ends in " + length(Duration.ofMillis(left)));
            Bukkit.getOnlinePlayers().stream().filter(player -> player.hasPermission(PERMISSION))
                    .forEach(player -> messages.send(player, info.autoEnd() ? "admin.season.warning-auto" : "admin.season.warning", tags));
        }
        if (info.autoEnd() && due(info, options.playerWarnings(), left, "player")) {
            TagResolver[] tags = tags(info, left);
            Bukkit.getOnlinePlayers().forEach(player -> messages.send(player, "season.ending-soon", tags));
        }
    }

    /** Tells admins who join that the season is past its planned end and does not end by itself. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Seasons.Info info = seasons.info();
        if (info.planned() && !info.autoEnd() && info.endsAt() <= System.currentTimeMillis() && event.getPlayer().hasPermission(PERMISSION)) {
            messages.send(event.getPlayer(), "admin.season.overdue", season(info.season()));
        }
    }

    /**
     * Whether a warning of {@code warnings} is due with {@code left} millis to go: the shortest one passed that was not
     * sent yet. The longer ones passed with it are marked sent, so a server started late sends one warning, not all.
     */
    private boolean due(Seasons.Info info, List<Duration> warnings, long left, String audience) {
        Duration shortest = null;
        for (Duration warning : warnings) {
            if (left <= warning.toMillis()) {
                shortest = warning;
            }
        }
        if (shortest == null) {
            return false;
        }
        boolean sent = false;
        for (Duration warning : warnings) {
            if (left <= warning.toMillis() && warned.add(info.season() + ":" + info.endsAt() + ":" + audience + ":" + warning)) {
                sent |= warning.equals(shortest);
            }
        }
        return sent;
    }

    /** Each player's division reward, by the overall rating they ended the season with; returns how many were paid. */
    private int payRewards(Seasons.Ended ended) {
        Divisions divisions = settings.get().ranked().divisions();
        int paid = 0;
        for (Seasons.Standing standing : ended.standings()) {
            Optional<Divisions.Division> division = divisions.of(standing.elo());
            if (division.isEmpty() || division.get().seasonReward().equals(Reward.NONE)) {
                continue;
            }
            rewards.giveSeason(Bukkit.getOfflinePlayer(standing.player()), standing.name(), division.get().seasonReward(),
                    Map.of("<player>", standing.name(), "<division>", Text.plain(Text.mm(division.get().name())),
                            "<elo>", String.valueOf(standing.elo()), "<season>", String.valueOf(ended.season())));
            paid++;
        }
        return paid;
    }

    private static TagResolver[] tags(Seasons.Info info, long left) {
        return new TagResolver[]{season(info.season()), Placeholder.unparsed("time", length(Duration.ofMillis(left)))};
    }

    /** A season-sized length in its two largest units: 34d 3h, 5h 12m, or every unit under an hour. */
    public static String length(Duration duration) {
        if (duration.toDays() > 0) {
            return Durations.format(Duration.ofHours(duration.toHours()));
        }
        if (duration.toHours() > 0) {
            return Durations.format(Duration.ofMinutes(duration.toMinutes()));
        }
        return Durations.format(duration);
    }

    private static TagResolver season(int number) {
        return Placeholder.unparsed("season", String.valueOf(number));
    }

    /** A day as {@code yyyy-MM-dd}, in the server's time zone. */
    public static String date(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /** Whole days season {@code info} has run. */
    public static long days(Seasons.Info info) {
        return Duration.ofMillis(Math.max(0, System.currentTimeMillis() - info.startedAt())).toDays();
    }

    /** How long until the planned end of {@code info}, or empty without one or once it passed. */
    public static Optional<Duration> left(Seasons.Info info) {
        long left = info.endsAt() - System.currentTimeMillis();
        return info.planned() && left > 0 ? Optional.of(Duration.ofMillis(left)) : Optional.empty();
    }

    /** The season's name, or "Season n" (messages.yml {@code season.unnamed}) without one. */
    public static Component name(Messages messages, CommandSender viewer, int season, String name) {
        return name.isEmpty() ? messages.get(viewer, "season.unnamed", season(season)) : Text.mm(name);
    }
}
