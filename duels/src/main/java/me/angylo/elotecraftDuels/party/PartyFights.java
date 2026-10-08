package me.angylo.elotecraftDuels.party;

import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.match.MatchManager;
import me.angylo.elotecraftDuels.match.QueueManager;
import me.angylo.elotecraftDuels.menu.TeamMenu;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Party fights, started by a party leader: a split into two teams picked in a menu, a free-for-all, or a fight
 * against another party once its leader accepts. Every member must be online and free; they leave their
 * queues when it starts. Party fights are casual: no stats, rating, rewards or rematch. Main thread only.
 */
public final class PartyFights {

    private static final long MILLIS_PER_TICK = 50;

    /** A challenge from {@code fromLeader}'s party; {@code arena} null for a random one. */
    /** @param kit read again from the registry on accept, unless it is a custom kit */
    private record Challenge(UUID fromLeader, Kit kit, String arena, long expiresAtTick) {
    }

    private final Messages messages;
    private final Supplier<Settings> settings;
    private final KitRegistry kits;
    private final ArenaRegistry arenas;
    private final MatchManager matches;
    private final QueueManager queues;
    private final PartyManager parties;
    private final TeamMenu teamMenu;
    /** Challenged leader, then challenging leader, oldest first. */
    private final Map<UUID, Map<UUID, Challenge>> challenges = new HashMap<>();

    public PartyFights(Messages messages, Supplier<Settings> settings, KitRegistry kits, ArenaRegistry arenas,
                       MatchManager matches, QueueManager queues, PartyManager parties, TeamMenu teamMenu) {
        this.messages = messages;
        this.settings = settings;
        this.kits = kits;
        this.arenas = arenas;
        this.matches = matches;
        this.queues = queues;
        this.parties = parties;
        this.teamMenu = teamMenu;
    }

    /** Opens the team menu; its start button calls {@link #startSplit}. @param arena null for a random arena the kit accepts */
    public void split(Player leader, Kit kit, Arena arena) {
        List<Player> members = readyParty(leader, kit);
        if (members != null) {
            teamMenu.open(leader, members, (red, blue) -> startSplit(leader, red, blue, kit, arena));
        }
    }

    /**
     * Starts a split with the picked teams, checking the party again: members who left are dropped and
     * members who joined since go to the smaller team.
     *
     * @param arena null for a random arena the kit accepts
     */
    public void startSplit(Player leader, Collection<UUID> red, Collection<UUID> blue, Kit kit, Arena arena) {
        List<Player> members = readyParty(leader, kit);
        if (members == null) {
            return;
        }
        List<Player> redTeam = new ArrayList<>();
        List<Player> blueTeam = new ArrayList<>();
        List<Player> newcomers = new ArrayList<>();
        for (Player member : members) {
            UUID uuid = member.getUniqueId();
            (red.contains(uuid) ? redTeam : blue.contains(uuid) ? blueTeam : newcomers).add(member);
        }
        newcomers.forEach(member -> (redTeam.size() <= blueTeam.size() ? redTeam : blueTeam).add(member));
        if (redTeam.isEmpty() || blueTeam.isEmpty()) {
            messages.send(leader, "party.split-empty-team");
            return;
        }
        start(leader, List.of(redTeam, blueTeam), kit, arena);
    }

    /** @param arena null for a random arena the kit accepts */
    public void ffa(Player leader, Kit kit, Arena arena) {
        List<Player> members = readyParty(leader, kit);
        if (members != null) {
            start(leader, members.stream().map(List::of).toList(), kit, arena);
        }
    }

    /** Challenges the party {@code target} leads. @param arena null for a random one */
    public void challenge(Player leader, Player target, Kit kit, Arena arena) {
        Party own = parties.ledBy(leader);
        if (own == null) {
            return;
        }
        Party other = parties.partyOf(target.getUniqueId()).orElse(null);
        if (other == null || !other.isLeader(target.getUniqueId())) {
            messages.send(leader, "party.duel-not-leader", name(target));
            return;
        }
        if (other == own) {
            messages.send(leader, "party.duel-same");
            return;
        }
        if (!kit.canUse(leader) || (arena != null && !kit.accepts(arena))) {
            messages.send(leader, arena == null ? "general.kit-locked" : "general.arena-wrong-kit", kitTag(kit), arenaTag(leader, arena));
            return;
        }
        long now = Bukkit.getCurrentTick();
        Map<UUID, Challenge> received = challenges.computeIfAbsent(target.getUniqueId(), uuid -> new LinkedHashMap<>());
        received.values().removeIf(challenge -> now >= challenge.expiresAtTick());
        received.put(leader.getUniqueId(), new Challenge(leader.getUniqueId(), kit, arena == null ? null : arena.name(),
                now + settings.get().requestExpiry().toMillis() / MILLIS_PER_TICK));
        TagResolver[] setup = {kitTag(kit), arenaTag(target, arena)};
        messages.send(leader, "party.duel-sent", with(setup, name(target)));
        String answer = " " + leader.getName();
        messages.send(target, "party.duel-received", with(setup, name(leader),
                Placeholder.styling("accept", ClickEvent.runCommand("/party duelaccept" + answer),
                        HoverEvent.showText(messages.get(target, "request.accept-hover"))),
                Placeholder.styling("deny", ClickEvent.runCommand("/party dueldeny" + answer),
                        HoverEvent.showText(messages.get(target, "request.deny-hover")))));
    }

