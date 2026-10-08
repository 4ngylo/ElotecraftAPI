package me.angylo.elotecraftDuels.hud;

import me.angylo.elotecraftAPI.hud.Sidebar;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.PlayerOptions;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.match.MatchManager;
import me.angylo.elotecraftDuels.match.QueueManager;
import me.angylo.elotecraftDuels.party.Party;
import me.angylo.elotecraftDuels.party.PartyManager;
import me.angylo.elotecraftDuels.stats.PlayerStats;
import me.angylo.elotecraftDuels.stats.StatsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * The optional sidebar (config.yml {@code sidebar}): the fight for fighters and spectators, stats in the
 * lobby. Refreshed once a second from the cached stats, so it never waits for the database; the leaderboard
 * rank is read every minute. Only hides sidebars it showed, and leaves another plugin's sidebar alone.
 * Players who turned it off in {@code /duel options} get none, and so no health under names in fights either.
 * Main thread only.
 */
public final class DuelsSidebar implements Listener {

    // ponytail: rank only within the top 100 by name; a per-player COUNT query if ranks past 100 matter
    private static final int RANK_LIMIT = 100;
    private static final int RANK_REFRESH_SECONDS = 60;
    private static final int SECONDS_PER_MINUTE = 60;

    private final Plugin plugin;
    private final Messages messages;
    private final Supplier<Settings> settings;
    private final StatsService stats;
    private final KitRegistry kits;
    private final MatchManager matches;
    private final QueueManager queues;
    private final PartyManager parties;
    private final Map<UUID, Sidebar> shown = new HashMap<>();
    /** Lowercase name to leaderboard position, from the last rank read. */
    private Map<String, Integer> ranks = Map.of();
    private boolean readingRanks;
    private boolean warnedTooLong;
    private int seconds;

    /** A sidebar's title and lines, ready to show. */
    public record Layout(Component title, List<Component> lines) {
    }

    public DuelsSidebar(Plugin plugin, Messages messages, Supplier<Settings> settings, StatsService stats, KitRegistry kits,
                        MatchManager matches, QueueManager queues, PartyManager parties) {
        this.plugin = plugin;
        this.messages = messages;
        this.settings = settings;
        this.stats = stats;
        this.kits = kits;
        this.matches = matches;
        this.queues = queues;
        this.parties = parties;
    }

