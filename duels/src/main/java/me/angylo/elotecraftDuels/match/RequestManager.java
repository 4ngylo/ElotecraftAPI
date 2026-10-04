package me.angylo.elotecraftDuels.match;

import me.angylo.elotecraftAPI.util.Cooldowns;
import me.angylo.elotecraftAPI.util.Durations;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Duel requests: a challenge stays open until it is accepted, denied, expires, or either player
 * leaves. Main thread only.
 */
public final class RequestManager {

    public static final String BYPASS_COOLDOWN = "duels.bypass.cooldown";
    private static final long MILLIS_PER_TICK = 50;

    /**
     * @param arena        null for a random free arena
     * @param expiresAtTick server tick, so the expiry follows game time
     */
    private record Request(UUID sender, String senderName, UUID target, String targetName, String kit, String arena,
                           long expiresAtTick) {
    }

    private final Messages messages;
    private final Supplier<Settings> settings;
    private final KitRegistry kits;
    private final ArenaRegistry arenas;
    private final MatchManager matches;
    private final Cooldowns<UUID> cooldowns = new Cooldowns<>();
    /** Target, then sender, in the order the challenges arrived. */
    private final Map<UUID, Map<UUID, Request>> pending = new HashMap<>();

    public RequestManager(Messages messages, Supplier<Settings> settings, KitRegistry kits, ArenaRegistry arenas,
                          MatchManager matches) {
        this.messages = messages;
        this.settings = settings;
        this.kits = kits;
        this.arenas = arenas;
        this.matches = matches;
    }

    /**
     * Challenges {@code target}; every check is explained to {@code sender} in chat.
     *
     * @param arena   null for a random free arena
     * @param rematch skips the cooldown
     */
    public void send(Player sender, Player target, Kit kit, Arena arena, boolean rematch) {
        if (sender.equals(target)) {
            messages.send(sender, "request.self");
            return;
        }
        if (!target.isOnline()) {
            messages.send(sender, "general.player-not-found", Placeholder.unparsed("player", target.getName()));
            return;
        }
        if (matches.isBusy(sender)) {
            messages.send(sender, "general.busy-self");
            return;
        }
        if (matches.isBusy(target)) {
            messages.send(sender, "general.busy-other", Placeholder.unparsed("player", target.getName()));
            return;
        }
        if (!kit.canUse(sender)) {
            messages.send(sender, "general.kit-locked", kitTag(kit));
            return;
        }
        if (pending.getOrDefault(target.getUniqueId(), Map.of()).containsKey(sender.getUniqueId())) {
            messages.send(sender, "request.already-sent", Placeholder.unparsed("player", target.getName()));
            return;
        }
        if (!rematch && !sender.hasPermission(BYPASS_COOLDOWN)
                && !cooldowns.tryUse(sender.getUniqueId(), settings.get().requestCooldown())) {
            messages.send(sender, "request.cooldown",
                    Placeholder.unparsed("time", Durations.format(cooldowns.remaining(sender.getUniqueId()))));
            settings.get().effects().play(sender, "denied");
            return;
        }
        pending.computeIfAbsent(target.getUniqueId(), uuid -> new LinkedHashMap<>()).put(sender.getUniqueId(), new Request(sender.getUniqueId(), sender.getName(), target.getUniqueId(),
                target.getName(), kit.name(), arena == null ? null : arena.name(),
                Bukkit.getCurrentTick() + settings.get().requestExpiry().toMillis() / MILLIS_PER_TICK));
        TagResolver[] setup = {kitTag(kit), arenaTag(target, arena)};
        messages.send(sender, "request.sent", MatchDisplay.with(setup, Placeholder.unparsed("player", target.getName())));
        String answer = " " + sender.getName();
        messages.send(target, "request.received", MatchDisplay.with(setup,
                Placeholder.unparsed("player", sender.getName()),
                Placeholder.styling("accept", ClickEvent.runCommand("/duel accept" + answer),
                        HoverEvent.showText(messages.get(target, "request.accept-hover"))),
                Placeholder.styling("deny", ClickEvent.runCommand("/duel deny" + answer),
                        HoverEvent.showText(messages.get(target, "request.deny-hover")))));
        settings.get().effects().play(target, "request-received");
    }

    /** @param senderName empty to accept the only pending challenge */
    public void accept(Player target, String senderName) {
        Optional<Request> found = find(target, senderName);
        if (found.isEmpty()) {
            return;
        }
        Request request = found.get();
        Player sender = Bukkit.getPlayer(request.sender());
        if (sender == null) {
            remove(request);
            messages.send(target, "general.player-not-found", Placeholder.unparsed("player", request.senderName()));
            return;
        }
        if (!matches.available(target)) {
            messages.send(target, "general.busy-self");
            return;
        }
        if (!matches.available(sender)) {
            messages.send(target, "general.busy-other", Placeholder.unparsed("player", sender.getName()));
            return;
        }
        Optional<Kit> kit = kits.get(request.kit());
        if (kit.isEmpty()) {
            remove(request);
            messages.send(target, "general.kit-not-found", Placeholder.unparsed("kit", request.kit()));
            return;
        }
        if (!kit.get().canUse(target)) {
            messages.send(target, "general.kit-locked", kitTag(kit.get()));
            return;
        }
        Optional<Arena> arena = request.arena() == null ? matches.randomFreeArena() : freeArena(target, request.arena());
        if (arena.isEmpty()) {
            if (request.arena() == null) {
                messages.send(target, "general.no-free-arena");
            }
            return;
        }
        remove(request);
        if (matches.start(sender, target, kit.get(), arena.get())) {
            messages.send(sender, "request.accepted", Placeholder.unparsed("player", target.getName()));
        }
    }

