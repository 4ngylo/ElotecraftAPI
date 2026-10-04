package me.angylo.elotecraftAPI.util;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional PlaceholderAPI support for {@link Messages}. */
final class PlaceholderHook {

    static final Pattern TOKEN = Pattern.compile("%[^%\\s]+%");

    private PlaceholderHook() {
    }

    static boolean enabled() {
        return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    static String apply(Player player, String raw, TagResolver.Builder resolvers) {
        return replace(raw, token -> Papi.resolve(player, token), resolvers);
    }

    /**
     * Replaces each known {@code %placeholder%} with a {@code <papi_N>} tag whose value is inserted as
     * text (legacy {@code §} colors kept), so a value can never add MiniMessage tags such as click events.
     * Unknown placeholders, which PlaceholderAPI returns unchanged, are left as they are.
     */
    static String replace(String raw, UnaryOperator<String> lookup, TagResolver.Builder resolvers) {
        Matcher matcher = TOKEN.matcher(raw);
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (matcher.find()) {
            String token = matcher.group();
            String value = lookup.apply(token);
            if (value == null || value.equals(token)) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(token));
                continue;
            }
            String tag = "papi_" + index++;
            resolvers.resolver(Placeholder.component(tag, LegacyComponentSerializer.legacySection().deserialize(value)));
            matcher.appendReplacement(out, "<" + tag + ">");
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Separate class so PlaceholderAPI is only loaded when the plugin is present. */
    private static final class Papi {
        static String resolve(Player player, String token) {
            return PlaceholderAPI.setPlaceholders(player, token);
        }
    }
}