    /** Shows, refreshes or hides every online player's sidebar; once a second. */
    public void tick() {
        Settings.Sidebars config = settings.get().sidebars();
        if (config.lobby() && seconds++ % RANK_REFRESH_SECONDS == 0) {
            refreshRanks();
        }
        shown.keySet().removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            boolean inMatch = matches.matchOf(player).isPresent();
            if (inMatch && config.match() && config.healthBelowName()) {
                // Changes without an event: healing at a round's start, absorption, other plugins.
                Sidebar.updateHealth(player);
            }
            if (PlayerOptions.SIDEBAR.isOn(player) && (inMatch ? config.match() : config.lobby() && inLobby(player, config))) {
                show(player, layoutFor(player), inMatch && config.healthBelowName());
            } else {
                hide(player);
            }
        }
    }

    /** The match layout while {@code player} fights or spectates, else the lobby one; whatever the config says. */
    public Layout layoutFor(Player player) {
        Optional<Match> match = matches.matchOf(player);
        if (match.isPresent()) {
            return new Layout(messages.get(player, "sidebar.match-title"),
                    messages.lines(player, matchKey(match.get(), player), matchTags(match.get(), player)));
        }
        return new Layout(messages.get(player, "sidebar.lobby-title"), messages.lines(player, "sidebar.lobby", lobbyTags(player)));
    }

    /** Reads the leaderboard positions shown as {@code <rank>}; completes on the main thread. */
    public CompletableFuture<Void> refreshRanks() {
        if (readingRanks) {
            return CompletableFuture.completedFuture(null);
        }
        readingRanks = true;
        return stats.topByElo(kits.names(), RANK_LIMIT).handle((top, error) -> {
            readingRanks = false;
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Could not read the duel leaderboard for the sidebar", error);
                return null;
            }
            Map<String, Integer> positions = new HashMap<>();
            for (int i = 0; i < top.size(); i++) {
                positions.putIfAbsent(top.get(i).name().toLowerCase(Locale.ROOT), i + 1);
            }
            ranks = Map.copyOf(positions);
            return null;
        });
    }

    /** A fighter's health under their name follows hits and healing; set a tick later, once health has changed. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        healthChanged(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegainHealth(EntityRegainHealthEvent event) {
        healthChanged(event.getEntity());
    }

    private void healthChanged(Entity entity) {
        Settings.Sidebars config = settings.get().sidebars();
        if (entity instanceof Player player && config.match() && config.healthBelowName() && matches.matchOf(player).isPresent()) {
            Tasks.later(plugin, () -> {
                if (player.isOnline()) {
                    Sidebar.updateHealth(player);
                }
            }, 1);
        }
    }

    /** Hides every sidebar this plugin showed; for shutdown. */
    public void hideAll() {
        shown.values().forEach(Sidebar::hide);
        shown.clear();
    }

    /** @param health whether to show health under names, see {@link Sidebar#healthBelowName} */
    private void show(Player player, Layout layout, boolean health) {
        Sidebar current = shown.get(player.getUniqueId());
        Sidebar onScreen = Sidebar.of(player).orElse(null);
        if (onScreen != null && onScreen != current) {
            // Another plugin's sidebar: it goes first.
            shown.remove(player.getUniqueId());
            return;
        }
        if (onScreen == null) {
            current = Sidebar.show(plugin, player, layout.title());
            shown.put(player.getUniqueId(), current);
        }
        List<Component> lines = layout.lines();
        if (lines.size() > Sidebar.MAX_LINES) {
            if (!warnedTooLong) {
                warnedTooLong = true;
                plugin.getLogger().warning("A sidebar in messages.yml has more than " + Sidebar.MAX_LINES + " lines; the rest are cut");
            }
            lines = lines.subList(0, Sidebar.MAX_LINES);
        }
        current.title(layout.title()).lines(lines)
                .healthBelowName(health ? messages.get(player, "sidebar.health-below-name") : null);
    }

    private void hide(Player player) {
        Sidebar sidebar = shown.remove(player.getUniqueId());
        if (sidebar != null) {
            sidebar.hide();
        }
    }

    private boolean inLobby(Player player, Settings.Sidebars config) {
        return settings.get().isLobby(player.getWorld().getName(), config.lobbyWorlds());
    }

    private static String matchKey(Match match, Player viewer) {
        if (!match.isFighter(viewer)) {
            return "sidebar.spectating";
        }
        if (!match.isDuel()) {
            return "sidebar.team-fight";
        }
        return match.isRanked() ? "sidebar.ranked-duel" : "sidebar.duel";
    }

    private TagResolver[] matchTags(Match match, Player viewer) {
        int team = match.teamOf(viewer.getUniqueId());
        Player opponent = match.isDuel() && team >= 0 ? match.opponentOf(viewer) : null;
        long teamLeft = team < 0 ? 0 : match.teams().get(team).stream().filter(match::isAlive).count();
        long enemiesLeft = match.fighters().stream().filter(match::isAlive).count() - teamLeft;
        String kit = match.kit().name();
        int elo = stats.elo(viewer.getUniqueId(), kit);
        TagResolver round = TagResolver.resolver(Placeholder.unparsed("round", String.valueOf(match.round())),
                Placeholder.unparsed("score", match.score(team)));
        return new TagResolver[]{
                round,
                Placeholder.component("rounds", match.roundsToWin() > 1 ? messages.get(viewer, "sidebar.rounds", round) : Component.empty()),
                Placeholder.component("kit", Text.mm(match.kit().displayName())),
                Placeholder.component("arena", Text.mm(match.arena().displayName())),
                Placeholder.unparsed("time", clock(match.timeLeftSeconds())),
                Placeholder.unparsed("ping", String.valueOf(viewer.getPing())),
                Placeholder.unparsed("opponent", opponent == null ? "" : opponent.getName()),
                Placeholder.unparsed("opponent_health", opponent == null ? "" : hearts(opponent.getHealth())),
                Placeholder.unparsed("opponent_ping", opponent == null ? "" : String.valueOf(opponent.getPing())),
                Placeholder.unparsed("elo", String.valueOf(elo)),
                Placeholder.component("division", settings.get().ranked().divisions().name(elo)),
                Placeholder.unparsed("opponent_elo", opponent == null ? "" : String.valueOf(stats.elo(opponent.getUniqueId(), kit))),
                Placeholder.unparsed("team_left", String.valueOf(teamLeft)),
                Placeholder.unparsed("enemies_left", String.valueOf(enemiesLeft)),
                Placeholder.unparsed("fighters", match.teams().stream()
                        .map(players -> players.stream().map(Player::getName).collect(Collectors.joining(", ")))
                        .collect(Collectors.joining(" vs ")))};
    }

    private TagResolver[] lobbyTags(Player viewer) {
        Optional<PlayerStats> own = stats.cached(viewer.getUniqueId());
        Component loading = messages.get(viewer, "sidebar.loading");
        Function<Function<PlayerStats, Object>, Component> stat = value ->
                own.map(found -> (Component) Component.text(String.valueOf(value.apply(found)))).orElse(loading);
        Integer position = ranks.get(viewer.getName().toLowerCase(Locale.ROOT));
        return new TagResolver[]{
                Placeholder.component("wins", stat.apply(PlayerStats::wins)),
                Placeholder.component("losses", stat.apply(PlayerStats::losses)),
                Placeholder.component("win_rate", stat.apply(PlayerStats::winRate)),
                Placeholder.component("win_streak", stat.apply(PlayerStats::winStreak)),
                Placeholder.component("best_win_streak", stat.apply(PlayerStats::bestWinStreak)),
                Placeholder.component("elo", stat.apply(found -> found.overallElo(kits.names()))),
                Placeholder.component("division", own.map(found -> settings.get().ranked().divisions()
                        .name(found.overallElo(kits.names()))).orElse(loading)),
                Placeholder.component("rank", position == null ? messages.get(viewer, "sidebar.no-rank")
                        : messages.get(viewer, "sidebar.rank", Placeholder.unparsed("position", String.valueOf(position)))),
                Placeholder.component("queue", queues.queued(viewer.getUniqueId())
                        .map(id -> messages.get(viewer, "sidebar.queue",
                                Placeholder.component("kit", kits.get(id.kit()).map(Kit::displayName).map(Text::mm)
                                        .orElse(Component.text(id.kit()))),
                                Placeholder.component("type", messages.get(viewer, id.ranked() ? "queue.type-ranked" : "queue.type-unranked"))))
                        .orElseGet(() -> messages.get(viewer, "sidebar.queue-none"))),
                Placeholder.component("party", parties.partyOf(viewer.getUniqueId()).map(Party::size)
                        .map(size -> messages.get(viewer, "sidebar.party", Placeholder.unparsed("size", String.valueOf(size))))
                        .orElseGet(() -> messages.get(viewer, "sidebar.party-none"))),
                Placeholder.unparsed("active_matches", String.valueOf(matches.activeMatches()))};
    }

    /** m:ss */
    private static String clock(int seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE);
    }

    /** Health in hearts, to half a heart. */
    private static String hearts(double health) {
        double hearts = Math.ceil(health) / 2;
        return hearts == Math.floor(hearts) ? String.valueOf((int) hearts) : String.valueOf(hearts);
    }
}
