package me.angylo.elotecraftDuels.party;

import me.angylo.elotecraftAPI.util.Durations;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.PingRange;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import me.angylo.elotecraftDuels.match.DailyRanked;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.match.MatchManager;
import me.angylo.elotecraftDuels.match.QueueManager;
import me.angylo.elotecraftDuels.stats.StatsService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The 2v2 queues of each kit, unranked and ranked: a party of two queues as a team, and solo players are paired into
 * teams, first come first served. Two teams fight a {@link Match.Type#TEAM} duel once every player of one team fits
 * every player of the other's {@link PingRange}; ranked teams also need average ratings in range of the team waiting
 * longer, a range that widens with the wait as in solo ranked queues. A place is lost once a member is offline, busy,
 * in a solo queue, or the party changed. Main thread only.
 */
public final class TeamQueue {

    public static final int TEAM_SIZE = 2;
    private static final long TICKS_PER_SECOND = 20;
    private static final long MILLIS_PER_TICK = 50;

    /** One 2v2 queue: a kit, unranked or ranked. */
    public record Key(String kit, boolean ranked) {
    }

    /** A queued party (its leader's id, both members) or solo player, with the tick it joined on. */
    private record Entry(UUID id, List<UUID> members, long joinedTick) {
    }

    /** A party, or two solo players, ready to fight. */
    private record Team(List<Entry> entries) {

        List<UUID> members() {
            return entries.stream().flatMap(entry -> entry.members().stream()).toList();
        }

        long joinedTick() {
            return entries.stream().mapToLong(Entry::joinedTick).min().orElse(0);
        }
    }

    private final Messages messages;
    private final Supplier<Settings> settings;
    private final KitRegistry kits;
    private final MatchManager matches;
    private final QueueManager queues;
    private final PartyManager parties;
    private final StatsService stats;
    /** Each queue's entries, oldest first, by entry id. */
    private final Map<Key, LinkedHashMap<UUID, Entry>> waiting = new LinkedHashMap<>();
    /** Every queued player to their queue and entry id. */
    private final Map<UUID, UUID> entryOf = new HashMap<>();
    private final Map<UUID, Key> queueOf = new HashMap<>();
    /** Entries told they wait for an arena, so they are told once. */
    private final Set<UUID> toldWaiting = new HashSet<>();

    public TeamQueue(Messages messages, Supplier<Settings> settings, KitRegistry kits, MatchManager matches,
                     QueueManager queues, PartyManager parties, StatsService stats) {
        this.messages = messages;
        this.settings = settings;
        this.kits = kits;
        this.matches = matches;
        this.queues = queues;
        this.parties = parties;
        this.stats = stats;
    }

    /**
     * Joins {@code kit}'s unranked or ranked 2v2 queue: with the party of two {@code player} leads, or alone outside a
     * party. Joining the same queue again leaves it.
     */
    public void toggle(Player player, Kit kit, boolean ranked) {
        Key key = new Key(kit.name(), ranked);
        if (key.equals(queueOf.get(player.getUniqueId()))) {
            leave(player);
            return;
        }
        Party party = parties.partyOf(player.getUniqueId()).orElse(null);
        if (party != null && !party.isLeader(player.getUniqueId())) {
            messages.send(player, "party.not-leader");
            return;
        }
        if (party != null && party.size() != TEAM_SIZE) {
            messages.send(player, "queue.team-size", Placeholder.unparsed("size", String.valueOf(TEAM_SIZE)));
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
        List<Player> members = new ArrayList<>();
        for (UUID uuid : party == null ? List.of(player.getUniqueId()) : party.members()) {
            Player member = Bukkit.getPlayer(uuid);
            if (member == null || !matches.available(member)) {
                messages.send(player, member == player ? "general.busy-self" : "party.member-busy",
                        Placeholder.unparsed("player", member == null ? "?" : member.getName()));
                return;
            }
            if (ranked && !queues.rankedAllowed(member)) {
                if (member != player) {
                    messages.send(player, "queue.team-member-locked", Placeholder.unparsed("player", member.getName()));
                }
                return;
            }
            members.add(member);
        }
        members.forEach(member -> {
            remove(member.getUniqueId());
            queues.leave(member);
        });
        Entry entry = new Entry(player.getUniqueId(), members.stream().map(Player::getUniqueId).toList(), Bukkit.getCurrentTick());
        waiting.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).put(entry.id(), entry);
        for (Player member : members) {
            entryOf.put(member.getUniqueId(), entry.id());
            queueOf.put(member.getUniqueId(), key);
            messages.send(member, "queue.team-joined", kitTag(kit), typeTag(member, ranked));
            settings.get().effects().play(member, "queue-join");
        }
        match(key);
    }

    /** Takes {@code player}'s party or themselves out of the queue. @return false if they were not queued */
    public boolean leave(Player player) {
        Key key = queueOf.get(player.getUniqueId());
        Entry entry = remove(player.getUniqueId());
        if (entry == null) {
            return false;
        }
        for (UUID uuid : entry.members()) {
            Player member = Bukkit.getPlayer(uuid);
            if (member != null) {
                messages.send(member, "queue.team-left", kitTag(key.kit()), typeTag(member, key.ranked()));
            }
        }
        return true;
    }

    /** The 2v2 queue {@code player} is in. */
    public Optional<Key> queued(Player player) {
        return Optional.ofNullable(queueOf.get(player.getUniqueId()));
    }

    /** Players in {@code kit}'s unranked or ranked 2v2 queue. */
    public int size(String kit, boolean ranked) {
        LinkedHashMap<UUID, Entry> entries = waiting.get(new Key(kit, ranked));
        return entries == null ? 0 : entries.values().stream().mapToInt(entry -> entry.members().size()).sum();
    }

    /** Drops lost places, starts the duels that can start and shows each player their waiting time; call every second. */
    public void tick() {
        for (Key key : List.copyOf(waiting.keySet())) {
            match(key);
        }
        long now = Bukkit.getCurrentTick();
        Settings.Ranked ranked = settings.get().ranked();
        waiting.forEach((key, entries) -> entries.values().forEach(entry -> {
            long waited = now - entry.joinedTick();
            TagResolver[] tags = {kitTag(key.kit()), Placeholder.unparsed("time", Durations.format(Duration.ofMillis(waited * MILLIS_PER_TICK))),
                    Placeholder.unparsed("elo", String.valueOf(average(entry.members(), key.kit()))),
                    Placeholder.unparsed("range", String.valueOf(ranked.range(waited / TICKS_PER_SECOND)))};
            for (UUID uuid : entry.members()) {
                Player member = Bukkit.getPlayer(uuid);
                if (member != null) {
                    member.sendActionBar(messages.get(member, key.ranked() ? "queue.team-action-bar-ranked" : "queue.team-action-bar", tags));
                }
            }
        }));
    }

    public void clear() {
        waiting.clear();
        entryOf.clear();
        queueOf.clear();
        toldWaiting.clear();
    }

    private void match(Key key) {
        LinkedHashMap<UUID, Entry> entries = waiting.get(key);
        if (entries == null) {
            return;
        }
        Optional<Kit> kit = kits.get(key.kit()).filter(found -> !found.disabled());
        for (Entry entry : List.copyOf(entries.values())) {
            if (kit.isEmpty() || !stillValid(entry)) {
                removeEntry(key, entry);
                tell(entry, "queue.team-dropped", kitTag(key.kit()));
            }
        }
        if (kit.isEmpty()) {
            return;
        }
        List<Team> teams = teams(List.copyOf(entries.values()));
        Optional<List<Team>> pair;
        while ((pair = findPair(teams, key)).isPresent()) {
            Optional<Arena> arena = matches.randomFreeArena(kit.get());
            if (arena.isEmpty()) {
                pair.get().forEach(team -> team.entries().forEach(this::waitingForArena));
                return;
            }
            teams.removeAll(pair.get());
            pair.get().forEach(team -> team.entries().forEach(entry -> removeEntry(key, entry)));
            List<List<Player>> fighters = pair.get().stream().map(team -> team.members().stream().map(Bukkit::getPlayer).toList()).toList();
            if (!matches.start(fighters, kit.get(), arena.get(), Match.Type.TEAM, key.ranked())) {
                pair.get().forEach(team -> team.entries().forEach(entry -> tell(entry, "party.start-failed")));
            } else if (key.ranked()) {
                fighters.forEach(team -> team.forEach(DailyRanked::count));
            }
        }
    }

    /** Teams in join order: a party is one, and solo players are paired in the order they joined. */
    private static List<Team> teams(List<Entry> entries) {
        List<Team> teams = new ArrayList<>();
        Entry solo = null;
        for (Entry entry : entries) {
            if (entry.members().size() == TEAM_SIZE) {
                teams.add(new Team(List.of(entry)));
            } else if (solo == null) {
                solo = entry;
            } else {
                teams.add(new Team(List.of(solo, entry)));
                solo = null;
            }
        }
        return teams;
    }

    /**
     * The longest-waiting team with an opponent it fits, and the longest-waiting such opponent: every player within the
     * other side's ping range and, ranked, the average ratings within the earlier team's range.
     */
    // ponytail: O(n²) per queue every second, as in QueueManager; fine for queues of tens of teams
    private Optional<List<Team>> findPair(List<Team> teams, Key key) {
        long now = Bukkit.getCurrentTick();
        Settings.Ranked ranked = settings.get().ranked();
        for (int i = 0; i < teams.size(); i++) {
            Team first = teams.get(i);
            int elo = average(first.members(), key.kit());
            int range = ranked.range((now - first.joinedTick()) / TICKS_PER_SECOND);
            for (Team second : teams.subList(i + 1, teams.size())) {
                if (pingsFit(first, second)
                        && (!key.ranked() || Math.abs(elo - average(second.members(), key.kit())) <= range)) {
                    return Optional.of(List.of(first, second));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean pingsFit(Team first, Team second) {
        for (UUID one : first.members()) {
            for (UUID other : second.members()) {
                if (!PingRange.fits(Bukkit.getPlayer(one), Bukkit.getPlayer(other))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The average rating of {@code players} in {@code kit}. */
    private int average(List<UUID> players, String kit) {
        return (int) Math.round(players.stream().mapToInt(uuid -> stats.elo(uuid, kit)).average().orElse(0));
    }

    /** Every member online, free and in no solo queue; a party still exactly these two players, the leader first. */
    private boolean stillValid(Entry entry) {
        for (UUID uuid : entry.members()) {
            Player member = Bukkit.getPlayer(uuid);
            if (member == null || !matches.available(member) || queues.queued(uuid).isPresent()) {
                return false;
            }
        }
        Optional<Party> party = parties.partyOf(entry.id());
        return entry.members().size() == 1 ? party.isEmpty()
                : party.filter(found -> found.isLeader(entry.id()) && found.members().equals(entry.members())).isPresent();
    }

    private void waitingForArena(Entry entry) {
        if (toldWaiting.add(entry.id())) {
            tell(entry, "queue.waiting-arena");
        }
    }

    /** Removes the entry {@code player} belongs to; returns it, or null. */
    private Entry remove(UUID player) {
        UUID id = entryOf.get(player);
        Key key = queueOf.get(player);
        LinkedHashMap<UUID, Entry> entries = key == null ? null : waiting.get(key);
        Entry entry = id == null || entries == null ? null : entries.get(id);
        if (entry != null) {
            removeEntry(key, entry);
        }
        return entry;
    }

    private void removeEntry(Key key, Entry entry) {
        LinkedHashMap<UUID, Entry> entries = waiting.get(key);
        if (entries != null) {
            entries.remove(entry.id());
            if (entries.isEmpty()) {
                waiting.remove(key);
            }
        }
        toldWaiting.remove(entry.id());
        entry.members().forEach(uuid -> {
            entryOf.remove(uuid);
            queueOf.remove(uuid);
        });
    }

    private void tell(Entry entry, String key, TagResolver... tags) {
        for (UUID uuid : entry.members()) {
            Player member = Bukkit.getPlayer(uuid);
            if (member != null) {
                messages.send(member, key, tags);
            }
        }
    }

    /** {@code <type>}: ranked or unranked, in the player's language. */
    private TagResolver typeTag(Player player, boolean ranked) {
        return Placeholder.component("type", messages.get(player, ranked ? "queue.type-ranked" : "queue.type-unranked"));
    }

    private TagResolver kitTag(String name) {
        return kits.get(name).map(TeamQueue::kitTag).orElse(Placeholder.unparsed("kit", name));
    }

    private static TagResolver kitTag(Kit kit) {
        return Placeholder.component("kit", Text.mm(kit.displayName()));
    }
}
