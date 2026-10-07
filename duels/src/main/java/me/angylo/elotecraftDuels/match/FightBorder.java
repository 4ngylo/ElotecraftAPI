package me.angylo.elotecraftDuels.match;

import me.angylo.elotecraftDuels.Settings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/**
 * The border of an event fight: a square around the arena that closes in after a delay. Each fighter is
 * shown their own world border, which only draws it; whether someone is outside is worked out here from
 * the fight time, so the damage never depends on the client.
 */
final class FightBorder {

    private static final long TICKS_PER_SECOND = 20;

    private final double centerX;
    private final double centerZ;
    private final double startSize;
    private final double endSize;
    private final long delaySeconds;
    private final long shrinkSeconds;
    private final double damage;

    private FightBorder(double centerX, double centerZ, double startSize, double endSize, long delaySeconds,
                        long shrinkSeconds, double damage) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.startSize = startSize;
        this.endSize = endSize;
        this.delaySeconds = delaySeconds;
        this.shrinkSeconds = shrinkSeconds;
        this.damage = damage;
    }

    /** A border just around the arena's box, closing in as config.yml {@code events.border} says. */
    static FightBorder around(BoundingBox box, Settings.Border config) {
        double start = Math.max(box.getWidthX(), box.getWidthZ()) + 1;
        return new FightBorder(box.getCenterX(), box.getCenterZ(), start, Math.min(start, config.minSize()),
                config.delay().toSeconds(), Math.max(1, config.shrinkTime().toSeconds()), config.damage());
    }

    /** Draws the border at its full size for {@code fighter}. */
    void show(Player fighter) {
        WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter(centerX, centerZ);
        border.setSize(startSize);
        if (delaySeconds == 0) {
            border.changeSize(endSize, shrinkSeconds * TICKS_PER_SECOND);
        }
        fighter.setWorldBorder(border);
    }

    /** Called every second of the fight, from 1: starts the closing in, and hurts fighters outside. */
    void tick(Match match, int fightSeconds) {
        for (Player fighter : match.fighters()) {
            if (!match.isFighting(fighter)) {
                continue;
            }
            WorldBorder shown = fighter.getWorldBorder();
            if (fightSeconds == delaySeconds && shown != null) {
                shown.changeSize(endSize, shrinkSeconds * TICKS_PER_SECOND);
            }
            if (damage > 0 && isOutside(fighter.getLocation(), fightSeconds)) {
                fighter.damage(damage);
            }
        }
    }

    /** Stops drawing it; the player sees their world's border again. */
    static void hide(Player player) {
        player.setWorldBorder(null);
    }

    /** The width of the square {@code fightSeconds} into the fight. */
    double size(long fightSeconds) {
        double progress = Math.clamp((double) (fightSeconds - delaySeconds) / shrinkSeconds, 0, 1);
        return startSize + (endSize - startSize) * progress;
    }

    boolean isOutside(Location location, long fightSeconds) {
        double half = size(fightSeconds) / 2;
        return Math.abs(location.getX() - centerX) > half || Math.abs(location.getZ() - centerZ) > half;
    }
}
