package me.angylo.elotecraftAPI.command;

import me.angylo.elotecraftAPI.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * Builds and registers a Bukkit command at runtime; no {@code plugin.yml} entry needed.
 * <pre>{@code
 * CommandBuilder.create("shop")
 *         .permission("elotecraft.shop")
 *         .sub("open", null, (sender, args) -> ...)
 *         .playerSub("buy", "elotecraft.shop.buy", (player, args) -> ...)
 *         .register(plugin);
 * }</pre>
 * Handlers receive the arguments after the subcommand name. A {@code null} permission means none.
 */
public final class CommandBuilder {

    private final String name;
    private final Map<String, Sub> subs = new LinkedHashMap<>();
    private List<String> aliases = List.of();
    private String description = "";
    private String permission;
    private BiConsumer<CommandSender, String[]> rootHandler;
    private BiFunction<CommandSender, String[], List<String>> rootSuggester;
    private String noPermissionMessage = "<red>You do not have permission to do that.";
    private String playerOnlyMessage = "<red>Only players can use this command.";

    private CommandBuilder(String name) {
        this.name = validName(name);
    }

    /** @throws IllegalArgumentException if {@code name} is blank or contains spaces, colons or uppercase */
    public static CommandBuilder create(String name) {
        return new CommandBuilder(name);
    }

    public CommandBuilder aliases(String... aliases) {
        this.aliases = Arrays.stream(aliases).map(CommandBuilder::validName).toList();
        return this;
    }

    public CommandBuilder description(String description) {
        this.description = description;
        return this;
    }

    /** Required for the whole command; players without it do not see it in tab completion. */
    public CommandBuilder permission(String permission) {
        this.permission = permission;
        return this;
    }

    /**
     * Runs for {@code /name} with no arguments, or for every call if no subcommands are added.
     * Without it, {@code /name} shows usage.
     */
    public CommandBuilder executes(BiConsumer<CommandSender, String[]> handler) {
        return executes(handler, null);
    }

    public CommandBuilder executes(BiConsumer<CommandSender, String[]> handler,
                                   BiFunction<CommandSender, String[], List<String>> suggester) {
        this.rootHandler = handler;
        this.rootSuggester = suggester;
        return this;
    }

    public CommandBuilder sub(String name, String permission, BiConsumer<CommandSender, String[]> handler) {
        return sub(name, permission, handler, null);
    }

    public CommandBuilder sub(String name, String permission, BiConsumer<CommandSender, String[]> handler,
                              BiFunction<CommandSender, String[], List<String>> suggester) {
        return addSub(name, new Sub(permission, false, handler, suggester));
    }

    /** Subcommand only players can run; the console gets an error message. */
    public CommandBuilder playerSub(String name, String permission, BiConsumer<Player, String[]> handler) {
        return playerSub(name, permission, handler, null);
    }

    public CommandBuilder playerSub(String name, String permission, BiConsumer<Player, String[]> handler,
                                    BiFunction<CommandSender, String[], List<String>> suggester) {
        return addSub(name, new Sub(permission, true, (sender, args) -> handler.accept((Player) sender, args), suggester));
    }

    /** MiniMessage overrides for the built-in error messages. */
    public CommandBuilder messages(String noPermission, String playerOnly) {
        this.noPermissionMessage = noPermission;
        this.playerOnlyMessage = playerOnly;
        return this;
    }

    /** Registers under {@code /name} and {@code /plugin:name}. Call from {@code onEnable}. */
    public Command register(Plugin plugin) {
        BuiltCommand command = new BuiltCommand(plugin, this);
        if (!Bukkit.getCommandMap().register(plugin.getName().toLowerCase(Locale.ROOT), command)) {
            plugin.getLogger().warning("/" + name + " is taken by another plugin; use /"
                    + plugin.getName().toLowerCase(Locale.ROOT) + ":" + name);
        }
        Bukkit.getOnlinePlayers().forEach(Player::updateCommands);
        return command;
    }

