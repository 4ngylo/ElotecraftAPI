package me.angylo.elotecraftDuels.command;

import me.angylo.elotecraftAPI.command.Args;
import me.angylo.elotecraftAPI.command.CommandBuilder;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Duels;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import me.angylo.elotecraftDuels.arena.ArenaTemplate;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.nio.file.NoSuchFileException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.logging.Level;

/** {@code /duels}: arena and kit setup, reload and stopping duels. Admin permissions only. */
public final class AdminCommand {

    private static final List<String> NUMBERS = List.of("1", "2");

    private final Duels duels;
    private final Messages messages;
    private final ArenaRegistry arenas;
    private final KitRegistry kits;
    /** Arenas whose snapshot is being written, so two cannot write the same file at once. */
    private final Set<String> snapshotting = new HashSet<>();

    public AdminCommand(Duels duels) {
        this.duels = duels;
        this.messages = duels.messages();
        this.arenas = duels.arenas();
        this.kits = duels.kits();
    }

    public void register() {
        BiFunction<CommandSender, String[], List<String>> arenaNames = (sender, args) -> args.length == 1 ? Args.filter(arenas.names(), args) : List.of();
        BiFunction<CommandSender, String[], List<String>> arenaNumber = (sender, args) ->
                args.length == 1 ? Args.filter(arenas.names(), args) : args.length == 2 ? Args.filter(NUMBERS, args) : List.of();
        BiFunction<CommandSender, String[], List<String>> kitNames = (sender, args) -> args.length == 1 ? Args.filter(kits.names(), args) : List.of();

        CommandBuilder.create("duels")
                .description(Text.plain(messages.get("command.admin-description")))
                .permission("duels.admin")
                .messages(sender -> messages.get(sender, "command.no-permission"),
                        sender -> messages.get(sender, "command.player-only"))
                .executes((sender, args) -> messages.send(sender, "command.admin-help"))
                .sub(CommandBuilder.create("arena").permission("duels.admin.arena")
                        .executes((sender, args) -> messages.send(sender, "command.arena-help"))
                        .playerSub("create", null, this::createArena)
                        .sub("delete", null, (sender, args) -> withArena(sender, args, this::deleteArena), arenaNames)
                        .playerSub("setspawn", null, (player, args) -> setPoint(player, args, true), arenaNumber)
                        .playerSub("setcorner", null, (player, args) -> setPoint(player, args, false), arenaNumber)
                        .playerSub("setspectator", null, (player, args) -> withArena(player, args, (arena, rest) ->
                                inWorld(player, arena, () -> save(player, arenas.update(arena.withSpectator(Arena.Position.of(player.getLocation()))),
                                        "admin.arena.spectator-set", arenaTags(arena)))), arenaNames)
                        .playerSub("seticon", null, (player, args) -> withArena(player, args, (arena, rest) ->
                                heldIcon(player).ifPresent(icon -> save(player, arenas.update(arena.withIcon(icon)),
                                        "admin.arena.icon-set", arenaTags(arena)))), arenaNames)
                        .sub("setname", null, (sender, args) -> withArena(sender, args, (arena, rest) -> rename(sender, rest,
                                text -> save(sender, arenas.update(arena.withDisplayName(text)), "admin.arena.name-set",
                                        arenaTags(arena.withDisplayName(text))))), arenaNames)
                        .sub("toggle", null, (sender, args) -> withArena(sender, args, (arena, rest) -> {
                            Arena toggled = arena.withEnabled(!arena.enabled());
                            save(sender, arenas.update(toggled), toggled.enabled() ? "admin.arena.enabled" : "admin.arena.disabled",
                                    arenaTags(toggled));
                        }), arenaNames)
                        .sub("info", null, (sender, args) -> withArena(sender, args, (arena, rest) -> info(sender, arena)), arenaNames)
                        .playerSub("tp", null, (player, args) -> withArena(player, args, (arena, rest) -> teleport(player, arena)), arenaNames)
                        .sub("list", null, (sender, args) -> listArenas(sender))
                        .sub("snapshot", null, (sender, args) -> withArena(sender, args, (arena, rest) -> snapshot(sender, arena)), arenaNames)
                        .sub("reset", null, (sender, args) -> withArena(sender, args, (arena, rest) -> reset(sender, arena)), arenaNames))
                .sub(CommandBuilder.create("kit").permission("duels.admin.kit")
                        .executes((sender, args) -> messages.send(sender, "command.kit-help"))
                        .playerSub("create", null, this::createKit)
                        .playerSub("save", null, (player, args) -> withKit(player, args, (kit, rest) -> {
                            if (isEmpty(player.getInventory())) {
                                messages.send(player, "admin.kit.empty-inventory");
                                return;
                            }
                            save(player, kits.update(kit.withItems(player.getInventory())), "admin.kit.saved", kitTags(kit));
                        }), kitNames)
                        .playerSub("load", null, (player, args) -> withKit(player, args, (kit, rest) -> {
                            if (duels.matches().isBusy(player) || !isEmpty(player.getInventory())) {
                                messages.send(player, "admin.kit.inventory-not-empty");
                                return;
                            }
                            kit.apply(player);
                            messages.send(player, "admin.kit.loaded", kitTags(kit));
                        }), kitNames)
                        .sub("delete", null, (sender, args) -> withKit(sender, args, (kit, rest) ->
                                save(sender, kits.delete(kit.name()), "admin.kit.deleted", kitTags(kit))), kitNames)
                        .playerSub("seticon", null, (player, args) -> withKit(player, args, (kit, rest) ->
                                heldIcon(player).ifPresent(icon -> save(player, kits.update(kit.withIcon(icon)),
                                        "admin.kit.icon-set", kitTags(kit)))), kitNames)
                        .sub("setname", null, (sender, args) -> withKit(sender, args, (kit, rest) -> rename(sender, rest,
                                text -> save(sender, kits.update(kit.withDisplayName(text)), "admin.kit.name-set",
                                        kitTags(kit.withDisplayName(text))))), kitNames)
                        .sub("setpermission", null, (sender, args) -> withKit(sender, args, (kit, rest) -> setPermission(sender, kit, rest)),
                                (sender, args) -> args.length == 1 ? Args.filter(kits.names(), args)
                                        : args.length == 2 ? Args.filter(List.of("none", "duels.kit." + args[0]), args) : List.of())
                        .sub("build", null, (sender, args) -> withKit(sender, args, (kit, rest) -> {
                            Kit changed = kit.withBuild(!kit.build());
                            save(sender, kits.update(changed), changed.build() ? "admin.kit.build-on" : "admin.kit.build-off", kitTags(kit));
                        }), kitNames)
                        .sub("list", null, (sender, args) -> listKits(sender)))
                .sub("reload", "duels.admin.reload", (sender, args) ->
                        messages.send(sender, duels.reload() ? "admin.reloaded" : "admin.reload-failed"))
                .sub("stop", "duels.admin.stop", this::stop, (sender, args) -> Args.players(args))
                .register(duels.plugin());
    }

