package me.angylo.elotecraftDuels.match;

import me.angylo.elotecraftAPI.util.Durations;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.PingRange;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.api.QueueJoinEvent;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import me.angylo.elotecraftDuels.stats.PlayerStats;
import me.angylo.elotecraftDuels.stats.StatsService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Matchmaking: an unranked and a ranked queue per kit, paired as soon as an arena is free. Unranked
 * queues pair first come first served; ranked ones pair players whose Elo ratings are in range, longest
 * waiting first, and the range widens the longer they wait. Main thread only, except {@link #queued}.
 */
public final class QueueManager {

    private static final long TICKS_PER_SECOND = 20;
    private static final long MILLIS_PER_TICK = 50;
    private static final String RANKED_PERMISSION = "duels.queue.ranked";

    /** One queue: a kit, unranked or ranked. */
    public record QueueId(String kit, boolean ranked) {
    }

    /** The queue a player's last duel came from, for {@code /duel playagain} until {@code expiresAtTick}. */
    private record LastQueue(QueueId queue, long expiresAtTick) {
    }

    private final Messages messages;
    private final Supplier<Settings> settings;
    private final KitRegistry kits;
    private final MatchManager matches;
    private final StatsService stats;
    /** Each queue's waiting players with the server tick they joined on, oldest first. */
    private final Map<QueueId, LinkedHashMap<UUID, Long>> queues = new LinkedHashMap<>();
    /** Player to the queue they are in; read by placeholders from other threads. */
    private final Map<UUID, QueueId> queued = new ConcurrentHashMap<>();
    /** Players told they are waiting for an arena, so they are told once. */
    private final Set<UUID> toldWaiting = new HashSet<>();
    /** Duels this manager started, until they finish. */
    private final Map<Match, QueueId> started = new HashMap<>();
    private final Map<UUID, LastQueue> lastQueues = new HashMap<>();

    public QueueManager(Messages messages, Supplier<Settings> settings, KitRegistry kits, MatchManager matches,
                        StatsService stats) {
        this.messages = messages;
        this.settings = settings;
        this.kits = kits;
        this.matches = matches;
        this.stats = stats;
        matches.onFinish(this::finished);
    }

    /** Joins {@code kit}'s unranked or ranked queue, leaving any other; joining the same queue again leaves it. */
    public void toggle(Player player, Kit kit, boolean ranked) {
        QueueId id = new QueueId(kit.name(), ranked);
        if (id.equals(queued.get(player.getUniqueId()))) {
            leave(player);
            return;
        }
        if (matches.isBusy(player)) {
            messages.send(player, "general.busy-self");
            return;
        }
        if (!kit.canUse(player)) {
            messages.send(player, "general.kit-locked", kitTag(kit));
            return;
        }
        if (!matches.hasArenaFor(kit)) {
            messages.send(player, "general.no-arena-for-kit", kitTag(kit));
            return;
        }
        if (ranked && !rankedAllowed(player)) {
            return;
        }
        if (!new QueueJoinEvent(player, kit.name(), ranked).callEvent()) {
            return;
        }
        remove(player.getUniqueId());
        queues.computeIfAbsent(id, key -> new LinkedHashMap<>()).put(player.getUniqueId(), (long) Bukkit.getCurrentTick());
        queued.put(player.getUniqueId(), id);
        messages.send(player, "queue.joined", kitTag(kit), typeTag(player, ranked),
                Placeholder.unparsed("queued", String.valueOf(size(kit.name(), ranked))));
        settings.get().effects().play(player, "queue-join");
        match(id);
    }

    /**
     * Whether {@code player} may play ranked now: within their daily limit ({@link DailyRanked}) and with the wins
     * {@code ranked.required-wins} asks for; they are told why not.
     */
    public boolean rankedAllowed(Player player) {
        int dailyLimit = DailyRanked.limit(player, settings.get().ranked().dailyLimit());
        if (!DailyRanked.allowed(player, dailyLimit)) {
            messages.send(player, "queue.ranked-limit", Placeholder.unparsed("limit", String.valueOf(dailyLimit)));
            return false;
        }
        int requiredWins = settings.get().ranked().requiredWins();
        int wins = stats.cached(player.getUniqueId()).map(PlayerStats::wins).orElse(0);
        if (wins < requiredWins) {
            messages.send(player, "queue.ranked-locked", Placeholder.unparsed("required", String.valueOf(requiredWins)),
                    Placeholder.unparsed("wins", String.valueOf(wins)));
            return false;
        }
        return true;
    }

    /** @return false if {@code player} was not queued */
    public boolean leave(Player player) {
        QueueId id = remove(player.getUniqueId());
        if (id == null) {
            return false;
        }
        messages.send(player, "queue.left", kitTag(id.kit()), typeTag(player, id.ranked()));
        return true;
    }

    /** Silently drops a quitting player. */
    public void handleQuit(Player player) {
        remove(player.getUniqueId());
        lastQueues.remove(player.getUniqueId());
    }

    /** The queue {@code player}'s last duel came from, while the rematch window after it is open. */
    public Optional<QueueId> lastQueue(Player player) {
        LastQueue last = lastQueues.get(player.getUniqueId());
        if (last == null || Bukkit.getCurrentTick() >= last.expiresAtTick()) {
            lastQueues.remove(player.getUniqueId());
            return Optional.empty();
        }
        return Optional.of(last.queue());
    }

    /** {@code /duel playagain}: joins the queue {@code player}'s last duel came from again. */
    public void playAgain(Player player) {
        Optional<QueueId> last = lastQueue(player);
        if (last.isEmpty()) {
            messages.send(player, "queue.no-last");
            return;
        }
        if (last.get().ranked() && !player.hasPermission(RANKED_PERMISSION)) {
            messages.send(player, "command.no-permission");
            return;
        }
        Optional<Kit> kit = kits.get(last.get().kit());
        if (kit.isEmpty() || kit.get().disabled()) {
            messages.send(player, kit.isEmpty() ? "queue.kit-removed" : "queue.kit-disabled", kitTag(last.get().kit()));
            return;
        }
        if (!last.get().equals(queued.get(player.getUniqueId()))) {
            toggle(player, kit.get(), last.get().ranked());
        }
    }

    /** Remembers the queue of a duel this manager started for its fighters; any other duel forgets theirs. */
    private void finished(Match match) {
        QueueId queue = started.remove(match);
        if (!match.isDuel()) {
            return;
        }
        long expiresAt = Bukkit.getCurrentTick() + settings.get().rematchWindow().toMillis() / MILLIS_PER_TICK;
        for (Player fighter : match.fighters()) {
            if (queue == null) {
                lastQueues.remove(fighter.getUniqueId());
            } else {
                lastQueues.put(fighter.getUniqueId(), new LastQueue(queue, expiresAt));
            }
        }
    }

    /** The queue {@code player} is in; safe from any thread. */
    public Optional<QueueId> queued(UUID player) {
        return Optional.ofNullable(queued.get(player));
    }

    public int size(String kit, boolean ranked) {
        LinkedHashMap<UUID, Long> queue = queues.get(new QueueId(kit, ranked));
        return queue == null ? 0 : queue.size();
    }

    /** Pairs waiting players and shows each their waiting time, and their rating range if ranked; call every second. */
    public void tick() {
        for (QueueId id : Set.copyOf(queues.keySet())) {
            match(id);
        }
        long now = Bukkit.getCurrentTick();
        Settings.Ranked ranked = settings.get().ranked();
        queues.forEach((id, queue) -> queue.forEach((uuid, joined) -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendActionBar(messages.get(player, id.ranked() ? "queue.action-bar-ranked" : "queue.action-bar",
                        kitTag(id.kit()), typeTag(player, id.ranked()),
                        Placeholder.unparsed("time", Durations.format(Duration.ofMillis((now - joined) * MILLIS_PER_TICK))),
                        Placeholder.unparsed("elo", String.valueOf(stats.elo(uuid, id.kit()))),
                        Placeholder.unparsed("range", String.valueOf(ranked.range((now - joined) / TICKS_PER_SECOND)))));
            }
        }));
    }

    public void clear() {
        queues.clear();
        queued.clear();
        toldWaiting.clear();
        started.clear();
        lastQueues.clear();
    }

    private void match(QueueId id) {
        LinkedHashMap<UUID, Long> queue = queues.get(id);
        if (queue == null) {
            return;
        }
        Optional<Kit> kit = kits.get(id.kit());
        if (kit.isEmpty() || kit.get().disabled()) {
            String key = kit.isEmpty() ? "queue.kit-removed" : "queue.kit-disabled";
            for (UUID uuid : Set.copyOf(queue.keySet())) {
                remove(uuid);
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    messages.send(player, key, kitTag(id.kit()));
                }
            }
            return;
        }
        dropUnavailable(queue);
        Optional<List<Player>> pair;
        while ((pair = id.ranked() ? findRankedPair(id.kit(), queue) : findPair(queue)).isPresent()) {
            Optional<Arena> arena = matches.randomFreeArena(kit.get());
            if (arena.isEmpty()) {
                for (Player player : pair.get()) {
                    if (toldWaiting.add(player.getUniqueId())) {
                        messages.send(player, "queue.waiting-arena");
                    }
                }
                return;
            }
            Player first = pair.get().get(0);
            Player second = pair.get().get(1);
            remove(first.getUniqueId());
            remove(second.getUniqueId());
            if (!matches.start(first, second, kit.get(), arena.get(), id.ranked())) {
                continue;
            }
            matches.matchOf(first).ifPresent(match -> started.put(match, id));
            if (id.ranked()) {
                DailyRanked.count(first);
                DailyRanked.count(second);
            }
        }
    }

    /** The longest-waiting player who fits someone's ping range, and the longest-waiting one they fit; see {@link PingRange}. */
    private static Optional<List<Player>> findPair(LinkedHashMap<UUID, Long> queue) {
        List<UUID> waiting = List.copyOf(queue.keySet());
        for (int i = 0; i < waiting.size(); i++) {
            Player first = Bukkit.getPlayer(waiting.get(i));
            for (UUID other : waiting.subList(i + 1, waiting.size())) {
                Player second = Bukkit.getPlayer(other);
                if (PingRange.fits(first, second)) {
                    return Optional.of(List.of(first, second));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The longest-waiting player with an opponent in rating range in {@code kit}, and the longest-waiting such opponent.
     * The range is the earlier player's, the wider of the two, so waiting long enough finds anyone. Both must
     * also fit each other's {@link PingRange}.
     */
    // ponytail: O(n²) per kit every second, as is findPair; sort by rating if a queue ever holds hundreds of players
    private Optional<List<Player>> findRankedPair(String kit, LinkedHashMap<UUID, Long> queue) {
        Settings.Ranked ranked = settings.get().ranked();
        long now = Bukkit.getCurrentTick();
        List<UUID> waiting = List.copyOf(queue.keySet());
        for (int i = 0; i < waiting.size(); i++) {
            UUID first = waiting.get(i);
            int elo = stats.elo(first, kit);
            int range = ranked.range((now - queue.get(first)) / TICKS_PER_SECOND);
            for (UUID second : waiting.subList(i + 1, waiting.size())) {
                if (Math.abs(elo - stats.elo(second, kit)) <= range
                        && PingRange.fits(Bukkit.getPlayer(first), Bukkit.getPlayer(second))) {
                    return Optional.of(List.of(Bukkit.getPlayer(first), Bukkit.getPlayer(second)));
                }
            }
        }
        return Optional.empty();
    }

    /** Players who went offline or got busy (accepted a duel, started spectating) lose their place. */
    private void dropUnavailable(LinkedHashMap<UUID, Long> queue) {
        for (UUID uuid : Set.copyOf(queue.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !matches.available(player)) {
                remove(uuid);
            }
        }
    }

    /** @return the queue {@code player} was in, or null */
    private QueueId remove(UUID player) {
        QueueId id = queued.remove(player);
        toldWaiting.remove(player);
        if (id != null) {
            LinkedHashMap<UUID, Long> queue = queues.get(id);
            if (queue != null) {
                queue.remove(player);
                if (queue.isEmpty()) {
                    queues.remove(id);
                }
            }
        }
        return id;
    }

    /** {@code <type>}: ranked or unranked, in the player's language. */
    private TagResolver typeTag(Player player, boolean ranked) {
        return Placeholder.component("type", messages.get(player, ranked ? "queue.type-ranked" : "queue.type-unranked"));
    }

    private TagResolver kitTag(String name) {
        return kits.get(name).map(QueueManager::kitTag).orElse(Placeholder.unparsed("kit", name));
    }

    private static TagResolver kitTag(Kit kit) {
        return Placeholder.component("kit", Text.mm(kit.displayName()));
    }
}
