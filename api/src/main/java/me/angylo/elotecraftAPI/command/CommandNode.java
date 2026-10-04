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
import java.util.function.Function;
import java.util.stream.Stream;

/** Routing for one command or nested group, built from a {@link CommandBuilder}. */
final class CommandNode {

    static final Component UNAVAILABLE = Text.mm("<red>This command is currently unavailable.");
    private static final Component NO_PERMISSION = Text.mm("<red>You do not have permission to do that.");
    private static final Component PLAYER_ONLY = Text.mm("<red>Only players can use this command.");

    record Leaf(String permission, boolean playersOnly, BiConsumer<CommandSender, String[]> handler,
                BiFunction<CommandSender, String[], List<String>> suggester) {
    }

    private final String permission;
    private final Map<String, Object> subs;
    private final BiConsumer<CommandSender, String[]> rootHandler;
    private final BiFunction<CommandSender, String[], List<String>> rootSuggester;
    private final Function<CommandSender, Component> noPermission;
    private final Function<CommandSender, Component> playerOnly;

    CommandNode(CommandBuilder builder) {
        this(builder, sender -> NO_PERMISSION, sender -> PLAYER_ONLY);
    }

    private CommandNode(CommandBuilder builder, Function<CommandSender, Component> parentNoPermission,
                        Function<CommandSender, Component> parentPlayerOnly) {
        this.permission = builder.permission;
        this.noPermission = builder.noPermissionMessage != null ? builder.noPermissionMessage : parentNoPermission;
        this.playerOnly = builder.playerOnlyMessage != null ? builder.playerOnlyMessage : parentPlayerOnly;
        Map<String, Object> built = new LinkedHashMap<>();
        builder.subs.forEach((name, sub) -> built.put(name,
                sub instanceof CommandBuilder group ? new CommandNode(group, noPermission, playerOnly) : sub));
        this.subs = Collections.unmodifiableMap(built);
        this.rootHandler = builder.rootHandler;
        this.rootSuggester = builder.rootSuggester;
    }

    void execute(CommandSender sender, String label, String[] args) {
        if (!allows(sender, permission)) {
            sender.sendMessage(noPermission.apply(sender));
            return;
        }
        Object sub = args.length == 0 ? null : subs.get(args[0].toLowerCase(Locale.ROOT));
        if (sub == null && rootHandler != null) {
            rootHandler.accept(sender, args);
            return;
        }
        String[] rest = args.length == 0 ? args : Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case null -> sendUsage(sender, label);
            case CommandNode group -> group.execute(sender, label + " " + args[0].toLowerCase(Locale.ROOT), rest);
            case Leaf leaf when !allows(sender, leaf.permission()) -> sender.sendMessage(noPermission.apply(sender));
            case Leaf leaf when leaf.playersOnly() && !(sender instanceof Player) -> sender.sendMessage(playerOnly.apply(sender));
            case Leaf leaf -> leaf.handler().accept(sender, rest);
            default -> throw new IllegalStateException("Unknown subcommand type: " + sub);
        }
    }

    List<String> complete(CommandSender sender, String[] args) {
        if (!allows(sender, permission)) {
            return List.of();
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            Stream<String> names = subs.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(first) && visible(sender, entry.getValue()))
                    .map(Map.Entry::getKey);
            return Stream.concat(names, rootSuggestions(sender, args).stream()).distinct().toList();
        }
        Object sub = subs.get(first);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        return switch (sub) {
            case null -> rootSuggestions(sender, args);
            case CommandNode group -> group.complete(sender, rest);
            case Leaf leaf when leaf.suggester() != null && allows(sender, leaf.permission()) -> leaf.suggester().apply(sender, rest);
            default -> List.of();
        };
    }

    private List<String> rootSuggestions(CommandSender sender, String[] args) {
        return rootSuggester == null ? List.of() : rootSuggester.apply(sender, args);
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
