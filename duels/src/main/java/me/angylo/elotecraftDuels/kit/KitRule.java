package me.angylo.elotecraftDuels.kit;

import me.angylo.elotecraftDuels.Settings;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * A per-kit game rule, stored under {@code rules} in kits.yml. Most are flags: unset, they fall back to
 * config.yml (hunger, regeneration, friendly fire, void), to false (drops) or to true. The others are whole
 * numbers from 0 to their {@link #max()}: unset, the game is left as it is.
 */
public enum KitRule {

    HUNGER("hunger", Settings::hunger),
    NATURAL_REGENERATION("natural-regeneration", Settings::naturalRegeneration),
    FRIENDLY_FIRE("friendly-fire", Settings::partyFriendlyFire),
    VOID_ELIMINATES("void-eliminates", Settings::voidEliminates),
    FALL_DAMAGE("fall-damage", settings -> true),
    FIRE_DAMAGE("fire-damage", settings -> true),
    EXPLOSION_DAMAGE("explosion-damage", settings -> true),
    SELF_DAMAGE("self-damage", settings -> true),
    ITEM_DURABILITY("item-durability", settings -> true),
    HIT_DELAY("hit-delay", settings -> true),
    ARROW_PICKUP("arrow-pickup", settings -> true),
    CRAFTING("crafting", settings -> true),
    /** Drinking and throwing potions (splash and lingering). */
    POTIONS("potions", settings -> true),
    /** Fighters drop items with Q and pick items up inside their arena. */
    ITEM_DROPS("item-drops", settings -> false),
    /** Blocks broken in a build duel drop their item. */
    BLOCK_DROPS("block-drops", settings -> false),
    /** A knocked-out fighter's inventory drops where they fell. */
    DEATH_DROPS("death-drops", settings -> false),
    /** Seconds between ender pearls. */
    PEARL_COOLDOWN("pearl-cooldown", KitRule.MAX_SECONDS, true),
    /** Boxing: a fighter hit this many times by opponents is knocked out, so in a duel the first to land them wins; 0 is off. */
    HITS_TO_WIN("hits-to-win", KitRule.MAX_HITS, false),
    /** Duels only: the first fighter to win this many rounds wins; 0 and 1 are a single round. */
    ROUNDS_TO_WIN("rounds-to-win", KitRule.MAX_ROUNDS, false),
    /** Fighters' maximum health in health points (20 is vanilla's ten hearts); 0 is off. */
    MAX_HEALTH("max-health", KitRule.MAX_HEALTH_POINTS, false),
    /** Percent of the damage opponents deal (50 halves it, 200 doubles it); 0 is off. */
    DAMAGE_MULTIPLIER("damage-multiplier", KitRule.MAX_PERCENT, false),
    /** Food and saturation stay full, so health comes back fast: an endless saturation effect. */
    SATURATION("saturation", settings -> false),
    /** Placed TNT is lit at once (build kits). */
    AUTO_IGNITE_TNT("auto-ignite-tnt", settings -> false);

    public static final int MAX_SECONDS = 60;
    public static final int MAX_HEALTH_POINTS = 200;
    public static final int MAX_PERCENT = 500;
    public static final int MAX_HITS = 1000;
    public static final int MAX_ROUNDS = 10;

    private final String key;
    /** Null for a number. */
    private final Predicate<Settings> flagDefault;
    private final int max;
    private final boolean seconds;

    KitRule(String key, Predicate<Settings> flagDefault) {
        this.key = key;
        this.flagDefault = flagDefault;
        this.max = 0;
        this.seconds = false;
    }

    KitRule(String key, int max, boolean seconds) {
        this.key = key;
        this.flagDefault = null;
        this.max = max;
        this.seconds = seconds;
    }

    public String key() {
        return key;
    }

    public boolean isFlag() {
        return flagDefault != null;
    }

    /** Whether this number is a number of seconds. */
    public boolean isSeconds() {
        return seconds;
    }

    /** The highest value of a number rule. */
    public int max() {
        return max;
    }

    /** A value of this number rule for admins: {@code 15s} for seconds, {@code 15} otherwise. */
    public String format(int value) {
        return seconds ? value + "s" : String.valueOf(value);
    }

    /** The value of an unset flag. */
    public boolean defaultFlag(Settings settings) {
        if (!isFlag()) {
            throw new IllegalStateException(key + " is not a flag");
        }
        return flagDefault.test(settings);
    }

    /** Whether {@code value} fits this rule: a Boolean for flags, 0 to {@link #max()} for numbers. */
    public boolean accepts(Object value) {
        return isFlag() ? value instanceof Boolean : value instanceof Integer number && number >= 0 && number <= max;
    }

    /**
     * Reads a typed value: true or false for flags, a whole number otherwise.
     *
     * @throws IllegalArgumentException if {@code raw} does not fit this rule
     */
    public Object parse(String raw) {
        String value = raw.strip().toLowerCase(Locale.ROOT);
        Object parsed;
        if (isFlag()) {
            parsed = value.equals("true") ? Boolean.TRUE : value.equals("false") ? Boolean.FALSE : null;
        } else {
            try {
                parsed = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                parsed = null;
            }
        }
        if (!accepts(parsed)) {
            throw new IllegalArgumentException("Invalid value for kit rule " + key + ": " + raw);
        }
        return parsed;
    }

    public static Optional<KitRule> byKey(String key) {
        String wanted = key.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(rule -> rule.key.equals(wanted)).findFirst();
    }

    public static List<String> keys() {
        return Arrays.stream(values()).map(KitRule::key).toList();
    }
}
