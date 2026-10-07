package me.angylo.elotecraftDuels.event;

import me.angylo.elotecraftAPI.util.Durations;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.match.MatchManager;
import me.angylo.elotecraftDuels.match.QueueManager;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Player-hosted events: a host picks the kit, arena and rules while players join, then everyone fights
 * once in one arena, everyone for themselves or in two teams; the last players or team standing win.
 * Joined players count as busy, so they cannot queue or duel meanwhile. An event starts when the host
 * says so, when it is full, or when its wait ends with enough players; otherwise it is cancelled.
 * Main thread only.
 */
public final class EventManager implements Listener {

    public static final String JOIN = "duels.event";
    public static final String HOST = "duels.event.host";
    public static final String HOST_PRIVATE = "duels.event.host.private";
    private static final String BYPASS_COOLDOWN = "duels.bypass.cooldown";
    private static final long MILLIS_PER_TICK = 50;

    private final Messages messages;
    private final Supplier<Settings> settings;
    private final KitRegistry kits;
    private final ArenaRegistry arenas;
    private final MatchManager matches;
    private final QueueManager queues;
    /** Hosts, in the order they started hosting. */
    private final Map<UUID, HostedEvent> byHost = new LinkedHashMap<>();
    private final Map<UUID, HostedEvent> byPlayer = new HashMap<>();
    /** When each host last hosted, in server ticks. */
    private final Map<UUID, Long> lastHosted = new HashMap<>();

    public EventManager(Messages messages, Supplier<Settings> settings, KitRegistry kits, ArenaRegistry arenas,
                        MatchManager matches, QueueManager queues) {
        this.messages = messages;
        this.settings = settings;
        this.kits = kits;
        this.arenas = arenas;
        this.matches = matches;
        this.queues = queues;
    }

    /** Whether {@code player} joined an event that has not started yet. */
    public boolean isWaiting(Player player) {
        return byPlayer.containsKey(player.getUniqueId());
    }

    public Optional<HostedEvent> eventOf(Player player) {
        return Optional.ofNullable(byPlayer.get(player.getUniqueId()));
    }

    /** The event {@code host} hosts, if they host one. */
    public Optional<HostedEvent> hostedBy(Player host) {
        return Optional.ofNullable(byHost.get(host.getUniqueId()));
    }

    /** Public events still gathering players, oldest first. */
    public List<HostedEvent> openEvents() {
        return byHost.values().stream().filter(HostedEvent::isOpen).toList();
    }

    /**
     * Starts gathering players for an event with {@code kit}, announced to everyone. {@code host} joins it.
     *
     * @return false after telling {@code host} why not
     */
    public boolean host(Player host, Kit kit) {
        if (isWaiting(host)) {
            messages.send(host, "event.already-in");
            return false;
        }
        if (matches.isBusy(host)) {
            messages.send(host, "general.busy-self");
            return false;
        }
        long now = Bukkit.getCurrentTick();
        long readyAt = lastHosted.getOrDefault(host.getUniqueId(), Long.MIN_VALUE / 2)
                + settings.get().events().hostCooldown().toMillis() / MILLIS_PER_TICK;
        if (now < readyAt && !host.hasPermission(BYPASS_COOLDOWN)) {
            messages.send(host, "event.cooldown", time((int) ((readyAt - now) * MILLIS_PER_TICK / 1000) + 1));
            return false;
        }
        if (!kit.canUse(host) || kit.isEmpty()) {
            messages.send(host, "general.kit-locked", kitTag(kit));
            return false;
        }
        if (!matches.hasArenaFor(kit)) {
            messages.send(host, "general.no-arena-for-kit", kitTag(kit));
            return false;
        }
        Settings.Events config = settings.get().events();
        HostedEvent event = new HostedEvent(host.getUniqueId(), host.getName(), kit.name(),
                (int) config.waitTime().toSeconds(), announceSeconds());
        byHost.put(host.getUniqueId(), event);
        byPlayer.put(host.getUniqueId(), event);
        lastHosted.put(host.getUniqueId(), now);
        queues.handleQuit(host);
        messages.send(host, "event.hosted", kitTag(kit));
        announce(event);
        return true;
    }

