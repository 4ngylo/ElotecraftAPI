package me.angylo.elotecraftDuels;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A player's own on/off choices from {@code /duel options}. All start on; turning one off sets a flag in
 * the player's data, so it lasts across restarts without the database (but not across servers).
 */
public enum PlayerOptions {

    /** Duel requests from others; also {@code /duel toggle}. */
    REQUESTS,
    PARTY_INVITES,
    SIDEBAR,
    /** Sounds of config.yml {@code effects}; particles stay, since others see them too. */
    SOUNDS,
    /** Others may watch my duels and party fights; staff always can. Checked in {@code Match.watchableBy}. */
    SPECTATORS,
    /** Seeing other players in the lobby worlds; off, only party members show ({@code LobbyVisibility}). */
    LOBBY_PLAYERS;

    /** REQUESTS keeps {@code requests-off}, the flag {@code /duel toggle} set before this menu existed. */
    private final NamespacedKey off = Objects.requireNonNull(NamespacedKey.fromString("elotecraftduels:" + key() + "-off"));

    /** The option named {@code key}, as in {@code /duel toggle <key>}; any case. */
    public static Optional<PlayerOptions> byKey(String key) {
        return Arrays.stream(values()).filter(option -> option.key().equalsIgnoreCase(key)).findFirst();
    }

    public static List<String> keys() {
        return Arrays.stream(values()).map(PlayerOptions::key).toList();
    }

    /** The menus.yml {@code options} and messages.yml {@code options.names} key of this option. */
    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public boolean isOn(Player player) {
        return !player.getPersistentDataContainer().has(off);
    }

    /** @return whether it is on now */
    public boolean toggle(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (data.has(off)) {
            data.remove(off);
            return true;
        }
        data.set(off, PersistentDataType.BOOLEAN, true);
        return false;
    }
}