    private CommandBuilder addSub(String name, Sub sub) {
        if (subs.putIfAbsent(validName(name), sub) != null) {
            throw new IllegalArgumentException("Duplicate subcommand: " + name);
        }
        return this;
    }

    private static String validName(String name) {
        if (name == null || name.isBlank() || !name.equals(name.toLowerCase(Locale.ROOT))
                || name.contains(" ") || name.contains(":")) {
            throw new IllegalArgumentException("Invalid command name: '" + name + "'");
        }
        return name;
    }

    private record Sub(String permission, boolean playersOnly, BiConsumer<CommandSender, String[]> handler,
                       BiFunction<CommandSender, String[], List<String>> suggester) {

        boolean allows(CommandSender sender) {
            return permission == null || sender.hasPermission(permission);
        }
    }

    private static final class BuiltCommand extends Command implements PluginIdentifiableCommand {

        private static final Component UNAVAILABLE = Text.mm("<red>This command is currently unavailable.");

        private final Plugin plugin;
        private final Map<String, Sub> subs;
        private final BiConsumer<CommandSender, String[]> rootHandler;
        private final BiFunction<CommandSender, String[], List<String>> rootSuggester;
        private final Component noPermission;
        private final Component playerOnly;

        BuiltCommand(Plugin plugin, CommandBuilder builder) {
            super(builder.name, builder.description, "/" + builder.name, builder.aliases);
            setPermission(builder.permission);
            this.plugin = plugin;
            this.subs = Collections.unmodifiableMap(new LinkedHashMap<>(builder.subs));
            this.rootHandler = builder.rootHandler;
            this.rootSuggester = builder.rootSuggester;
            this.noPermission = Text.mm(builder.noPermissionMessage);
            this.playerOnly = Text.mm(builder.playerOnlyMessage);
        }

        @Override
        public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String @NotNull [] args) {
            if (!plugin.isEnabled()) {
                sender.sendMessage(UNAVAILABLE);
                return true;
            }
            if (!testPermissionSilent(sender)) {
                sender.sendMessage(noPermission);
                return true;
            }
            if (rootHandler != null && (args.length == 0 || subs.isEmpty())) {
                rootHandler.accept(sender, args);
                return true;
            }
            Sub sub = args.length == 0 ? null : subs.get(args[0].toLowerCase(Locale.ROOT));
            if (sub == null) {
                sendUsage(sender, label);
            } else if (!sub.allows(sender)) {
                sender.sendMessage(noPermission);
            } else if (sub.playersOnly() && !(sender instanceof Player)) {
                sender.sendMessage(playerOnly);
            } else {
                sub.handler().accept(sender, Arrays.copyOfRange(args, 1, args.length));
            }
            return true;
        }

        @Override
        public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String @NotNull [] args) {
            if (!plugin.isEnabled() || !testPermissionSilent(sender)) {
                return List.of();
            }
            if (subs.isEmpty()) {
                return rootSuggester == null ? List.of() : rootSuggester.apply(sender, args);
            }
            String first = args[0].toLowerCase(Locale.ROOT);
            if (args.length == 1) {
                return subs.entrySet().stream()
                        .filter(entry -> entry.getKey().startsWith(first) && entry.getValue().allows(sender))
                        .map(Map.Entry::getKey)
                        .toList();
            }
            Sub sub = subs.get(first);
            if (sub == null || sub.suggester() == null || !sub.allows(sender)) {
                return List.of();
            }
            return sub.suggester().apply(sender, Arrays.copyOfRange(args, 1, args.length));
        }

        @Override
        public @NotNull Plugin getPlugin() {
            return plugin;
        }

        private void sendUsage(CommandSender sender, String label) {
            sender.sendMessage(Text.mm("<yellow>Usage:"));
            subs.forEach((subName, sub) -> {
                if (sub.allows(sender)) {
                    sender.sendMessage(Text.mm("<gray>/<label> <sub>",
                            Placeholder.unparsed("label", label), Placeholder.unparsed("sub", subName)));
                }
            });
        }
    }
}