    private void createArena(Player player, String[] args) {
        String name = Args.get(args, 0).toLowerCase(Locale.ROOT);
        if (!ArenaRegistry.validName(name)) {
            messages.send(player, "admin.invalid-name");
            return;
        }
        if (arenas.get(name).isPresent()) {
            messages.send(player, "admin.arena.exists", Placeholder.unparsed("id", name));
            return;
        }
        save(player, arenas.create(name, player.getLocation()), "admin.arena.created",
                Placeholder.unparsed("id", name), Placeholder.unparsed("world", player.getWorld().getName()));
    }

    private void deleteArena(CommandSender sender, Arena arena, String[] rest) {
        if (duels.matches().isArenaInUse(arena.name())) {
            messages.send(sender, "admin.arena.in-use", arenaTags(arena));
            return;
        }
        save(sender, arenas.delete(arena.name()), "admin.arena.deleted", arenaTags(arena));
        // A snapshot left behind would be pasted over whatever a new arena of that name stands on.
        ArenaTemplate.delete(duels.plugin(), arena.name()).exceptionally(error -> {
            duels.plugin().getLogger().log(Level.WARNING, "Could not delete the snapshot of arena " + arena.name(), error);
            return false;
        });
    }

    private void setPoint(Player player, String[] args, boolean spawn) {
        withArena(player, args, (arena, rest) -> {
            String number = Args.get(rest, 0);
            if (!NUMBERS.contains(number)) {
                messages.send(player, "admin.use-number");
                return;
            }
            Arena.Position position = Arena.Position.of(player.getLocation());
            Arena changed = spawn ? arena.withSpawn(Integer.parseInt(number), position) : arena.withCorner(Integer.parseInt(number), position);
            inWorld(player, arena, () -> save(player, arenas.update(changed),
                    spawn ? "admin.arena.spawn-set" : "admin.arena.corner-set",
                    with(arenaTags(arena), Placeholder.unparsed("number", number))));
        });
    }

