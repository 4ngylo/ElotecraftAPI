package me.angylo.elotecraftAPI.util;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MiniMessage strings from the plugin's {@code messages.yml}, e.g. {@code no-permission: "<prefix><red>No permission"}.
 * <ul>
 *   <li><b>Prefix:</b> a {@code prefix} key is available as {@code <prefix>} in every message.</li>
 *   <li><b>Languages:</b> {@code messages_<lang>.yml} (e.g. {@code messages_es.yml}) is used for players whose
 *       client language matches; missing keys fall back to {@code messages.yml}. Files in the data folder are
 *       found automatically; bundled ones are listed in the constructor so they are copied out
 *       (see {@link LocalizedFile}).</li>
 *   <li><b>PlaceholderAPI:</b> when it is installed, {@code %placeholders%} are filled in for players.
 *       Their values are inserted as text, so they cannot add tags.</li>
 * </ul>
 * Fill your own placeholders with {@code Placeholder.unparsed("player", name)} for player-supplied values.
 * Messages without placeholders are parsed once and cached until {@link #reload()}.
 */
public final class Messages {

    private static final String FILE = "messages.yml";
    private static final String DEFAULT = LocalizedFile.DEFAULT;

    private final Plugin plugin;
    private final LocalizedFile files;
    private final Set<String> warnedKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, Component> parsed = new ConcurrentHashMap<>();

    /**
     * @param bundledLanguages languages with a {@code messages_<lang>.yml} inside the plugin jar, e.g. {@code "es"}
     * @throws IllegalArgumentException if a language is not 2–3 lowercase letters
     */
    public Messages(Plugin plugin, String... bundledLanguages) {
        this.plugin = plugin;
        this.files = new LocalizedFile(plugin, FILE, bundledLanguages);
    }

    /** The message in the default language, or the key itself (with one warning logged) if it is missing. */
    public Component get(String key, TagResolver... resolvers) {
        return render(null, DEFAULT, key, resolvers);
    }

    /** The message in {@code viewer}'s language with PlaceholderAPI applied, if {@code viewer} is a player. */
    public Component get(Audience viewer, String key, TagResolver... resolvers) {
        if (viewer instanceof Player player) {
            return render(player, files.languageOf(player), key, resolvers);
        }
        return get(key, resolvers);
    }

    /**
     * A message of several lines, one component each, in {@code viewer}'s language: a YAML list or a
     * string with line breaks. Each line is parsed on its own with the same placeholders, e.g. for sidebars.
     */
    public List<Component> lines(Audience viewer, String key, TagResolver... resolvers) {
        Player player = viewer instanceof Player found ? found : null;
        String language = files.languageOf(viewer);
        List<String> raw = linesOf(files.get(language), key);
        if (raw == null) {
            return List.of(missing(key));
        }
        return raw.stream().map(line -> parse(player, language, line, resolvers)).toList();
    }

    /** Whether {@code key} is in the default language file (or its bundled copy). */
    public boolean has(String key) {
        return raw(DEFAULT, key) != null;
    }

    public void send(Audience audience, String key, TagResolver... resolvers) {
        audience.sendMessage(get(audience, key, resolvers));
    }

    /** Reloads every language file and finds new ones in the data folder. */
    public boolean reload() {
        warnedKeys.clear();
        boolean ok = files.reload();
        parsed.clear();
        return ok;
    }

    private Component render(Player player, String language, String key, TagResolver[] resolvers) {
        String cacheKey = language + ':' + key;
        if (resolvers.length == 0) {
            Component cached = parsed.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        String raw = raw(language, key);
        if (raw == null) {
            return missing(key);
        }
        Component message = parse(player, language, raw, resolvers);
        if (resolvers.length == 0 && !usesPlaceholders(player, raw)) {
            parsed.put(cacheKey, message);
        }
        return message;
    }

    private Component parse(Player player, String language, String raw, TagResolver[] resolvers) {
        TagResolver.Builder tags = TagResolver.builder().resolvers(resolvers);
        String prefix = raw(language, "prefix");
        if (prefix != null) {
            tags.resolver(Placeholder.parsed("prefix", prefix));
        }
        String text = usesPlaceholders(player, raw) ? PlaceholderHook.apply(player, raw, tags) : raw;
        return Text.mm(text, tags.build());
    }

    private static boolean usesPlaceholders(Player player, String raw) {
        return player != null && PlaceholderHook.TOKEN.matcher(raw).find() && PlaceholderHook.enabled();
    }

    private Component missing(String key) {
        if (warnedKeys.add(key)) {
            plugin.getLogger().warning("Missing message '" + key + "' in " + FILE);
        }
        return Component.text(key);
    }

    private static List<String> linesOf(YamlConfiguration config, String key) {
        if (config.isList(key)) {
            return config.getStringList(key);
        }
        String value = config.getString(key);
        return value == null ? null : List.of(value.split("\n", -1));
    }

    private String raw(String language, String key) {
        return files.get(language).getString(key);
    }
}
