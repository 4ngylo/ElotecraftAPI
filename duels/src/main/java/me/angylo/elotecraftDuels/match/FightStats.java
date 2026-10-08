package me.angylo.elotecraftDuels.match;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Hits, combos and health potions of each fighter in one fight, shown by {@code /duel inventory}. Main thread only. */
public final class FightStats {

    private static final class Counts {
        private int hits;
        private int combo;
        private int longestCombo;
        private int potionsThrown;
        private int potionsMissed;
    }

    private final Map<UUID, Counts> counts = new HashMap<>();
    /** Each fighter's last opponent to hit them, who gets the kill. */
    private final Map<UUID, UUID> lastHitBy = new HashMap<>();

    /** A hit by {@code attacker} on {@code victim}: it adds to the attacker's combo and ends the victim's. */
    public void hit(Player attacker, Player victim) {
        Counts landed = of(attacker);
        landed.hits++;
        landed.combo++;
        landed.longestCombo = Math.max(landed.longestCombo, landed.combo);
        of(victim).combo = 0;
        lastHitBy.put(victim.getUniqueId(), attacker.getUniqueId());
    }

    /** Forgets who hit whom, for a new round. */
    void clearLastHits() {
        lastHitBy.clear();
    }

    /** The last opponent who hit {@code victim} in this fight, if any. */
    Optional<UUID> lastHitBy(Player victim) {
        return Optional.ofNullable(lastHitBy.get(victim.getUniqueId()));
    }

    /** A thrown health potion; missed when it healed its thrower less than half. */
    public void healthPotion(Player thrower, boolean missed) {
        Counts thrown = of(thrower);
        thrown.potionsThrown++;
        if (missed) {
            thrown.potionsMissed++;
        }
    }

    int hits(Player fighter) {
        return of(fighter).hits;
    }

    int longestCombo(Player fighter) {
        return of(fighter).longestCombo;
    }

    int potionsThrown(Player fighter) {
        return of(fighter).potionsThrown;
    }

    int potionsMissed(Player fighter) {
        return of(fighter).potionsMissed;
    }

    private Counts of(Player fighter) {
        return counts.computeIfAbsent(fighter.getUniqueId(), uuid -> new Counts());
    }
}
