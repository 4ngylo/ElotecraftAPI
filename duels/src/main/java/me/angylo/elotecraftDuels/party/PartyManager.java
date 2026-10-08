package me.angylo.elotecraftDuels.party;

import me.angylo.elotecraftAPI.util.Cooldowns;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.PlayerOptions;
import me.angylo.elotecraftDuels.Settings;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Parties: create, invite (invites expire after {@code parties.invite-expiry}), accept, kick, leave,
 * disband and promote. A player is in at most one party; quitting the server leaves it, and
 * a leader who leaves hands the party to the member who joined next. Main thread only, except {@link #partyOf}
 * and {@link #onChat}.
 */
public final class PartyManager implements Listener {

    private static final long MILLIS_PER_TICK = 50;
    private static final Duration ADVERTISE_COOLDOWN = Duration.ofMinutes(1);

    private final Plugin plugin;
    private final Messages messages;
    private final Supplier<Settings> settings;
    /** Member to their party; read by placeholders from other threads. */
    private final Map<UUID, Party> byPlayer = new ConcurrentHashMap<>();
    private final List<Party> parties = new ArrayList<>();
    private final Cooldowns<UUID> advertised = new Cooldowns<>();
    /** Members whose chat goes to their party; read on chat threads. */
    private final Set<UUID> chatMode = ConcurrentHashMap.newKeySet();

    public PartyManager(Plugin plugin, Messages messages, Supplier<Settings> settings) {
        this.plugin = plugin;
        this.messages = messages;
        this.settings = settings;
    }

    /** {@code player}'s party; safe from any thread. */
    public Optional<Party> partyOf(UUID player) {
        return Optional.ofNullable(byPlayer.get(player));
    }

    public void create(Player player) {
        if (byPlayer.containsKey(player.getUniqueId())) {
            messages.send(player, "party.already-in-party");
            return;
        }
        newParty(player);
        messages.send(player, "party.created");
    }

    /** Invites {@code target}, creating a party led by {@code player} if they have none. */
    public void invite(Player player, Player target) {
        if (target.equals(player)) {
            messages.send(player, "party.invite-self");
            return;
        }
        Party party = byPlayer.get(player.getUniqueId());
        if (party != null && !party.isLeader(player.getUniqueId())) {
            messages.send(player, "party.not-leader");
            return;
        }
        if (byPlayer.containsKey(target.getUniqueId())) {
            messages.send(player, "party.target-in-party", name(target));
            return;
        }
        if (!PlayerOptions.PARTY_INVITES.isOn(target)) {
            messages.send(player, "party.invites-disabled", name(target));
            return;
        }
        if (party != null && party.size() >= settings.get().partyMaxSize()) {
            messages.send(player, "party.full");
            return;
        }
        long now = Bukkit.getCurrentTick();
        if (party != null && party.hasInvite(target.getUniqueId(), now)) {
            messages.send(player, "party.already-invited", name(target));
            return;
        }
        if (party == null) {
            party = newParty(player);
            messages.send(player, "party.created");
        }
        party.invite(target.getUniqueId(), now + settings.get().partyInviteExpiry().toMillis() / MILLIS_PER_TICK);
        messages.send(player, "party.invited", name(target));
        String answer = " " + player.getName();
        messages.send(target, "party.invite-received", name(player),
                Placeholder.styling("accept", ClickEvent.runCommand("/party accept" + answer),
                        HoverEvent.showText(messages.get(target, "request.accept-hover"))),
                Placeholder.styling("deny", ClickEvent.runCommand("/party deny" + answer),
                        HoverEvent.showText(messages.get(target, "request.deny-hover"))));
    }

    /** @param leaderName empty to accept the only invite */
    public void accept(Player player, String leaderName) {
        if (byPlayer.containsKey(player.getUniqueId())) {
            messages.send(player, "party.already-in-party");
            return;
        }
        Optional<Party> found = invitedTo(player, leaderName);
        if (found.isEmpty()) {
            return;
        }
        Party party = found.get();
        if (party.size() >= settings.get().partyMaxSize()) {
            party.removeInvite(player.getUniqueId());
            messages.send(player, "party.full");
            return;
        }
        party.add(player.getUniqueId());
        byPlayer.put(player.getUniqueId(), party);
        broadcast(party, "party.joined", name(player));
    }

    /** @param leaderName empty to deny the only invite */
    public void deny(Player player, String leaderName) {
        invitedTo(player, leaderName).ifPresent(party -> {
            party.removeInvite(player.getUniqueId());
            messages.send(player, "party.you-denied");
            Player leader = Bukkit.getPlayer(party.leader());
            if (leader != null) {
                messages.send(leader, "party.denied", name(player));
            }
        });
    }

    public void kick(Player player, String targetName) {
        Party party = ledBy(player);
        if (party == null) {
            return;
        }
        Player target = member(party, targetName);
        if (target == null) {
            messages.send(player, "party.not-member", Placeholder.unparsed("player", targetName));
            return;
        }
        if (target.equals(player)) {
            messages.send(player, "party.kick-self");
            return;
        }
        removeMember(party, target.getUniqueId());
        messages.send(target, "party.kicked");
        broadcast(party, "party.kicked-broadcast", name(target));
    }

    /** @return false if {@code player} is in no party */
    public boolean leave(Player player) {
        Party party = byPlayer.get(player.getUniqueId());
        if (party == null) {
            messages.send(player, "party.not-in-party");
            return false;
        }
        boolean wasLeader = party.isLeader(player.getUniqueId());
        removeMember(party, player.getUniqueId());
        messages.send(player, "party.left");
        announceLeft(party, player.getName(), wasLeader);
        return true;
    }

    public void disband(Player player) {
        Party party = ledBy(player);
        if (party == null) {
            return;
        }
        broadcast(party, "party.disbanded", name(player));
        for (UUID member : party.members()) {
            byPlayer.remove(member);
            chatMode.remove(member);
        }
        parties.remove(party);
    }

    public void promote(Player player, String targetName) {
        Party party = ledBy(player);
        if (party == null) {
            return;
        }
        Player target = member(party, targetName);
        if (target == null || target.equals(player)) {
            messages.send(player, "party.not-member", Placeholder.unparsed("player", targetName));
            return;
        }
        party.promote(target.getUniqueId());
        broadcast(party, "party.promoted", name(target));
    }

    public void info(Player player) {
        Party party = byPlayer.get(player.getUniqueId());
        if (party == null) {
            messages.send(player, "party.not-in-party");
            return;
        }
        messages.send(player, "party.info-header", Placeholder.unparsed("size", String.valueOf(party.size())),
                Placeholder.unparsed("max", String.valueOf(settings.get().partyMaxSize())));
        for (UUID member : party.members()) {
            Player online = Bukkit.getPlayer(member);
            messages.send(player, party.isLeader(member) ? "party.info-leader" : "party.info-member",
                    Placeholder.unparsed("player", online == null ? member.toString() : online.getName()));
        }
    }

    /**
     * Makes {@code player}'s party public, so anyone may {@link #join} it, or private again; leader only. Going
     * public is announced to everyone not in a party, with a click to join.
     */
    public void toggleOpen(Player player) {
        Party party = ledBy(player);
        if (party == null) {
            return;
        }
        party.open(!party.isOpen());
        broadcast(party, party.isOpen() ? "party.opened" : "party.closed", name(player));
        // A leader switching back and forth announces it once a minute at most.
        if (party.isOpen() && advertised.tryUse(player.getUniqueId(), ADVERTISE_COOLDOWN)) {
            ClickEvent join = ClickEvent.runCommand("/party join " + player.getName());
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!byPlayer.containsKey(online.getUniqueId()) && PlayerOptions.PARTY_INVITES.isOn(online)) {
                    messages.send(online, "party.advertised", name(player), Placeholder.styling("join", join,
                            HoverEvent.showText(messages.get(online, "party.join-hover"))));
                }
            }
        }
    }

    /** Joins the party {@code leaderName} leads: a public one, or one that invited {@code player}. */
    public void join(Player player, String leaderName) {
        if (byPlayer.containsKey(player.getUniqueId())) {
            messages.send(player, "party.already-in-party");
            return;
        }
        Player leader = Bukkit.getPlayerExact(leaderName);
        Party party = leader == null ? null : byPlayer.get(leader.getUniqueId());
        if (party == null || !party.isLeader(leader.getUniqueId())) {
            messages.send(player, "party.no-such-party", Placeholder.unparsed("player", leaderName));
            return;
        }
        if (!party.isOpen() && !party.hasInvite(player.getUniqueId(), Bukkit.getCurrentTick())) {
            messages.send(player, "party.not-open", name(leader));
            return;
        }
        if (party.size() >= settings.get().partyMaxSize()) {
            messages.send(player, "party.full");
            return;
        }
        party.add(player.getUniqueId());
        byPlayer.put(player.getUniqueId(), party);
        broadcast(party, "party.joined", name(player));
    }

    /** Public parties, biggest first. */
    public List<Party> openParties() {
        return parties.stream().filter(Party::isOpen).sorted(Comparator.comparingInt(Party::size).reversed()).toList();
    }

    /**
     * Sends {@code message} to {@code player}'s party; it is shown as typed, never as formatting. Without a message,
     * switches party chat mode: while it is on, what they type in chat goes to the party.
     */
    public void chat(Player player, String message) {
        Party party = byPlayer.get(player.getUniqueId());
        if (party == null) {
            messages.send(player, "party.not-in-party");
            return;
        }
        String text = message.strip();
        if (text.isEmpty()) {
            boolean on = !chatMode.remove(player.getUniqueId());
            if (on) {
                chatMode.add(player.getUniqueId());
            }
            messages.send(player, on ? "party.chat-on" : "party.chat-off");
            return;
        }
        broadcast(party, "party.chat", name(player), Placeholder.unparsed("message", text));
    }

    /** Chat of a player in party chat mode goes to their party. LOWEST, like ChatInput, before chat plugins see it. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String text = Text.plain(event.message());
        if (!chatMode.contains(player.getUniqueId()) || text.isBlank()) {
            return;
        }
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                chat(player, text);
            }
        });
    }

    /** A quitting player leaves their party; the others are told. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Party party = byPlayer.get(player.getUniqueId());
        if (party == null) {
            return;
        }
        boolean wasLeader = party.isLeader(player.getUniqueId());
        removeMember(party, player.getUniqueId());
        announceLeft(party, player.getName(), wasLeader);
    }

    public void clear() {
        parties.clear();
        byPlayer.clear();
        chatMode.clear();
    }

    private Party newParty(Player leader) {
        Party party = new Party(leader.getUniqueId());
        parties.add(party);
        byPlayer.put(leader.getUniqueId(), party);
        return party;
    }

    /** Removes {@code player}; an empty party is dropped. */
    private void removeMember(Party party, UUID player) {
        party.remove(player);
        byPlayer.remove(player);
        chatMode.remove(player);
        if (party.size() == 0) {
            parties.remove(party);
        }
    }

    private void announceLeft(Party party, String playerName, boolean wasLeader) {
        if (party.size() == 0) {
            return;
        }
        broadcast(party, "party.left-broadcast", Placeholder.unparsed("player", playerName));
        if (wasLeader) {
            Player leader = Bukkit.getPlayer(party.leader());
            broadcast(party, "party.new-leader", Placeholder.unparsed("player", leader == null ? "?" : leader.getName()));
        }
    }

    /** The party {@code player} leads, or null after telling them they lead none. */
    Party ledBy(Player player) {
        Party party = byPlayer.get(player.getUniqueId());
        if (party == null) {
            messages.send(player, "party.not-in-party");
            return null;
        }
        if (!party.isLeader(player.getUniqueId())) {
            messages.send(player, "party.not-leader");
            return null;
        }
        return party;
    }

    /** The party that invited {@code player}, by leader name or the only one; explains when there is none. */
    private Optional<Party> invitedTo(Player player, String leaderName) {
        long now = Bukkit.getCurrentTick();
        List<Party> inviting = parties.stream().filter(party -> party.hasInvite(player.getUniqueId(), now)
                && (leaderName.isEmpty() || leaderName.equalsIgnoreCase(leaderName(party)))).toList();
        if (inviting.size() == 1) {
            return Optional.of(inviting.getFirst());
        }
        messages.send(player, inviting.isEmpty() ? "party.no-invite" : "party.which-invite");
        return Optional.empty();
    }

    private static String leaderName(Party party) {
        Player leader = Bukkit.getPlayer(party.leader());
        return leader == null ? "" : leader.getName();
    }

    private static Player member(Party party, String name) {
        Player player = Bukkit.getPlayerExact(name);
        return player != null && party.contains(player.getUniqueId()) ? player : null;
    }

    private void broadcast(Party party, String key, TagResolver... tags) {
        for (UUID member : party.members()) {
            Player player = Bukkit.getPlayer(member);
            if (player != null) {
                messages.send(player, key, tags);
            }
        }
    }

    private static TagResolver name(Player player) {
        return Placeholder.unparsed("player", player.getName());
    }
}
