package me.angylo.elotecraftAPI.util;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MiniMessage strings from the plugin's {@code messages.yml}, e.g. {@code no-permission: "<red>No permission"}.
 * Fill placeholders with {@code Placeholder.unparsed("player", name)} for player-supplied values.
 */
public final class Messages {

    private final Plugin plugin;
    private final ConfigFile file;
    private final Set<String> warnedKeys = ConcurrentHashMap.newKeySet();

    public Messages(Plugin plugin) {
        this.plugin = plugin;
        this.file = new ConfigFile(plugin, "messages.yml");
    }

    /** The parsed message, or the key itself (with one warning logged) if it is missing. */
    public Component get(String key, TagResolver... resolvers) {
        String raw = file.get().getString(key);
        if (raw == null) {
            if (warnedKeys.add(key)) {
                plugin.getLogger().warning("Missing message '" + key + "' in messages.yml");
            }
            return Component.text(key);
        }
        return Text.mm(raw, resolvers);
    }

    public void send(Audience audience, String key, TagResolver... resolvers) {
        audience.sendMessage(get(key, resolvers));
    }

    public boolean reload() {
        warnedKeys.clear();
        return file.reload();
    }
}
