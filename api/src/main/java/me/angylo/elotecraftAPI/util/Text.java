package me.angylo.elotecraftAPI.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Text conversion shortcuts over Adventure.
 */
public final class Text {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private Text() {
    }

    /**
     * Parses a MiniMessage string. Pass player-supplied values through
     * {@code Placeholder.unparsed(...)}, never by concatenating them into {@code input},
     * or players can inject tags such as click events.
     */
    public static Component mm(String input, TagResolver... resolvers) {
        return MINI_MESSAGE.deserialize(input, resolvers);
    }

    /** Strips all formatting and returns the raw text. */
    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
