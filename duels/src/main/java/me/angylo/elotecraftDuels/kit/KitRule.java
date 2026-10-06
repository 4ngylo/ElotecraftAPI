package me.angylo.elotecraftDuels.kit;

import me.angylo.elotecraftDuels.Settings;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * A per-kit game rule, stored under {@code rules} in kits.yml. Most are flags: unset, they fall back to
 * config.yml (hunger, regeneration, friendly fire, void) or to true. {@link #PEARL_COOLDOWN} is a number
 * of seconds: unset, the vanilla cooldown is left alone.
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
    PEARL_COOLDOWN("pearl-cooldown", null);

    public static final int MAX_SECONDS = 60;

    private final String key;
    /** Null for a number of seconds. */
    private final Predicate<Settings> flagDefault;

    KitRule(String key, Predicate<Settings> flagDefault) {
        this.key = key;
        this.flagDefault = flagDefault;
    }

    public String key() {
        return key;
    }

    public boolean isFlag() {
        return flagDefault != null;
    }

    /** The value of an unset flag. */
    public boolean defaultFlag(Settings settings) {
        if (!isFlag()) {
            throw new IllegalStateException(key + " is not a flag");
        }
        return flagDefault.test(settings);
    }

    /** Whether {@code value} fits this rule: a Boolean for flags, 0 to {@link #MAX_SECONDS} for seconds. */
    public boolean accepts(Object value) {
        return isFlag() ? value instanceof Boolean : value instanceof Integer seconds && seconds >= 0 && seconds <= MAX_SECONDS;
    }

    /**
     * Reads a typed value: true or false for flags, a whole number of seconds otherwise.
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