    /** @param fromName empty for the only challenge */
    public void accept(Player leader, String fromName) {
        if (parties.ledBy(leader) == null) {
            return;
        }
        Challenge challenge = find(leader, fromName);
        if (challenge == null) {
            return;
        }
        challenges.get(leader.getUniqueId()).remove(challenge.fromLeader());
        Player from = Bukkit.getPlayer(challenge.fromLeader());
        Party theirs = from == null ? null : parties.partyOf(from.getUniqueId()).filter(party -> party.isLeader(from.getUniqueId())).orElse(null);
        Optional<Kit> kit = kits.current(challenge.kit());
        if (theirs == null || kit.isEmpty()) {
            messages.send(leader, "party.duel-gone");
            return;
        }
        Arena arena = challenge.arena() == null ? null : arenas.get(challenge.arena()).orElse(null);
        List<Player> ours = readyParty(leader, kit.get(), 1);
        List<Player> them = ours == null ? null : readyParty(leader, theirs, kit.get(), 1);
        if (them != null) {
            start(leader, List.of(them, ours), kit.get(), arena);
        }
    }

    /** @param fromName empty for the only challenge */
    public void deny(Player leader, String fromName) {
        if (parties.ledBy(leader) == null) {
            return;
        }
        Challenge challenge = find(leader, fromName);
        if (challenge == null) {
            return;
        }
        challenges.get(leader.getUniqueId()).remove(challenge.fromLeader());
        messages.send(leader, "party.duel-you-denied");
        Player from = Bukkit.getPlayer(challenge.fromLeader());
        if (from != null) {
            messages.send(from, "party.duel-denied", name(leader));
        }
    }

    public void clear() {
        challenges.clear();
    }

    /** The members of the party {@code leader} leads, at least two, all free; null after explaining. */
    private List<Player> readyParty(Player leader, Kit kit) {
        return readyParty(leader, kit, 2);
    }

    private List<Player> readyParty(Player leader, Kit kit, int minimum) {
        Party party = parties.ledBy(leader);
        if (party == null) {
            return null;
        }
        if (!kit.canUse(leader)) {
            messages.send(leader, "general.kit-locked", kitTag(kit));
            return null;
        }
        return readyParty(leader, party, kit, minimum);
    }

    /** {@code party}'s members if there are enough and all are free; {@code asker} is told otherwise. */
    private List<Player> readyParty(Player asker, Party party, Kit kit, int minimum) {
        if (party.size() < minimum) {
            messages.send(asker, "party.not-enough");
            return null;
        }
        List<Player> members = new ArrayList<>();
        for (UUID uuid : party.members()) {
            Player member = Bukkit.getPlayer(uuid);
            if (member == null || !matches.available(member)) {
                messages.send(asker, "party.member-busy", Placeholder.unparsed("player", member == null ? "?" : member.getName()));
                return null;
            }
            members.add(member);
        }
        return members;
    }

    /** Picks the arena and starts the fight; {@code asker} is told what went wrong. */
    private void start(Player asker, List<List<Player>> teams, Kit kit, Arena chosen) {
        Arena arena = chosen;
        if (arena != null && (!kit.accepts(arena) || !matches.isArenaFree(arena))) {
            messages.send(asker, kit.accepts(arena) ? "general.arena-busy" : "general.arena-wrong-kit", kitTag(kit), arenaTag(asker, arena));
            return;
        }
        if (arena == null) {
            arena = matches.randomFreeArena(kit).orElse(null);
            if (arena == null) {
                messages.send(asker, matches.hasArenaFor(kit) ? "general.no-free-arena" : "general.no-arena-for-kit", kitTag(kit));
                return;
            }
        }
        teams.forEach(team -> team.forEach(queues::handleQuit));
        if (!matches.start(teams, kit, arena, Match.Type.PARTY, false)) {
            messages.send(asker, "party.start-failed");
        }
    }

    private Challenge find(Player leader, String fromName) {
        long now = Bukkit.getCurrentTick();
        List<Challenge> found = challenges.getOrDefault(leader.getUniqueId(), Map.of()).values().stream()
                .filter(challenge -> now < challenge.expiresAtTick())
                .filter(challenge -> {
                    Player from = Bukkit.getPlayer(challenge.fromLeader());
                    return fromName.isEmpty() || (from != null && from.getName().equalsIgnoreCase(fromName));
                }).toList();
        if (found.size() == 1) {
            return found.getFirst();
        }
        messages.send(leader, found.isEmpty() ? "party.duel-none" : "party.duel-which");
        return null;
    }

    private static TagResolver kitTag(Kit kit) {
        return Placeholder.component("kit", Text.mm(kit.displayName()));
    }

    private TagResolver arenaTag(Player viewer, Arena arena) {
        return Placeholder.component("arena", arena == null ? messages.get(viewer, "general.random-arena") : Text.mm(arena.displayName()));
    }

    private static TagResolver name(Player player) {
        return Placeholder.unparsed("player", player.getName());
    }

    private static TagResolver[] with(TagResolver[] tags, TagResolver... more) {
        TagResolver[] all = Arrays.copyOf(tags, tags.length + more.length);
        System.arraycopy(more, 0, all, tags.length, more.length);
        return all;
    }
}
