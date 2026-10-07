package me.angylo.elotecraftAPI.command;

import me.angylo.elotecraftAPI.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Builds and registers a Bukkit command at runtime; no {@code plugin.yml} entry needed.
 * <pre>{@code
 * CommandBuilder.create("shop")
 *         .permission("elotecraft.shop")
 *         .sub("open", null, (sender, args) -> ...)
 *         .playerSub("buy", "elotecraft.shop.buy", (player, args) -> ...)
 *         .sub(CommandBuilder.create("admin").permission("elotecraft.shop.admin")   // /shop admin reset
 *                 .sub("reset", null, (sender, args) -> ...))
 *         .register(plugin);
 * }</pre>
 * Handlers receive the arguments after the subcommand name. A {@code null} permission means none.
 * Commands are unregistered automatically when their plugin disables.
 */
public final class CommandBuilder {

    private static final Map<Plugin, List<Command>> REGISTERED = new ConcurrentHashMap<>();

    final String name;
    final Map<String, Object> subs = new LinkedHashMap<>();
    String permission;
    BiConsumer<CommandSender, String[]> rootHandler;
    BiFunction<CommandSender, String[], List<String>> rootSuggester;
    /** {@code null} until {@link #messages} is called: a nested group then uses its parent's. */
    Function<CommandSender, Component> noPermissionMessage;
    Function<CommandSender, Component> playerOnlyMessage;
    private List<String> aliases = List.of();
    private String description = "";

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

    /** Required for the whole command (or nested group); players without it do not see it in tab completion. */
    public CommandBuilder permission(String permission) {
        this.permission = permission;
        return this;
    }

    /**
     * Runs whenever no subcommand matches: for {@code /name} with no arguments, when the first argument
     * is not a subcommand (e.g. a player in {@code /duel <player>}; subcommand names win), or for every
     * call if no subcommands are added. It receives all arguments. Without it, those calls show usage.
     */
    public CommandBuilder executes(BiConsumer<CommandSender, String[]> handler) {
        return executes(handler, null);
    }

    /**
     * Like {@link #executes(BiConsumer)}; {@code suggester} completes the first argument next to the
     * subcommand names, and later arguments when the first is not a subcommand.
     */
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
        return addSub(name, new CommandNode.Leaf(permission, false, handler, suggester));
    }

    /** Subcommand only players can run; the console gets an error message. */
    public CommandBuilder playerSub(String name, String permission, BiConsumer<Player, String[]> handler) {
        return playerSub(name, permission, handler, null);
    }

    public CommandBuilder playerSub(String name, String permission, BiConsumer<Player, String[]> handler,
                                    BiFunction<CommandSender, String[], List<String>> suggester) {
        return addSub(name, new CommandNode.Leaf(permission, true,
                (sender, args) -> handler.accept((Player) sender, args), suggester));
    }

    /**
     * Nested group named after {@code group}, with its own permission and subcommands,
     * e.g. {@code /shop admin reset}. The group's aliases and description are ignored.
     */
    public CommandBuilder sub(CommandBuilder group) {
        return addSub(group.name, group);
    }

    /** MiniMessage overrides for the built-in error messages; nested groups without their own use these. */
    public CommandBuilder messages(String noPermission, String playerOnly) {
        Component noPermissionText = Text.mm(noPermission);
        Component playerOnlyText = Text.mm(playerOnly);
        return messages(sender -> noPermissionText, sender -> playerOnlyText);
    }

    /**
     * Built-in error messages made for each sender when sent, e.g. from a {@code Messages} file so they follow
     * the player's language and reloads. Nested groups without their own use these.
     */
    public CommandBuilder messages(Function<CommandSender, Component> noPermission, Function<CommandSender, Component> playerOnly) {
        this.noPermissionMessage = noPermission;
        this.playerOnlyMessage = playerOnly;
        return this;
    }

    /** Registers under {@code /name} and {@code /plugin:name}. Call from {@code onEnable}. */
    public Command register(Plugin plugin) {
        BuiltCommand command = new BuiltCommand(plugin, this);
        String prefix = plugin.getName().toLowerCase(Locale.ROOT);
        if (!Bukkit.getCommandMap().register(prefix, command)) {
            plugin.getLogger().warning("/" + name + " is taken by another plugin; use /" + prefix + ":" + name);
        }
        REGISTERED.computeIfAbsent(plugin, key -> new CopyOnWriteArrayList<>()).add(command);
        Bukkit.getOnlinePlayers().forEach(Player::updateCommands);
        return command;
    }

    /** Removes a command returned by {@link #register(Plugin)} from every label it was registered under. */
    public static void unregister(Command command) {
        CommandMap map = Bukkit.getCommandMap();
        command.unregister(map);
        // Paper's known-commands map forwards to the Brigadier tree; its values() view ignores removals, so remove by label.
        Map<String, Command> known = map.getKnownCommands();
        List<String> labels = known.entrySet().stream()
                .filter(entry -> entry.getValue() == command)
                .map(Map.Entry::getKey)
                .toList();
        labels.forEach(known::remove);
        if (command instanceof PluginIdentifiableCommand owned) {
            List<Command> commands = REGISTERED.get(owned.getPlugin());
            if (commands != null) {
                commands.remove(command);
            }
        }
        Bukkit.getOnlinePlayers().forEach(Player::updateCommands);
    }

    /** Removes every command {@code plugin} registered here; called automatically when it disables. */
    public static void unregisterAll(Plugin plugin) {
        List<Command> commands = REGISTERED.remove(plugin);
        if (commands != null) {
            new ArrayList<>(commands).forEach(CommandBuilder::unregister);
        }
    }

    private CommandBuilder addSub(String name, Object sub) {
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

    private static final class BuiltCommand extends Command implements PluginIdentifiableCommand {

        private final Plugin plugin;
        private final CommandNode root;

        BuiltCommand(Plugin plugin, CommandBuilder builder) {
            super(builder.name, builder.description, "/" + builder.name, builder.aliases);
            setPermission(builder.permission);
            this.plugin = plugin;
            this.root = new CommandNode(builder);
        }

        @Override
        public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String @NotNull [] args) {
            if (!plugin.isEnabled()) {
                sender.sendMessage(CommandNode.UNAVAILABLE);
                return true;
            }
            root.execute(sender, label, args);
            return true;
        }

        @Override
        public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String @NotNull [] args) {
            return plugin.isEnabled() ? root.complete(sender, args) : List.of();
        }

        @Override
        public @NotNull Plugin getPlugin() {
            return plugin;
        }
    }
}
