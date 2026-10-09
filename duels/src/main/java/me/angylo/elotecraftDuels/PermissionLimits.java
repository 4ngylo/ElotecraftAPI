package me.angylo.elotecraftDuels;

import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.PermissionAttachmentInfo;

/**
 * Numbers granted by permissions such as {@code duels.party.size.12}, so ranks can raise a config.yml limit: the
 * highest number among the player's granted {@code <prefix>.<n>} nodes wins, never below the config value.
 */
public final class PermissionLimits {

    public static final String PARTY_SIZE = "duels.party.size";
    public static final String CUSTOM_KIT_SLOTS = "duels.kit.custom.slots";
    public static final String RANKED_DAILY = "duels.queue.ranked.limit";

    private PermissionLimits() {
    }

    /**
     * The highest {@code n} of the {@code <prefix>.<n>} nodes {@code permissible} has, capped at {@code max}, or
     * {@code fallback} if none is higher. Nodes whose end is not a number are ignored.
     */
    public static int highest(Permissible permissible, String prefix, int fallback, int max) {
        String start = prefix + ".";
        int best = fallback;
        for (PermissionAttachmentInfo info : permissible.getEffectivePermissions()) {
            String node = info.getPermission();
            if (info.getValue() && node.startsWith(start)) {
                try {
                    best = Math.max(best, Math.min(Integer.parseInt(node.substring(start.length())), max));
                } catch (NumberFormatException ignored) {
                    // Not a number, e.g. duels.party.size.abc: not a limit.
                }
            }
        }
        return best;
    }
}
