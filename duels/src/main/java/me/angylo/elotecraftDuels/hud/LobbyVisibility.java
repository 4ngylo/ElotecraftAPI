package me.angylo.elotecraftDuels.hud;

import me.angylo.elotecraftDuels.PlayerOptions;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.match.MatchManager;
import me.angylo.elotecraftDuels.party.PartyManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@link PlayerOptions#LOBBY_PLAYERS}: a player who turned it off sees only their party members while in the lobby
 * worlds of config.yml {@code lobby-items.worlds}. In fights, and elsewhere, everyone shows again. Only undoes the
 * hiding it did, so other plugins' vanish stays. Synced once a second from {@code Duels.tick()}. Main thread only.
 */
public final class LobbyVisibility {

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final MatchManager matches;
    private final PartyManager parties;
    /** Viewer to the players this hid from them. */
    private final Map<UUID, Set<UUID>> hidden = new HashMap<>();

    public LobbyVisibility(Plugin plugin, Supplier<Settings> settings, MatchManager matches, PartyManager parties) {
        this.plugin = plugin;
        this.settings = settings;
        this.matches = matches;
        this.parties = parties;
    }

    // ponytail: every viewer against every player each second; fine for hundreds online, by-world sets past that
    public void tick() {
        hidden.keySet().removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            boolean hiding = !PlayerOptions.LOBBY_PLAYERS.isOn(viewer) && matches.matchOf(viewer).isEmpty()
                    && settings.get().isLobby(viewer.getWorld().getName(), settings.get().lobbyItems().worlds());
            Set<UUID> hiddenNow = hidden.computeIfAbsent(viewer.getUniqueId(), uuid -> new HashSet<>());
            for (Player other : plugin.getServer().getOnlinePlayers()) {
                boolean hide = hiding && !other.equals(viewer) && !sameParty(viewer, other);
                if (hide && hiddenNow.add(other.getUniqueId())) {
                    viewer.hidePlayer(plugin, other);
                } else if (!hide && hiddenNow.remove(other.getUniqueId())) {
                    viewer.showPlayer(plugin, other);
                }
            }
            hiddenNow.removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
        }
    }

    /** Shows everyone this hid; for shutdown. */
    public void showAll() {
        hidden.forEach((viewerId, others) -> {
            Player viewer = plugin.getServer().getPlayer(viewerId);
            if (viewer != null) {
                others.stream().map(plugin.getServer()::getPlayer).filter(other -> other != null)
                        .forEach(other -> viewer.showPlayer(plugin, other));
            }
        });
        hidden.clear();
    }

    private boolean sameParty(Player viewer, Player other) {
        return parties.partyOf(viewer.getUniqueId()).filter(party -> party.contains(other.getUniqueId())).isPresent();
    }
}
