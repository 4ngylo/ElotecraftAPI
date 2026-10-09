package me.angylo.elotecraftDuels.kit;

import me.angylo.elotecraftDuels.Settings;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A per-kit game rule, stored under {@code rules} in kits.yml. Most are flags, the others whole numbers from 0 to
 * their {@link #max()}. Unset, a rule takes config.yml {@code rules.kit-defaults}, and the built-in default here when
 * that is missing or invalid; an unset number with no default leaves the game as it is (vanilla).
 */
public enum KitRule {

    /** Fighters may place blocks, and break the ones placed during the duel. */
    BUILD("build", false),
    /** false for knockback-only fights such as Sumo: hits never hurt, falling off the arena loses. */
    DAMAGE("damage", true),
    HUNGER("hunger", false),
    NATURAL_REGENERATION("natural-regeneration", true),
    FRIENDLY_FIRE("friendly-fire", false),
    VOID_ELIMINATES("void-eliminates", true),
    /** Fighters leaving the arena's box by the sides or the top go back to their spawn; off for kits played over the void. */
    ARENA_BOUNDS("arena-bounds", true),
    FALL_DAMAGE("fall-damage", true),
    FIRE_DAMAGE("fire-damage", true),
    EXPLOSION_DAMAGE("explosion-damage", true),
    SELF_DAMAGE("self-damage", true),
    ITEM_DURABILITY("item-durability", true),
    HIT_DELAY("hit-delay", true),
    ARROW_PICKUP("arrow-pickup", true),
    CRAFTING("crafting", true),
    /** Drinking and throwing potions (splash and lingering). */
    POTIONS("potions", true),
    /** Fighters drop items with Q and pick items up inside their arena. */
    ITEM_DROPS("item-drops", false),
    /** Blocks broken in a build duel drop their item. */
    BLOCK_DROPS("block-drops", false),
    /** A knocked-out fighter's inventory drops where they fell. */
    DEATH_DROPS("death-drops", false),
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
    SATURATION("saturation", false),
    /** Placed TNT is lit at once (build kits). */
    AUTO_IGNITE_TNT("auto-ignite-tnt", false);

    public static final int MAX_SECONDS = 60;
    public static final int MAX_HEALTH_POINTS = 200;
    public static final int MAX_PERCENT = 500;
    public static final int MAX_HITS = 1000;
    public static final int MAX_ROUNDS = 10;

    private final String key;
    /** The built-in default: a Boolean for a flag, null (vanilla) for a number. */
    private final Boolean builtIn;
    private final int max;
    private final boolean seconds;

    KitRule(String key, boolean builtIn) {
        this.key = key;
        this.builtIn = builtIn;
        this.max = 0;
        this.seconds = false;
    }

    KitRule(String key, int max, boolean seconds) {
        this.key = key;
        this.builtIn = null;
        this.max = max;
        this.seconds = seconds;
    }

    public String key() {
        return key;
    }

    public boolean isFlag() {
        return builtIn != null;
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

    /** The built-in default: a Boolean for a flag, null (vanilla) for a number. */
    public Object builtIn() {
        return builtIn;
    }

    /** The value of an unset flag: config.yml {@code rules.kit-defaults}. */
    public boolean defaultFlag(Settings settings) {
        if (!isFlag()) {
            throw new IllegalStateException(key + " is not a flag");
        }
        return settings.kitDefaults().get(this) instanceof Boolean value ? value : builtIn;
    }

    /** The value of an unset number: config.yml {@code rules.kit-defaults}, or empty for vanilla (and for a flag). */
    public OptionalInt defaultNumber(Settings settings) {
        return settings.kitDefaults().get(this) instanceof Integer value ? OptionalInt.of(value) : OptionalInt.empty();
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
