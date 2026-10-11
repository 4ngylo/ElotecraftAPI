package me.angylo.elotecraftAPI.util;

import net.kyori.adventure.audience.Audience;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@link ConfigFile} with translations, e.g. {@code new LocalizedFile(plugin, "menus.yml", "es")}:
 * {@code menus_<lang>.yml} (e.g. {@code menus_es.yml}) is used for players whose client language matches, and keys
 * it lacks come from {@code menus.yml}, so a translation only needs the texts. Files in the data folder are found
 * automatically; bundled ones are listed in the constructor so they are copied out.
 * <p>
 * Translated views are read-only copies made on load and {@link #reload()}; changes to {@link #get()} reach them
 * only after a reload.
 */
public final class LocalizedFile {

    static final String DEFAULT = "";
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}");

    private final Plugin plugin;
    private final String stem;
    private final Pattern languageFile;
    private final Set<String> bundledLanguages;
    private final ConfigFile base;
    private final Map<String, ConfigFile> translations = new ConcurrentHashMap<>();
    private volatile Map<String, YamlConfiguration> views = Map.of();

    /**
     * @param name             the default file, e.g. {@code menus.yml}
     * @param bundledLanguages languages with a {@code <name>_<lang>.yml} inside the plugin jar, e.g. {@code "es"}
     * @throws IllegalArgumentException if {@code name} does not end in {@code .yml} or a language is not 2–3 lowercase letters
     */
    public LocalizedFile(Plugin plugin, String name, String... bundledLanguages) {
        if (!name.endsWith(".yml")) {
            throw new IllegalArgumentException("Localized file '" + name + "' must end in .yml");
        }
        this.plugin = plugin;
        this.stem = name.substring(0, name.length() - ".yml".length());
        this.languageFile = Pattern.compile(Pattern.quote(stem) + "_([a-z]{2,3})\\.yml");
        this.bundledLanguages = Set.copyOf(Arrays.asList(bundledLanguages));
        this.bundledLanguages.forEach(language -> {
            if (!LANGUAGE.matcher(language).matches()) {
                throw new IllegalArgumentException("Invalid language '" + language + "'; use codes like es or pt");
            }
        });
        this.base = new ConfigFile(plugin, name);
        loadLanguages();
    }

    /** The default language's file, live. */
    public YamlConfiguration get() {
        return base.get();
    }

    /** The file in {@code viewer}'s language if {@code viewer} is a player with a translation, else the default one. */
    public YamlConfiguration get(Audience viewer) {
        return get(languageOf(viewer));
    }

    /** Reloads every file and finds new translations in the data folder. */
    public boolean reload() {
        boolean ok = base.reload();
        for (ConfigFile translation : translations.values()) {
            ok &= translation.reload();
        }
        loadLanguages();
        return ok;
    }

    YamlConfiguration get(String language) {
        return language.equals(DEFAULT) ? base.get() : views.getOrDefault(language, base.get());
    }

    /** The client's language if a translation exists for it, else {@link #DEFAULT}. Client input, so it is validated. */
    String languageOf(Audience viewer) {
        if (!(viewer instanceof Player player)) {
            return DEFAULT;
        }
        String language = player.locale().getLanguage().toLowerCase(Locale.ROOT);
        return LANGUAGE.matcher(language).matches() && views.containsKey(language) ? language : DEFAULT;
    }

    private void loadLanguages() {
        bundledLanguages.forEach(this::loadLanguage);
        Path folder = plugin.getDataFolder().toPath();
        try (DirectoryStream<Path> found = Files.newDirectoryStream(folder, stem + "_*.yml")) {
            for (Path file : found) {
                Matcher name = languageFile.matcher(file.getFileName().toString());
                if (name.matches()) {
                    loadLanguage(name.group(1));
                }
            }
        } catch (IOException e) {
            if (Files.isDirectory(folder)) {
                plugin.getLogger().log(Level.WARNING, "Could not list the translations of " + stem + ".yml", e);
            }
        }
        Map<String, YamlConfiguration> fresh = new HashMap<>();
        translations.forEach((language, translation) -> fresh.put(language, view(translation.get())));
        views = Map.copyOf(fresh);
    }

    private void loadLanguage(String language) {
        translations.computeIfAbsent(language, key -> {
            String name = stem + "_" + key + ".yml";
            if (!hasResource(name) && Files.notExists(plugin.getDataFolder().toPath().resolve(name))) {
                plugin.getLogger().warning("Language '" + key + "' has no " + name + " in the jar or data folder");
            }
            return new ConfigFile(plugin, name);
        });
    }

    /** A copy of the default file with the translation's values over it, keeping the default file's key order. */
    private YamlConfiguration view(YamlConfiguration translation) {
        YamlConfiguration view = new YamlConfiguration();
        copyInto(view, base.get());
        copyInto(view, translation);
        // ConfigFile always sets the jar's copy (maybe empty) as defaults.
        view.setDefaults(base.get().getDefaults());
        return view;
    }

    /** Sections come before their keys, and an existing section is kept, so values from both files merge. */
    private static void copyInto(YamlConfiguration target, ConfigurationSection source) {
        source.getValues(true).forEach((path, value) -> {
            if (!(value instanceof ConfigurationSection)) {
                target.set(path, value);
            } else if (!target.isConfigurationSection(path)) {
                target.createSection(path);
            }
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
