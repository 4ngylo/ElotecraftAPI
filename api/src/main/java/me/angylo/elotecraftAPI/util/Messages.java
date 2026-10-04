package me.angylo.elotecraftAPI.util;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MiniMessage strings from the plugin's {@code messages.yml}, e.g. {@code no-permission: "<red>No permission"}.
 * Fill placeholders with {@code Placeholder.unparsed("player", name)} for player-supplied values.
 * Messages without placeholders are parsed once and cached until {@link #reload()}.
 */
public final class Messages {

    private final Plugin plugin;
    private final ConfigFile file;
    private final Set<String> warnedKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, Component> parsed = new ConcurrentHashMap<>();

    public Messages(Plugin plugin) {
        this.plugin = plugin;
        this.file = new ConfigFile(plugin, "messages.yml");
    }

    /** The parsed message, or the key itself (with one warning logged) if it is missing. */
    public Component get(String key, TagResolver... resolvers) {
        if (resolvers.length == 0) {
            Component cached = parsed.get(key);
            if (cached != null) {
                return cached;
            }
        }
        String raw = file.get().getString(key);
        if (raw == null) {
            if (warnedKeys.add(key)) {
                plugin.getLogger().warning("Missing message '" + key + "' in messages.yml");
            }
            return Component.text(key);
        }
        Component message = Text.mm(raw, resolvers);
        if (resolvers.length == 0) {
            parsed.put(key, message);
        }
        return message;
    }

    public void send(Audience audience, String key, TagResolver... resolvers) {
        audience.sendMessage(get(key, resolvers));
    }

    public boolean reload() {
        warnedKeys.clear();
        boolean ok = file.reload();
        parsed.clear();
        return ok;
    }
}
