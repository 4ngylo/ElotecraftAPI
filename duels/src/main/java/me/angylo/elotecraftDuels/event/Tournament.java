package me.angylo.elotecraftDuels.event;

import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.match.MatchManager;
import me.angylo.elotecraftDuels.match.Rewards;
import me.angylo.elotecraftDuels.state.SnapshotStore;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * A single-elimination tournament of 1v1 {@link Match.Type#EVENT} fights, started from a hosted event. Each round
 * pairs the players left at random, an odd one out going through without a fight. Fights start once both players
 * are back from their last one and an arena is free: all at once, or one at a time with the others watching (sumo).
 * The last player left is the champion, paid the event reward if they won the final by a lethal hit. Between fights
 * players count as waiting for an event, so they cannot queue or duel. Main thread only.
 */
final class Tournament {

    /** The pause between rounds. */
    static final int BREAK_SECONDS = 3;

    private record Pair(UUID first, UUID second) {

        UUID other(UUID player) {
            return player.equals(first) ? second : first;
        }
    }

    private final Messages messages;
    private final Supplier<Settings> settings;
    private final ArenaRegistry arenas;
    private final MatchManager matches;
    private final SnapshotStore snapshots;
    private final Rewards rewards;
    private final String host;
    private final Kit kit;
    /** The arena the host picked, or null for random ones. */
    private final String arena;
    private final boolean oneAtATime;
    private final boolean spectatable;
    private final boolean border;
    /** Everyone who started it, with their names for players who left since. */
    private final Map<UUID, String> names = new LinkedHashMap<>();
    /** Players not knocked out yet. */
    private final Set<UUID> alive = new LinkedHashSet<>();
    /** This round's fights not started yet. */
    private final Deque<Pair> pending = new ArrayDeque<>();
    private final Map<Match, Pair> fights = new HashMap<>();
    /** Fighters of the fights running or being started: they are not waiting. */
    private final Set<UUID> fighting = new HashSet<>();
    private int round;
    private int breakLeft;
    private Match lastFight;
    private boolean over;

    Tournament(Messages messages, Supplier<Settings> settings, ArenaRegistry arenas, MatchManager matches,
               SnapshotStore snapshots, Rewards rewards, HostedEvent event, Kit kit, List<Player> players) {
        this.messages = messages;
        this.settings = settings;
        this.arenas = arenas;
        this.matches = matches;
        this.snapshots = snapshots;
        this.rewards = rewards;
        this.host = event.hostName();
        this.kit = kit;
        this.arena = event.arena();
        this.oneAtATime = event.mode() == HostedEvent.Mode.SUMO;
        this.spectatable = event.isSpectatable();
        this.border = event.hasBorder();
        players.forEach(player -> {
            names.put(player.getUniqueId(), player.getName());
            alive.add(player.getUniqueId());
        });
    }

    /** Announces it and starts the first round. */
    void begin() {
        tellAlive("event.tournament-starting", Placeholder.unparsed("host", host), Placeholder.component("kit", Text.mm(kit.displayName())),
                Placeholder.unparsed("players", String.valueOf(alive.size())));
        newRound();
    }

    boolean isOver() {
        return over;
    }

    /** Whether {@code player} is still in it and not fighting right now. */
    boolean isWaiting(UUID player) {
        return alive.contains(player) && !fighting.contains(player);
    }

    /** Whether {@code match} is one of its fights. */
    boolean owns(Match match) {
        return fights.containsKey(match);
    }

    /** Once a second: the break between rounds, then fights whose players and an arena are ready. */
    void tick() {
        if (over) {
            return;
        }
        if (breakLeft > 0) {
            if (--breakLeft == 0) {
                newRound();
            }
            return;
        }
        startFights();
    }

    /** {@code player} left or quit: they are out. One fighting loses that fight first. */
    void quit(UUID player) {
        if (alive.remove(player) && !fighting.contains(player)) {
            roundOverIfDone();
        }
    }

    /** A fight ended: its winner goes on, the other is out. Without a winner, a random one still here goes on. */
    void finished(Match match) {
        Pair pair = fights.remove(match);
        if (pair == null) {
            return;
        }
        fighting.remove(pair.first());
        fighting.remove(pair.second());
        lastFight = match;
        UUID winner = winnerOf(match, pair);
        UUID loser = winner == null ? null : pair.other(winner);
        if (winner == null) {
            alive.remove(pair.first());
            alive.remove(pair.second());
        } else {
            alive.remove(loser);
            send(winner, "event.tournament-advance", Placeholder.unparsed("opponent", names.get(loser)));
            send(loser, "event.tournament-out", Placeholder.unparsed("host", host), Placeholder.unparsed("round", String.valueOf(round)));
        }
        roundOverIfDone();
    }

    private UUID winnerOf(Match match, Pair pair) {
        if (match.winnerTeams().size() == 1) {
            UUID winner = match.teams().get(match.winnerTeams().getFirst()).getFirst().getUniqueId();
            return alive.contains(winner) ? winner : null;
        }
        // ponytail: a draw or a cancelled fight is a coin flip; replay it if players mind
        List<UUID> left = new ArrayList<>(List.of(pair.first(), pair.second()));
        left.removeIf(player -> !alive.contains(player) || Bukkit.getPlayer(player) == null);
        return left.isEmpty() ? null : left.get(ThreadLocalRandom.current().nextInt(left.size()));
    }

    private void newRound() {
        List<UUID> players = new ArrayList<>(alive);
        if (players.size() <= 1) {
            crown();
            return;
        }
        round++;
        Collections.shuffle(players);
        for (int i = 0; i + 1 < players.size(); i += 2) {
            pending.add(new Pair(players.get(i), players.get(i + 1)));
        }
        tellAlive("event.tournament-round", Placeholder.unparsed("round", String.valueOf(round)),
                Placeholder.unparsed("players", String.valueOf(players.size())));
        if (players.size() % 2 == 1) {
            send(players.getLast(), "event.tournament-bye");
        }
        startFights();
    }

    private void startFights() {
        for (Iterator<Pair> it = pending.iterator(); it.hasNext() && !(oneAtATime && !fights.isEmpty()); ) {
            Pair pair = it.next();
            Player first = alive.contains(pair.first()) ? Bukkit.getPlayer(pair.first()) : null;
            Player second = alive.contains(pair.second()) ? Bukkit.getPlayer(pair.second()) : null;
            if (first == null || second == null) {
                // Whoever is still here goes through without a fight.
                it.remove();
                continue;
            }
            if (!ready(first) || !ready(second)) {
                continue;
            }
            Arena free = freeArena();
            if (free == null) {
                break;
            }
            it.remove();
            if (!start(first, second, free)) {
                pending.addFirst(pair);
                break;
            }
        }
        roundOverIfDone();
    }

    /** Back from their last fight and not watching one; a player watching one stops. */
    private boolean ready(Player player) {
        Match watched = matches.matchOf(player).orElse(null);
        if (watched != null) {
            if (watched.isSpectator(player)) {
                matches.leave(player);
            }
            return false;
        }
        return !player.isDead() && !snapshots.isReturning(player.getUniqueId());
    }

    private Arena freeArena() {
        Arena picked = arena == null ? null : arenas.get(arena).filter(found -> found.isReady() && kit.accepts(found)).orElse(null);
        return picked != null && matches.isArenaFree(picked) ? picked : matches.randomFreeArena(kit).orElse(null);
    }

    private boolean start(Player first, Player second, Arena free) {
        fighting.add(first.getUniqueId());
        fighting.add(second.getUniqueId());
        boolean started = matches.start(List.of(List.of(first), List.of(second)), kit, free, Match.Type.EVENT, false,
                new Match.Options(1, spectatable, border, host, true));
        Match match = matches.matchOf(first).filter(found -> found.isFighter(first)).orElse(null);
        if (!started || match == null) {
            fighting.remove(first.getUniqueId());
            fighting.remove(second.getUniqueId());
            return false;
        }
        fights.put(match, new Pair(first.getUniqueId(), second.getUniqueId()));
        if (oneAtATime && spectatable) {
            alive.stream().filter(this::isWaiting).map(Bukkit::getPlayer).filter(Objects::nonNull)
                    .filter(player -> matches.matchOf(player).isEmpty() && !player.isDead()
                            && !snapshots.isReturning(player.getUniqueId()))
                    .forEach(player -> matches.spectate(player, match));
        }
        return true;
    }

    private void roundOverIfDone() {
        if (over || breakLeft > 0 || !pending.isEmpty() || !fights.isEmpty()) {
            return;
        }
        if (alive.size() <= 1) {
            crown();
        } else {
            breakLeft = BREAK_SECONDS;
        }
    }

    /** The last player left wins; nobody left ends it without a word. */
    private void crown() {
        over = true;
        Player champion = alive.size() == 1 ? Bukkit.getPlayer(alive.iterator().next()) : null;
        if (champion == null) {
            return;
        }
        TagResolver[] tags = {Placeholder.unparsed("winner", champion.getName()), Placeholder.unparsed("host", host),
                Placeholder.component("kit", Text.mm(kit.displayName())), Placeholder.unparsed("players", String.valueOf(names.size()))};
        if (settings.get().events().broadcastResult()) {
            Bukkit.getOnlinePlayers().forEach(player -> messages.send(player, "event.tournament-won", tags));
        } else {
            names.keySet().forEach(player -> send(player, "event.tournament-won", tags));
        }
        // Like other events, only a real win pays: not a final the opponent left.
        if (lastFight != null && lastFight.endReason() == Match.EndReason.ELIMINATED && lastFight.isFighter(champion)) {
            rewards.giveEvent(champion, host, kit.name(), lastFight.arena().name());
        }
    }

    private void tellAlive(String key, TagResolver... tags) {
        alive.forEach(player -> send(player, key, tags));
    }

    private void send(UUID player, String key, TagResolver... tags) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            messages.send(online, key, tags);
        }
    }
}
