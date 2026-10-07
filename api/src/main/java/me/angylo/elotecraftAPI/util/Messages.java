package me.angylo.elotecraftAPI.util;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MiniMessage strings from the plugin's {@code messages.yml}, e.g. {@code no-permission: "<prefix><red>No permission"}.
 * <ul>
 *   <li><b>Prefix:</b> a {@code prefix} key is available as {@code <prefix>} in every message.</li>
 *   <li><b>Languages:</b> {@code messages_<lang>.yml} (e.g. {@code messages_es.yml}) is used for players whose
 *       client language matches; missing keys fall back to {@code messages.yml}. Files in the data folder are
 *       found automatically; bundled ones are listed in the constructor so they are copied out.</li>
 *   <li><b>PlaceholderAPI:</b> when it is installed, {@code %placeholders%} are filled in for players.
 *       Their values are inserted as text, so they cannot add tags.</li>
 * </ul>
 * Fill your own placeholders with {@code Placeholder.unparsed("player", name)} for player-supplied values.
 * Messages without placeholders are parsed once and cached until {@link #reload()}.
 */
public final class Messages {

    private static final String FILE = "messages.yml";
    private static final String DEFAULT = "";
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}");
    private static final Pattern LANGUAGE_FILE = Pattern.compile("messages_([a-z]{2,3})\\.yml");

    private final Plugin plugin;
    private final Set<String> bundledLanguages;
    private final Map<String, ConfigFile> files = new ConcurrentHashMap<>();
    private final Set<String> warnedKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, Component> parsed = new ConcurrentHashMap<>();

    /**
     * @param bundledLanguages languages with a {@code messages_<lang>.yml} inside the plugin jar, e.g. {@code "es"}
     * @throws IllegalArgumentException if a language is not 2–3 lowercase letters
     */
    public Messages(Plugin plugin, String... bundledLanguages) {
        this.plugin = plugin;
        this.bundledLanguages = Set.copyOf(Arrays.asList(bundledLanguages));
        this.bundledLanguages.forEach(language -> {
            if (!LANGUAGE.matcher(language).matches()) {
                throw new IllegalArgumentException("Invalid language '" + language + "'; use codes like es or pt");
            }
        });
        loadFiles();
    }

    /** The message in the default language, or the key itself (with one warning logged) if it is missing. */
    public Component get(String key, TagResolver... resolvers) {
        return render(null, DEFAULT, key, resolvers);
    }

    /** The message in {@code viewer}'s language with PlaceholderAPI applied, if {@code viewer} is a player. */
    public Component get(Audience viewer, String key, TagResolver... resolvers) {
        if (viewer instanceof Player player) {
            return render(player, languageOf(player), key, resolvers);
        }
        return get(key, resolvers);
    }

    /**
     * A message of several lines, one component each, in {@code viewer}'s language: a YAML list or a
     * string with line breaks. Each line is parsed on its own with the same placeholders, e.g. for sidebars.
     */
    public List<Component> lines(Audience viewer, String key, TagResolver... resolvers) {
        Player player = viewer instanceof Player found ? found : null;
        String language = player == null ? DEFAULT : languageOf(player);
        List<String> raw = rawLines(language, key);
        if (raw == null) {
            return List.of(missing(key));
        }
        return raw.stream().map(line -> parse(player, language, line, resolvers)).toList();
    }

    public void send(Audience audience, String key, TagResolver... resolvers) {
        audience.sendMessage(get(audience, key, resolvers));
    }

    /** Reloads every language file and finds new ones in the data folder. */
    public boolean reload() {
        warnedKeys.clear();
        boolean ok = files.values().stream().map(ConfigFile::reload).reduce(true, Boolean::logicalAnd);
        loadFiles();
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

    private List<String> rawLines(String language, String key) {
        ConfigFile translated = files.get(language);
        List<String> value = translated == null ? null : linesOf(translated.get(), key);
        return value != null ? value : linesOf(files.get(DEFAULT).get(), key);
    }

    private static List<String> linesOf(YamlConfiguration config, String key) {
        if (config.isList(key)) {
            return config.getStringList(key);
        }
        String value = config.getString(key);
        return value == null ? null : List.of(value.split("\n", -1));
    }

    private String raw(String language, String key) {
        ConfigFile translated = files.get(language);
        String value = translated == null ? null : translated.get().getString(key);
        return value != null ? value : files.get(DEFAULT).get().getString(key);
    }

    /** The client's language if a file exists for it, else the default. Client input, so it is validated. */
    private String languageOf(Player player) {
        String language = player.locale().getLanguage().toLowerCase(Locale.ROOT);
        return LANGUAGE.matcher(language).matches() && files.containsKey(language) ? language : DEFAULT;
    }

    private void loadFiles() {
        files.computeIfAbsent(DEFAULT, language -> new ConfigFile(plugin, FILE));
        bundledLanguages.forEach(this::loadLanguage);
        try (DirectoryStream<Path> found = Files.newDirectoryStream(plugin.getDataFolder().toPath(), "messages_*.yml")) {
            for (Path file : found) {
                Matcher name = LANGUAGE_FILE.matcher(file.getFileName().toString());
                if (name.matches()) {
                    loadLanguage(name.group(1));
                }
            }
        } catch (IOException e) {
            if (Files.isDirectory(plugin.getDataFolder().toPath())) {
                plugin.getLogger().log(Level.WARNING, "Could not list language files", e);
            }
        }
    }

    private void loadLanguage(String language) {
        files.computeIfAbsent(language, key -> {
            String name = "messages_" + key + ".yml";
            if (!hasResource(name) && Files.notExists(plugin.getDataFolder().toPath().resolve(name))) {
                plugin.getLogger().warning("Language '" + key + "' has no " + name + " in the jar or data folder");
            }
            return new ConfigFile(plugin, name);
        });
    }

    private boolean hasResource(String name) {
        try (InputStream in = plugin.getResource(name)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }
}