    /** @param senderName empty to deny the only pending challenge */
    public void deny(Player target, String senderName) {
        find(target, senderName).ifPresent(request -> {
            remove(request);
            messages.send(target, "request.you-denied", Placeholder.unparsed("player", request.senderName()));
            Player sender = Bukkit.getPlayer(request.sender());
            if (sender != null) {
                messages.send(sender, "request.denied", Placeholder.unparsed("player", target.getName()));
                settings.get().effects().play(sender, "denied");
            }
        });
    }

    /** Challenges the last opponent again with the same kit and arena, or accepts their rematch request. */
    public void rematch(Player player) {
        Optional<MatchManager.Rematch> rematch = matches.rematchOf(player);
        Player opponent = rematch.map(last -> Bukkit.getPlayer(last.opponent())).orElse(null);
        if (opponent == null) {
            messages.send(player, "request.rematch-none");
            return;
        }
        Map<UUID, Request> toPlayer = pending.getOrDefault(player.getUniqueId(), Map.of());
        if (toPlayer.containsKey(opponent.getUniqueId())) {
            accept(player, opponent.getName());
            return;
        }
        Optional<Kit> kit = kits.get(rematch.get().kit());
        if (kit.isEmpty()) {
            messages.send(player, "general.kit-not-found", Placeholder.unparsed("kit", rematch.get().kit()));
            return;
        }
        send(player, opponent, kit.get(), arenas.get(rematch.get().arena()).orElse(null), true);
    }

    /** Names of the players who challenged {@code target}, oldest first; for tab completion. */
    public List<String> sendersOf(Player target) {
        return pending.getOrDefault(target.getUniqueId(), Map.of()).values().stream().map(Request::senderName).toList();
    }

    /** Expires old requests; call every second. */
    public void tick() {
        long now = Bukkit.getCurrentTick();
        List<Request> expired = new ArrayList<>();
        pending.values().forEach(requests -> requests.values().stream()
                .filter(request -> now >= request.expiresAtTick())
                .forEach(expired::add));
        for (Request request : expired) {
            remove(request);
            notify(request.sender(), "request.expired-sender", request.targetName());
            notify(request.target(), "request.expired-target", request.senderName());
        }
    }

    /** Drops every request to or from a quitting player and tells the other side. */
    public void handleQuit(Player player) {
        UUID uuid = player.getUniqueId();
        Map<UUID, Request> received = pending.remove(uuid);
        if (received != null) {
            received.values().forEach(request -> notify(request.sender(), "request.cancelled-quit", player.getName()));
        }
        pending.forEach((target, requests) -> {
            if (requests.remove(uuid) != null) {
                notify(target, "request.cancelled-quit", player.getName());
            }
        });
        pending.values().removeIf(Map::isEmpty);
        cooldowns.clear(uuid);
    }

    public void clear() {
        pending.clear();
    }

    private Optional<Request> find(Player target, String senderName) {
        Map<UUID, Request> requests = pending.getOrDefault(target.getUniqueId(), Map.of());
        if (requests.isEmpty()) {
            messages.send(target, "request.none");
            return Optional.empty();
        }
        if (senderName.isEmpty()) {
            if (requests.size() > 1) {
                messages.send(target, "request.choose");
                return Optional.empty();
            }
            return Optional.of(requests.values().iterator().next());
        }
        Optional<Request> request = requests.values().stream()
                .filter(candidate -> candidate.senderName().equalsIgnoreCase(senderName))
                .findFirst();
        if (request.isEmpty()) {
            messages.send(target, "request.none-from", Placeholder.unparsed("player", senderName));
        }
        return request;
    }

    /** The chosen arena if it still exists and is free; explains otherwise. */
    private Optional<Arena> freeArena(Player target, String name) {
        Optional<Arena> arena = arenas.get(name).filter(Arena::isReady);
        if (arena.isEmpty()) {
            messages.send(target, "general.arena-not-found", Placeholder.unparsed("arena", name));
            return Optional.empty();
        }
        if (matches.isArenaBusy(name)) {
            messages.send(target, "general.arena-busy", arenaTag(target, arena.get()));
            return Optional.empty();
        }
        return arena;
    }

    private void remove(Request request) {
        Map<UUID, Request> requests = pending.get(request.target());
        if (requests != null) {
            requests.remove(request.sender());
            if (requests.isEmpty()) {
                pending.remove(request.target());
            }
        }
    }

    private void notify(UUID player, String key, String otherName) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            messages.send(online, key, Placeholder.unparsed("player", otherName));
        }
    }

    private static TagResolver kitTag(Kit kit) {
        return Placeholder.component("kit", Text.mm(kit.displayName()));
    }

    private TagResolver arenaTag(Player viewer, Arena arena) {
        Component name = arena == null ? messages.get(viewer, "general.random-arena") : Text.mm(arena.displayName());
        return Placeholder.component("arena", name);
    }

}