    private void info(CommandSender sender, Arena arena) {
        messages.send(sender, "admin.arena.info", with(arenaTags(arena),
                Placeholder.unparsed("world", arena.world()),
                Placeholder.unparsed("enabled", String.valueOf(arena.enabled())),
                Placeholder.unparsed("in-use", String.valueOf(duels.matches().isArenaInUse(arena.name()))),
                Placeholder.component("status", status(sender, arena))));
    }

    private void teleport(Player player, Arena arena) {
        if (!arena.isReady()) {
            messages.send(player, "admin.arena.not-ready", with(arenaTags(arena), Placeholder.component("problems", status(player, arena))));
            return;
        }
        player.teleportAsync(arena.spectatorSpawn(), TeleportCause.COMMAND);
        messages.send(player, "admin.arena.teleported", arenaTags(arena));
    }

    private void listArenas(CommandSender sender) {
        List<Arena> all = arenas.all();
        if (all.isEmpty()) {
            messages.send(sender, "admin.arena.list-empty");
            return;
        }
        messages.send(sender, "admin.arena.list-header", Placeholder.unparsed("count", String.valueOf(all.size())));
        for (Arena arena : all) {
            messages.send(sender, "admin.arena.list-entry", with(arenaTags(arena), Placeholder.component("status", status(sender, arena))));
        }
    }

    /** Saves the arena's blocks, to rebuild it if a crash cuts a build duel short. */
    private void snapshot(CommandSender sender, Arena arena) {
        if (arena.corner1() == null || arena.corner2() == null || Bukkit.getWorld(arena.world()) == null) {
            messages.send(sender, "admin.arena.not-ready", with(arenaTags(arena), Placeholder.component("problems", status(sender, arena))));
            return;
        }
        if (!ArenaTemplate.fitsLimits(arena)) {
            messages.send(sender, "admin.arena.too-big", arenaTags(arena));
            return;
        }
        if (duels.matches().isArenaInUse(arena.name()) || !snapshotting.add(arena.name())) {
            messages.send(sender, "admin.arena.in-use", arenaTags(arena));
            return;
        }
        boolean wasMarked = arenas.needingReset().contains(arena.name());
        CompletableFuture<Integer> saving = ArenaTemplate.save(duels.plugin(), arena);
        saving.whenComplete((blocks, error) -> Tasks.sync(duels.plugin(), () -> {
            snapshotting.remove(arena.name());
            // The admin vouches that the arena is intact, so a crash mark no longer applies.
            if (error == null) {
                arenas.needsReset(arena.name(), false);
            }
        }));
        save(sender, saving, wasMarked ? "admin.arena.snapshot-fixed" : "admin.arena.snapshot-saved", arenaTags(arena));
    }