    /** {@code player} joins {@code event}; it starts once it is full. */
    public void join(Player player, HostedEvent event) {
        if (byHost.get(event.host()) != event) {
            // Started or cancelled while a menu still showed it.
            messages.send(player, "event.not-found", Placeholder.unparsed("player", event.hostName()));
            return;
        }
        if (isWaiting(player)) {
            messages.send(player, "event.already-in");
            return;
        }
        if (matches.isBusy(player)) {
            messages.send(player, "general.busy-self");
            return;
        }
        if (!event.isOpen() && !event.isInvited(player.getUniqueId())) {
            messages.send(player, "event.private", hostTag(event));
            return;
        }
        int max = settings.get().events().maxPlayers();
        if (event.size() >= max) {
            messages.send(player, "event.full");
            return;
        }
        event.add(player.getUniqueId());
        byPlayer.put(player.getUniqueId(), event);
        queues.handleQuit(player);
        messages.send(player, "event.joined", hostTag(event), countTag(event), maxTag());
        tellOthers(event, player, "event.player-joined", Placeholder.unparsed("player", player.getName()), countTag(event), maxTag());
        if (event.size() >= max) {
            startOrCancel(event);
        }
    }

    /** {@code /event join <host>} */
    public void join(Player player, String hostName) {
        Player host = Bukkit.getPlayerExact(hostName);
        HostedEvent event = host == null ? null : byHost.get(host.getUniqueId());
        if (event == null) {
            messages.send(player, "event.not-found", Placeholder.unparsed("player", hostName));
            return;
        }
        join(player, event);
    }

    /**
     * Takes {@code player} out of the event they joined; the host leaving cancels it.
     *
     * @return false if they are in none
     */
    public boolean leave(Player player) {
        HostedEvent event = byPlayer.get(player.getUniqueId());
        if (event == null) {
            return false;
        }
        if (event.isHost(player.getUniqueId())) {
            cancel(event, "event.cancelled");
            return true;
        }
        event.remove(player.getUniqueId());
        byPlayer.remove(player.getUniqueId());
        messages.send(player, "event.left");
        tellOthers(event, player, "event.player-left", Placeholder.unparsed("player", player.getName()), countTag(event), maxTag());
        return true;
    }

    /** The host lets {@code target} join a private event, and tells them how. */
    public void invite(Player host, Player target) {
        HostedEvent event = hosting(host);
        if (event == null) {
            return;
        }
        if (target.equals(host) || event.players().contains(target.getUniqueId())) {
            messages.send(host, "event.already-joined", Placeholder.unparsed("player", target.getName()));
            return;
        }
        event.invite(target.getUniqueId());
        messages.send(host, "event.invited", Placeholder.unparsed("player", target.getName()));
        Kit kit = kits.get(event.kit()).orElse(null);
        messages.send(target, "event.invite-received", hostTag(event), kit == null ? Placeholder.unparsed("kit", event.kit()) : kitTag(kit),
                joinTag(target, event));
    }

    /**
     * The host starts the event now. Team picks come from the team menu: players who left are dropped and
     * players who joined since go to the smaller team.
     *
     * @param red  the red team's ids, or null for random teams (and for a free-for-all)
     * @param blue the blue team's ids, or null
     */
    public void start(Player host, Collection<UUID> red, Collection<UUID> blue) {
        HostedEvent event = hosting(host);
        if (event == null) {
            return;
        }
        String problem = launch(event, red, blue);
        if (problem != null) {
            messages.send(host, problem, hostTag(event), minTag());
        }
    }

