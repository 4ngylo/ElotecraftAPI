package me.angylo.elotecraftAPI.command;

import me.angylo.elotecraftAPI.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/** Routing for one command or nested group, built from a {@link CommandBuilder}. */
final class CommandNode {

    static final Component UNAVAILABLE = Text.mm("<red>This command is currently unavailable.");

    record Leaf(String permission, boolean playersOnly, BiConsumer<CommandSender, String[]> handler,
                BiFunction<CommandSender, String[], List<String>> suggester) {
    }

    private final String permission;
    private final Map<String, Object> subs;
    private final BiConsumer<CommandSender, String[]> rootHandler;
    private final BiFunction<CommandSender, String[], List<String>> rootSuggester;
    private final Component noPermission;
    private final Component playerOnly;

    CommandNode(CommandBuilder builder) {
        this.permission = builder.permission;
        Map<String, Object> built = new LinkedHashMap<>();
        builder.subs.forEach((name, sub) -> built.put(name, sub instanceof CommandBuilder group ? new CommandNode(group) : sub));
        this.subs = Collections.unmodifiableMap(built);
        this.rootHandler = builder.rootHandler;
        this.rootSuggester = builder.rootSuggester;
        this.noPermission = Text.mm(builder.noPermissionMessage);
        this.playerOnly = Text.mm(builder.playerOnlyMessage);
    }

    void execute(CommandSender sender, String label, String[] args) {
        if (!allows(sender, permission)) {
            sender.sendMessage(noPermission);
            return;
        }
        if (rootHandler != null && (args.length == 0 || subs.isEmpty())) {
            rootHandler.accept(sender, args);
            return;
        }
        Object sub = args.length == 0 ? null : subs.get(args[0].toLowerCase(Locale.ROOT));
        String[] rest = args.length == 0 ? args : Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case null -> sendUsage(sender, label);
            case CommandNode group -> group.execute(sender, label + " " + args[0].toLowerCase(Locale.ROOT), rest);
            case Leaf leaf when !allows(sender, leaf.permission()) -> sender.sendMessage(noPermission);
            case Leaf leaf when leaf.playersOnly() && !(sender instanceof Player) -> sender.sendMessage(playerOnly);
            case Leaf leaf -> leaf.handler().accept(sender, rest);
            default -> throw new IllegalStateException("Unknown subcommand type: " + sub);
        }
    }

    List<String> complete(CommandSender sender, String[] args) {
        if (!allows(sender, permission)) {
            return List.of();
        }
        if (subs.isEmpty()) {
            return rootSuggester == null ? List.of() : rootSuggester.apply(sender, args);
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            return subs.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(first) && visible(sender, entry.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();
        }
        Object sub = subs.get(first);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        return switch (sub) {
            case CommandNode group -> group.complete(sender, rest);
            case Leaf leaf when leaf.suggester() != null && allows(sender, leaf.permission()) -> leaf.suggester().apply(sender, rest);
            case null, default -> List.of();
        };
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(Text.mm("<yellow>Usage:"));
        subs.forEach((subName, sub) -> {
            if (visible(sender, sub)) {
                sender.sendMessage(Text.mm("<gray>/<label> <sub>",
                        Placeholder.unparsed("label", label), Placeholder.unparsed("sub", subName)));
            }
        });
    }

    private static boolean visible(CommandSender sender, Object sub) {
        return switch (sub) {
            case CommandNode group -> allows(sender, group.permission);
            case Leaf leaf -> allows(sender, leaf.permission());
            default -> false;
        };
    }

    private static boolean allows(CommandSender sender, String permission) {
        return permission == null || sender.hasPermission(permission);
    }
}