    /** Puts every block of the arena back as it was in its snapshot. */
    private void reset(CommandSender sender, Arena arena) {
        if (Bukkit.getWorld(arena.world()) == null) {
            messages.send(sender, "admin.arena.not-ready", with(arenaTags(arena), Placeholder.component("problems", status(sender, arena))));
            return;
        }
        if (duels.matches().isArenaInUse(arena.name())) {
            messages.send(sender, "admin.arena.in-use", arenaTags(arena));
            return;
        }
        messages.send(sender, "admin.arena.reset-started", arenaTags(arena));
        duels.instances().reset(arena).whenComplete((changed, error) -> Tasks.sync(duels.plugin(), () -> {
            if (error == null) {
                messages.send(sender, "admin.arena.reset-done", with(arenaTags(arena), Placeholder.unparsed("blocks", String.valueOf(changed))));
            } else if (rootCause(error) instanceof NoSuchFileException) {
                messages.send(sender, "admin.arena.no-snapshot", arenaTags(arena));
            } else {
                duels.plugin().getLogger().log(Level.WARNING, "Could not reset arena " + arena.name(), error);
                messages.send(sender, "admin.save-failed");
            }
        }));
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private void createKit(Player player, String[] args) {
        String name = Args.get(args, 0).toLowerCase(Locale.ROOT);
        if (!ArenaRegistry.validName(name)) {
            messages.send(player, "admin.invalid-name");
            return;
        }
        if (kits.get(name).isPresent()) {
            messages.send(player, "admin.kit.exists", Placeholder.unparsed("id", name));
            return;
        }
        PlayerInventory inventory = player.getInventory();
        if (isEmpty(inventory)) {
            messages.send(player, "admin.kit.empty-inventory");
            return;
        }
        Material hand = inventory.getItemInMainHand().getType();
        save(player, kits.create(name, hand.isAir() ? Kit.DEFAULT_ICON : hand, inventory), "admin.kit.created",
                Placeholder.unparsed("id", name));
    }

    private void setPermission(CommandSender sender, Kit kit, String[] rest) {
        String permission = Args.get(rest, 0).toLowerCase(Locale.ROOT);
        if (rest.length != 1) {
            messages.send(sender, "admin.kit.invalid-permission");
        } else if (permission.equals("none")) {
            save(sender, kits.update(kit.withPermission(null)), "admin.kit.permission-cleared", kitTags(kit));
        } else if (KitRegistry.validPermission(permission)) {
            save(sender, kits.update(kit.withPermission(permission)), "admin.kit.permission-set",
                    with(kitTags(kit), Placeholder.unparsed("permission", permission)));
        } else {
            messages.send(sender, "admin.kit.invalid-permission");
        }
    }

    private void listKits(CommandSender sender) {
        List<Kit> all = kits.all();
        if (all.isEmpty()) {
            messages.send(sender, "admin.kit.list-empty");
            return;
        }
        messages.send(sender, "admin.kit.list-header", Placeholder.unparsed("count", String.valueOf(all.size())));
        for (Kit kit : all) {
            messages.send(sender, "admin.kit.list-entry", with(kitTags(kit),
                    Placeholder.unparsed("permission", kit.permission() == null ? "" : kit.permission())));
        }
    }

    private void stop(CommandSender sender, String[] args) {
        Optional<Player> target = Args.player(Args.get(args, 0));
        if (target.isEmpty()) {
            messages.send(sender, "general.player-not-found", Placeholder.unparsed("player", Args.get(args, 0)));
            return;
        }
        boolean stopped = duels.matches().stop(target.get());
        messages.send(sender, stopped ? "admin.stopped" : "general.not-dueling", Placeholder.unparsed("player", target.get().getName()));
    }

    /** Runs {@code action} with the arena named by the first argument and the remaining arguments. */
    private void withArena(CommandSender sender, String[] args, BiConsumer<Arena, String[]> action) {
        Optional<Arena> arena = arenas.get(Args.get(args, 0));
        if (arena.isEmpty()) {
            messages.send(sender, "general.arena-not-found", Placeholder.unparsed("arena", Args.get(args, 0)));
            return;
        }
        action.accept(arena.get(), Arrays.copyOfRange(args, 1, args.length));
    }

    private void withArena(CommandSender sender, String[] args, ArenaAction action) {
        withArena(sender, args, (arena, rest) -> action.run(sender, arena, rest));
    }

    @FunctionalInterface
    private interface ArenaAction {
        void run(CommandSender sender, Arena arena, String[] rest);
    }

    private void withKit(CommandSender sender, String[] args, BiConsumer<Kit, String[]> action) {
        Optional<Kit> kit = kits.get(Args.get(args, 0));
        if (kit.isEmpty()) {
            messages.send(sender, "general.kit-not-found", Placeholder.unparsed("kit", Args.get(args, 0)));
            return;
        }
        action.accept(kit.get(), Arrays.copyOfRange(args, 1, args.length));
    }

    /** Points must be in the arena's world. */
    private void inWorld(Player player, Arena arena, Runnable action) {
        if (!player.getWorld().getName().equals(arena.world())) {
            messages.send(player, "admin.arena.wrong-world", with(arenaTags(arena), Placeholder.unparsed("world", arena.world())));
            return;
        }
        action.run();
    }

    /** The held item's material, or empty after telling the player to hold one. */
    private Optional<Material> heldIcon(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.isEmpty()) {
            messages.send(player, "admin.hold-item");
            return Optional.empty();
        }
        return Optional.of(held.getType());
    }

