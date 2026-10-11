package me.angylo.elotecraftDuels.match;

import org.bukkit.Bukkit;
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
        private int kills;
        private int deaths;
        /** Kills since the last death. */
        private int streak;
    }

    private final Map<UUID, Counts> counts = new HashMap<>();
    /** Each fighter's last opponent to hit them, who gets the kill. */
    private final Map<UUID, UUID> lastHitBy = new HashMap<>();
    /** The server tick of each fighter's last hit taken. */
    private final Map<UUID, Integer> lastHitAt = new HashMap<>();

    /** A hit by {@code attacker} on {@code victim}: it adds to the attacker's combo and ends the victim's. */
    public void hit(Player attacker, Player victim) {
        Counts landed = of(attacker);
        landed.hits++;
        landed.combo++;
        landed.longestCombo = Math.max(landed.longestCombo, landed.combo);
        of(victim).combo = 0;
        lastHitBy.put(victim.getUniqueId(), attacker.getUniqueId());
        lastHitAt.put(victim.getUniqueId(), Bukkit.getCurrentTick());
    }

    /** Forgets who hit whom, for a new round. */
    void clearLastHits() {
        lastHitBy.clear();
        lastHitAt.clear();
    }

    /** A free-for-all: {@code victim} died and comes back, so nobody gets them again for older hits. */
    void died(Player victim, Player killer) {
        lastHitBy.remove(victim.getUniqueId());
        lastHitAt.remove(victim.getUniqueId());
        Counts dead = of(victim);
        dead.deaths++;
        dead.streak = 0;
        if (killer != null) {
            of(killer).kills++;
            of(killer).streak++;
        }
    }

    /** The last opponent who hit {@code victim} at most {@code ticks} ago. */
    Optional<UUID> lastHitBy(Player victim, int ticks) {
        Integer at = lastHitAt.get(victim.getUniqueId());
        return at != null && Bukkit.getCurrentTick() - at <= ticks ? lastHitBy(victim) : Optional.empty();
    }

    /** Kills in a free-for-all since joining. */
    public int kills(Player fighter) {
        return of(fighter).kills;
    }

    /** Deaths in a free-for-all since joining. */
    public int deaths(Player fighter) {
        return of(fighter).deaths;
    }

    /** Kills in a free-for-all since the last death. */
    public int streak(Player fighter) {
        return of(fighter).streak;
    }

    /** A free-for-all: {@code fighter} left, so rejoining starts their counts again. */
    void forget(Player fighter) {
        counts.remove(fighter.getUniqueId());
        lastHitBy.remove(fighter.getUniqueId());
        lastHitAt.remove(fighter.getUniqueId());
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