    /** Ends the event before it started; everyone in it hears {@code messageKey}. */
    public void cancel(HostedEvent event, String messageKey) {
        byHost.remove(event.host(), event);
        for (UUID uuid : event.players()) {
            byPlayer.remove(uuid, event);
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                messages.send(player, messageKey, hostTag(event), minTag());
            }
        }
    }

    /** {@code /event cancel}: the host calls it off. */
    public void cancel(Player host) {
        HostedEvent event = hosting(host);
        if (event != null) {
            cancel(event, "event.cancelled");
        }
    }

    // Settings the host changes from the event menu. Each returns quietly if {@code host} hosts nothing.

    /** A new kit; an arena it cannot use goes back to random. */
    public void kit(Player host, Kit kit) {
        HostedEvent event = hosting(host);
        if (event == null) {
            return;
        }
        if (!kit.canUse(host) || kit.isEmpty()) {
            messages.send(host, "general.kit-locked", kitTag(kit));
            return;
        }
        event.kit(kit.name());
        Arena arena = event.arena() == null ? null : arenas.get(event.arena()).orElse(null);
        if (arena != null && !kit.accepts(arena)) {
            event.arena(null);
        }
    }

    /** @param arena null for a random one */
    public void arena(Player host, Arena arena) {
        HostedEvent event = hosting(host);
        if (event != null) {
            event.arena(arena == null ? null : arena.name());
        }
    }

    public void toggleMode(Player host) {
        HostedEvent event = hosting(host);
        if (event != null) {
            event.mode(event.mode() == HostedEvent.Mode.FFA ? HostedEvent.Mode.TEAMS : HostedEvent.Mode.FFA);
        }
    }

    /** Changes the number of free-for-all winners by {@code delta}, from 1 to one less than the most players. */
    public void changeWinners(Player host, int delta) {
        HostedEvent event = hosting(host);
        if (event != null && event.mode() == HostedEvent.Mode.FFA) {
            event.winners(Math.clamp(event.winners() + delta, 1, settings.get().events().maxPlayers() - 1));
        }
    }

    /** Public to private and back; private needs {@link #HOST_PRIVATE}. */
    public void toggleOpen(Player host) {
        HostedEvent event = hosting(host);
        if (event == null) {
            return;
        }
        if (event.isOpen() && !host.hasPermission(HOST_PRIVATE)) {
            messages.send(host, "command.no-permission");
            return;
        }
        event.open(!event.isOpen());
    }

    public void toggleSpectating(Player host) {
        HostedEvent event = hosting(host);
        if (event != null) {
            event.spectatable(!event.isSpectatable());
        }
    }

    public void toggleBorder(Player host) {
        HostedEvent event = hosting(host);
        if (event != null) {
            event.border(!event.hasBorder());
        }
    }

    /** @param value null for the kit's own */
    public void rule(Player host, KitRule rule, Boolean value) {
        HostedEvent event = hosting(host);
        if (event != null && rule.isFlag()) {
            event.rule(rule, value);
        }
    }

    /** Once a second: counts down, announces public events again, and starts or cancels those whose wait ended. */
    public void tick() {
        int interval = announceSeconds();
        for (HostedEvent event : List.copyOf(byHost.values())) {
            if (event.countDown() <= 0) {
                startOrCancel(event);
            } else if (event.isOpen() && event.announceDue(interval)) {
                announce(event);
            }
        }
    }

    private int announceSeconds() {
        return (int) Math.max(1, settings.get().events().announceInterval().toSeconds());
    }

    /** Starts the event with random teams, or cancels it saying why it could not start. */
    private void startOrCancel(HostedEvent event) {
        String problem = launch(event, null, null);
        if (problem != null) {
            // event.not-enough becomes event.cancelled-not-enough, and so on.
            cancel(event, "event.cancelled-" + problem.substring("event.".length()));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        HostedEvent hosted = byPlayer.get(event.getPlayer().getUniqueId());
        if (hosted == null) {
            return;
        }
        if (hosted.isHost(event.getPlayer().getUniqueId())) {
            cancel(hosted, "event.cancelled-host-left");
        } else {
            leave(event.getPlayer());
        }
    }

    /** Drops every event without a word; for shutdown. */
    public void clear() {
        byHost.clear();
        byPlayer.clear();
        lastHosted.clear();
    }

    /**
     * Turns the event into a match.
     *
     * @return null once started (the event is gone), else the key of the message saying why not
     */
    private String launch(HostedEvent event, Collection<UUID> red, Collection<UUID> blue) {
        Kit base = kits.get(event.kit()).filter(kit -> !kit.isEmpty()).orElse(null);
        if (base == null) {
            return "event.kit-gone";
        }
        List<Player> players = event.players().stream().map(Bukkit::getPlayer).filter(Objects::nonNull).toList();
        if (players.size() < settings.get().events().minPlayers()) {
            return "event.not-enough";
        }
        Kit kit = event.kitFor(base);
        Arena arena = event.arena() == null ? null : arenas.get(event.arena()).filter(found -> found.isReady() && kit.accepts(found)).orElse(null);
        if (arena == null || !matches.isArenaFree(arena)) {
            arena = matches.randomFreeArena(kit).orElse(null);
        }
        if (arena == null) {
            return matches.hasArenaFor(kit) ? "event.no-free-arena" : "event.no-arena";
        }
        List<List<Player>> teams = event.mode() == HostedEvent.Mode.FFA
                ? players.stream().map(List::of).toList()
                : teams(players, red, blue);
        // Out of the event first: it makes them busy, and the match only takes free players.
        cancelQuietly(event);
        players.forEach(queues::handleQuit);
        int winners = Math.min(event.winners(), teams.size() - 1);
        Match.Options options = new Match.Options(winners, event.isSpectatable(), event.hasBorder(), event.hostName());
        if (!matches.start(teams, kit, arena, Match.Type.EVENT, false, options)) {
            players.forEach(player -> messages.send(player, "event.start-failed", hostTag(event)));
        }
        return null;
    }

    /** Red and blue from the picks, newcomers on the smaller team; random halves without picks. */
    private static List<List<Player>> teams(List<Player> players, Collection<UUID> red, Collection<UUID> blue) {
        List<Player> redTeam = new ArrayList<>();
        List<Player> blueTeam = new ArrayList<>();
        List<Player> newcomers = new ArrayList<>();
        for (Player player : players) {
            UUID uuid = player.getUniqueId();
            (red != null && red.contains(uuid) ? redTeam : blue != null && blue.contains(uuid) ? blueTeam : newcomers).add(player);
        }
        Collections.shuffle(newcomers);
        newcomers.forEach(player -> (redTeam.size() <= blueTeam.size() ? redTeam : blueTeam).add(player));
        // Two players picked onto one team still need someone to fight.
        if (blueTeam.isEmpty()) {
            blueTeam.add(redTeam.removeLast());
        } else if (redTeam.isEmpty()) {
            redTeam.add(blueTeam.removeLast());
        }
        return List.of(redTeam, blueTeam);
    }

    private void cancelQuietly(HostedEvent event) {
        byHost.remove(event.host(), event);
        event.players().forEach(uuid -> byPlayer.remove(uuid, event));
    }

    /** The event {@code host} hosts; null after telling them they host none. */
    private HostedEvent hosting(Player host) {
        HostedEvent event = byHost.get(host.getUniqueId());
        if (event == null) {
            messages.send(host, byPlayer.containsKey(host.getUniqueId()) ? "event.not-host" : "event.not-hosting");
        }
        return event;
    }

    /** Tells every online player about a public event, with a button to join it. */
    private void announce(HostedEvent event) {
        if (!event.isOpen()) {
            return;
        }
        Kit kit = kits.get(event.kit()).orElse(null);
        Arena arena = event.arena() == null ? null : arenas.get(event.arena()).orElse(null);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission(JOIN)) {
                continue;
            }
            messages.send(player, "event.announce", hostTag(event), countTag(event), maxTag(),
                    kit == null ? Placeholder.unparsed("kit", event.kit()) : kitTag(kit),
                    Placeholder.component("arena", arena == null ? messages.get(player, "general.random-arena") : Text.mm(arena.displayName())),
                    Placeholder.component("mode", messages.get(player, event.mode() == HostedEvent.Mode.FFA ? "event.mode-ffa" : "event.mode-teams")),
                    time(event.secondsLeft()), joinTag(player, event));
        }
    }

    private void tellOthers(HostedEvent event, Player except, String key, TagResolver... tags) {
        for (UUID uuid : event.players()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && !player.equals(except)) {
                messages.send(player, key, tags);
            }
        }
    }

    private TagResolver joinTag(Player viewer, HostedEvent event) {
        return Placeholder.styling("join", ClickEvent.runCommand("/event join " + event.hostName()),
                HoverEvent.showText(messages.get(viewer, "event.join-hover")));
    }

    private static TagResolver hostTag(HostedEvent event) {
        return Placeholder.unparsed("host", event.hostName());
    }

    private static TagResolver countTag(HostedEvent event) {
        return Placeholder.unparsed("players", String.valueOf(event.size()));
    }

    private TagResolver maxTag() {
        return Placeholder.unparsed("max", String.valueOf(settings.get().events().maxPlayers()));
    }

    private TagResolver minTag() {
        return Placeholder.unparsed("min", String.valueOf(settings.get().events().minPlayers()));
    }

    private static TagResolver time(int seconds) {
        return Placeholder.unparsed("time", Durations.format(Duration.ofSeconds(Math.max(0, seconds))));
    }

    private static TagResolver kitTag(Kit kit) {
        return Placeholder.component("kit", Text.mm(kit.displayName()));
    }
}