    private void rename(CommandSender sender, String[] rest, Consumer<String> action) {
        String text = Args.join(rest, 0).strip();
        if (text.isEmpty()) {
            messages.send(sender, "admin.invalid-display-name");
            return;
        }
        action.accept(text);
    }

    /** Sends {@code successKey} once the file is written, or a failure message; files are written off the main thread. */
    private void save(CommandSender sender, CompletableFuture<?> saving, String successKey, TagResolver... tags) {
        saving.whenComplete((ignored, error) -> Tasks.sync(duels.plugin(), () -> {
            if (error == null) {
                messages.send(sender, successKey, tags);
                return;
            }
            duels.plugin().getLogger().log(Level.WARNING, "Could not save a duels admin change", error);
            messages.send(sender, "admin.save-failed");
        }));
    }

    /** "ready", or the arena's problems joined with commas. */
    private Component status(CommandSender viewer, Arena arena) {
        List<Arena.Problem> problems = arena.problems();
        if (problems.isEmpty()) {
            return messages.get(viewer, "admin.arena.ready");
        }
        return Component.join(JoinConfiguration.commas(true),
                problems.stream().map(problem -> messages.get(viewer, problem.messageKey())).toList());
    }

    private static boolean isEmpty(PlayerInventory inventory) {
        return Arrays.stream(inventory.getContents()).allMatch(item -> item == null || item.isEmpty());
    }

    private static TagResolver[] arenaTags(Arena arena) {
        return new TagResolver[]{Placeholder.unparsed("id", arena.name()), Placeholder.component("arena", Text.mm(arena.displayName()))};
    }

    private static TagResolver[] kitTags(Kit kit) {
        return new TagResolver[]{Placeholder.unparsed("id", kit.name()), Placeholder.component("kit", Text.mm(kit.displayName()))};
    }

    private static TagResolver[] with(TagResolver[] tags, TagResolver... more) {
        TagResolver[] all = Arrays.copyOf(tags, tags.length + more.length);
        System.arraycopy(more, 0, all, tags.length, more.length);
        return all;
    }
}
