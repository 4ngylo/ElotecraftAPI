package me.angylo.elotecraftDuels.match;

import org.bukkit.Bukkit;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The fighters of finished fights, kept for a while for {@code /duel inventory}. Main thread only. */
final class FightResults {

    /** How long the inventories of a finished fight can be looked at. */
    private static final Duration KEPT = Duration.ofMinutes(10);
    private static final long MILLIS_PER_TICK = 50;

    /** The fighters of one fight, by lowercase name. */
    private record Kept(Map<String, FighterResult> byName, long expiresAtTick) {

        boolean expired() {
            return Bukkit.getCurrentTick() >= expiresAtTick;
        }
    }

    private final Map<UUID, Kept> byId = new HashMap<>();

    /** Keeps {@code fighters} and returns the id their links use. */
    UUID keep(List<FighterResult> fighters) {
        Map<String, FighterResult> byName = new LinkedHashMap<>();
        fighters.forEach(result -> byName.put(result.name().toLowerCase(Locale.ROOT), result));
        UUID id = UUID.randomUUID();
        byId.put(id, new Kept(byName, Bukkit.getCurrentTick() + KEPT.toMillis() / MILLIS_PER_TICK));
        return id;
    }

    /** The fighters kept under {@code id}, empty once it expired. */
    List<FighterResult> all(UUID id) {
        Kept kept = byId.get(id);
        return kept == null || kept.expired() ? List.of() : List.copyOf(kept.byName().values());
    }

    /** A fighter of a fight that ended in the last {@link #KEPT}, by the id in its result message. */
    Optional<FighterResult> get(String id, String name) {
        try {
            Kept kept = byId.get(UUID.fromString(id));
            return kept == null || kept.expired() ? Optional.empty()
                    : Optional.ofNullable(kept.byName().get(name.toLowerCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    void purgeExpired() {
        byId.values().removeIf(Kept::expired);
    }

    void clear() {
        byId.clear();
    }
}
