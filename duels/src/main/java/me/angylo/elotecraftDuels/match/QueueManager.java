package me.angylo.elotecraftDuels.match;

import me.angylo.elotecraftAPI.util.Durations;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Matchmaking: one queue per kit, first come first served, paired as soon as two players wait and an
 * arena is free. Main thread only, except {@link #queuedKit}.
 */
public final class QueueManager {

    private final Messages messages;
    private final Supplier<Settings> settings;
    private final KitRegistry kits;
    private final MatchManager matches;
    /** Kit name, then waiting players with the time they joined, oldest first. */
    private final Map<String, LinkedHashMap<UUID, Long>> queues = new LinkedHashMap<>();
    /** Player to kit name; read by placeholders from other threads. */
    private final Map<UUID, String> queuedKits = new ConcurrentHashMap<>();
    /** Players told they are waiting for an arena, so they are told once. */
    private final Set<UUID> toldWaiting = new HashSet<>();

    public QueueManager(Messages messages, Supplier<Settings> settings, KitRegistry kits, MatchManager matches) {
        this.messages = messages;
        this.settings = settings;
        this.kits = kits;
        this.matches = matches;
    }

    /** Joins {@code kit}'s queue, leaving any other; joining the same queue again leaves it. */
    public void toggle(Player player, Kit kit) {
        String current = queuedKits.get(player.getUniqueId());
        if (kit.name().equals(current)) {
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
        remove(player.getUniqueId());
        queues.computeIfAbsent(kit.name(), name -> new LinkedHashMap<>()).put(player.getUniqueId(), System.nanoTime());
        queuedKits.put(player.getUniqueId(), kit.name());
        messages.send(player, "queue.joined", kitTag(kit), Placeholder.unparsed("queued", String.valueOf(size(kit.name()))));
        settings.get().effects().play(player, "queue-join");
        match(kit.name());
    }

    /** @return false if {@code player} was not queued */
    public boolean leave(Player player) {
        String kit = remove(player.getUniqueId());
        if (kit == null) {
            return false;
        }
        messages.send(player, "queue.left", kits.get(kit).map(QueueManager::kitTag).orElse(Placeholder.unparsed("kit", kit)));
        return true;
    }

    /** Silently drops a quitting player. */
    public void handleQuit(Player player) {
        remove(player.getUniqueId());
    }

    /** The kit {@code player} is queued for; safe from any thread. */
    public Optional<String> queuedKit(UUID player) {
        return Optional.ofNullable(queuedKits.get(player));
    }

    public int size(String kit) {
        LinkedHashMap<UUID, Long> queue = queues.get(kit);
        return queue == null ? 0 : queue.size();
    }

    /** Pairs waiting players and shows each their waiting time; call every second. */
    public void tick() {
        for (String kit : Set.copyOf(queues.keySet())) {
            match(kit);
        }
        long now = System.nanoTime();
        queues.forEach((kit, queue) -> queue.forEach((uuid, joined) -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendActionBar(messages.get(player, "queue.action-bar",
                        kits.get(kit).map(QueueManager::kitTag).orElse(Placeholder.unparsed("kit", kit)),
                        Placeholder.unparsed("time", Durations.format(Duration.ofNanos(now - joined)))));
            }
        }));
    }

    public void clear() {
        queues.clear();
        queuedKits.clear();
        toldWaiting.clear();
    }

    private void match(String kitName) {
        LinkedHashMap<UUID, Long> queue = queues.get(kitName);
        if (queue == null) {
            return;
        }
        Optional<Kit> kit = kits.get(kitName);
        if (kit.isEmpty()) {
            for (UUID uuid : Set.copyOf(queue.keySet())) {
                remove(uuid);
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    messages.send(player, "queue.kit-removed", Placeholder.unparsed("kit", kitName));
                }
            }
            return;
        }
        dropUnavailable(queue);
        while (queue.size() >= 2) {
            Optional<Arena> arena = matches.randomFreeArena();
            Iterator<UUID> waiting = queue.keySet().iterator();
            Player first = Bukkit.getPlayer(waiting.next());
            Player second = Bukkit.getPlayer(waiting.next());
            if (arena.isEmpty()) {
                for (Player player : new Player[]{first, second}) {
                    if (toldWaiting.add(player.getUniqueId())) {
                        messages.send(player, "queue.waiting-arena");
                    }
                }
                return;
            }
            remove(first.getUniqueId());
            remove(second.getUniqueId());
            matches.start(first, second, kit.get(), arena.get());
        }
    }

    /** Players who went offline or got busy (accepted a duel, started spectating) lose their place. */
    private void dropUnavailable(LinkedHashMap<UUID, Long> queue) {
        for (UUID uuid : Set.copyOf(queue.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || matches.isBusy(player)) {
                remove(uuid);
            }
        }
    }

    /** @return the kit {@code player} was queued for, or null */
    private String remove(UUID player) {
        String kit = queuedKits.remove(player);
        toldWaiting.remove(player);
        if (kit != null) {
            LinkedHashMap<UUID, Long> queue = queues.get(kit);
            if (queue != null) {
                queue.remove(player);
                if (queue.isEmpty()) {
                    queues.remove(kit);
                }
            }
        }
        return kit;
    }

    private static TagResolver kitTag(Kit kit) {
        return Placeholder.component("kit", Text.mm(kit.displayName()));
    }
}
