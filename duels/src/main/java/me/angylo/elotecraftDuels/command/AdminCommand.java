package me.angylo.elotecraftDuels.command;

import me.angylo.elotecraftAPI.command.Args;
import me.angylo.elotecraftAPI.command.CommandBuilder;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Duels;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaPregen;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import me.angylo.elotecraftDuels.arena.ArenaTemplate;
import me.angylo.elotecraftDuels.hook.WorldEditHook;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.BoundingBox;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Pattern;

/** {@code /duels}: arena and kit setup, reload and stopping duels. Admin permissions only. */
public final class AdminCommand {

    private static final List<String> NUMBERS = List.of("1", "2");
    private static final List<String> ADD_REMOVE = List.of("add", "remove");
    private static final String NONE = "none";
    private static final String ANY = "any";
    private static final String CLEAR = "clear";
    /** Schematic files /duels arena import reads from plugins/ElotecraftDuels/schematics; no paths. */
    private static final Pattern SCHEMATIC = Pattern.compile("[a-z0-9_-]{1,64}\\.schem");
    /** Height the lowest layer of an imported schematic is pasted at. */
    private static final int IMPORT_Y = 64;
    private static final int MAX_WIDTH = 256;

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
                        .sub("delete", null, (sender, args) -> withEditableArena(sender, args, this::deleteArena), arenaNames)
                        .playerSub("setspawn", null, (player, args) -> setPoint(player, args, true), arenaNumber)
                        .playerSub("setcorner", null, (player, args) -> setPoint(player, args, false), arenaNumber)
                        .playerSub("setspectator", null, (player, args) -> withEditableArena(player, args, (arena, rest) ->
                                inWorld(player, arena, () -> save(player, arenas.update(arena.withSpectator(Arena.Position.of(player.getLocation()))),
                                        "admin.arena.spectator-set", arenaTags(arena)))), arenaNames)
                        .playerSub("setbox", null, (player, args) -> withEditableArena(player, args, (arena, rest) ->
                                inWorld(player, arena, () -> setBox(player, arena))), arenaNames)
                        .sub("import", null, this::importSchematic)
                        .playerSub("setcenter", null, (player, args) -> withEditableArena(player, args, (arena, rest) ->
                                inWorld(player, arena, () -> setCenter(player, arena))), arenaNames)
                        .playerSub("seticon", null, (player, args) -> withEditableArena(player, args, (arena, rest) ->
                                heldIcon(player).ifPresent(icon -> save(player, arenas.update(arena.withIcon(icon)),
                                        "admin.arena.icon-set", arenaTags(arena)))), arenaNames)
                        .sub("setname", null, (sender, args) -> withEditableArena(sender, args, (arena, rest) -> rename(sender, rest,
                                text -> save(sender, arenas.update(arena.withDisplayName(text)), "admin.arena.name-set",
                                        arenaTags(arena.withDisplayName(text))))), arenaNames)
                        .sub("category", null, (sender, args) -> withEditableArena(sender, args, this::category),
                                (sender, args) -> args.length == 1 ? Args.filter(arenas.names(), args)
                                        : args.length == 2 ? Args.filter(ADD_REMOVE, args)
                                        : args.length == 3 ? Args.filter(categories(), args) : List.of())
                        .sub("buildlimit", null, (sender, args) -> withEditableArena(sender, args, this::buildLimit),
                                (sender, args) -> args.length == 1 ? Args.filter(arenas.names(), args)
                                        : args.length == 2 ? Args.filter(List.of(NONE), args) : List.of())
                        .sub("pregen", null, (sender, args) -> withArena(sender, args, this::pregen),
                                (sender, args) -> args.length == 1 ? Args.filter(arenas.names(), args)
                                        : args.length == 2 ? Args.filter(List.of(CLEAR), args) : List.of())
                        .sub("toggle", null, (sender, args) -> withArena(sender, args, (arena, rest) -> {
                            Arena toggled = arena.withEnabled(!arena.enabled());
                            save(sender, arenas.update(toggled), toggled.enabled() ? "admin.arena.enabled" : "admin.arena.disabled",
                                    arenaTags(toggled));
                        }), arenaNames)
                        .sub("info", null, (sender, args) -> withArena(sender, args, (arena, rest) -> info(sender, arena)), arenaNames)
                        .playerSub("tp", null, (player, args) -> withArena(player, args, (arena, rest) -> teleport(player, arena)), arenaNames)
                        .sub("list", null, (sender, args) -> listArenas(sender))
                        .sub("snapshot", null, (sender, args) -> withEditableArena(sender, args, (arena, rest) -> snapshot(sender, arena)), arenaNames)
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
                        .sub("arenas", null, (sender, args) -> withKit(sender, args, (kit, rest) -> kitArenas(sender, kit, rest)),
                                (sender, args) -> args.length == 1 ? Args.filter(kits.names(), args)
                                        : Args.filter(args.length == 2 ? withAny(categories()) : categories(), args))
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
        if (duels.matches().isArenaInUse(arena.name()) || duels.pregen().isBusy(arena.name())) {
            messages.send(sender, "admin.arena.in-use", arenaTags(arena));
            return;
        }
        if (!arenas.copiesOf(arena.name()).isEmpty()) {
            messages.send(sender, "admin.arena.has-copies", arenaTags(arena));
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
        withEditableArena(player, args, (arena, rest) -> {
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
                Placeholder.unparsed("categories", arena.categories().isEmpty() ? NONE : String.join(", ", new TreeSet<>(arena.categories()))),
                Placeholder.unparsed("build-limit", arena.buildLimit() == null ? NONE : String.valueOf(arena.buildLimit())),
                Placeholder.component("copy", arena.copy() == null ? Component.empty()
                        : messages.get(sender, "admin.arena.copy-of", Placeholder.unparsed("source", arena.copy().source()))),
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

    /** Both corners from the player's WorldEdit selection. */
    private void setBox(Player player, Arena arena) {
        Optional<WorldEditHook> worldEdit = duels.worldEdit();
        if (worldEdit.isEmpty()) {
            messages.send(player, "admin.arena.no-worldedit");
            return;
        }
        Optional<BoundingBox> box = worldEdit.get().selection(player);
        if (box.isEmpty()) {
            messages.send(player, "admin.arena.no-selection");
            return;
        }
        BoundingBox b = box.get();
        Arena changed = arena.withCorner(1, new Arena.Position(b.getMinX(), b.getMinY(), b.getMinZ(), 0, 0))
                .withCorner(2, new Arena.Position(b.getMaxX() - 1, b.getMaxY() - 1, b.getMaxZ() - 1, 0, 0));
        save(player, arenas.update(changed), "admin.arena.box-set", arenaTags(arena));
    }

    /** {@code import <name> <file.schem>}: a new arena from a schematic, pasted at a free place in the arenas world. */
    private void importSchematic(CommandSender sender, String[] args) {
        String name = Args.get(args, 0).toLowerCase(Locale.ROOT);
        String file = Args.get(args, 1);
        Optional<WorldEditHook> worldEdit = duels.worldEdit();
        if (!ArenaRegistry.validName(name)) {
            messages.send(sender, "admin.invalid-name");
        } else if (arenas.get(name).isPresent()) {
            messages.send(sender, "admin.arena.exists", Placeholder.unparsed("id", name));
        } else if (!SCHEMATIC.matcher(file).matches()) {
            messages.send(sender, "admin.arena.bad-schematic");
        } else if (worldEdit.isEmpty()) {
            messages.send(sender, "admin.arena.no-worldedit");
        } else if (!duels.pregen().isAvailable()) {
            messages.send(sender, "admin.arena.no-arenas-world", Placeholder.unparsed("world", duels.pregen().worldName()));
        } else {
            TagResolver[] tags = {Placeholder.unparsed("id", name), Placeholder.unparsed("arena", name), Placeholder.unparsed("file", file)};
            messages.send(sender, "admin.arena.importing", tags);
            Path path = duels.plugin().getDataFolder().toPath().resolve("schematics").resolve(file);
            worldEdit.get().load(path).whenComplete((copy, error) -> Tasks.sync(duels.plugin(), () -> {
                if (error != null) {
                    importFailed(sender, name, error, tags);
                    return;
                }
                pasteImport(sender, worldEdit.get(), copy, name, tags);
            }));
        }
    }

    private void pasteImport(CommandSender sender, WorldEditHook worldEdit, WorldEditHook.Copy copy, String name, TagResolver[] tags) {
        int[] size = copy.size();
        if (size[0] > MAX_WIDTH || size[2] > MAX_WIDTH || (long) size[0] * size[1] * size[2] > ArenaTemplate.MAX_BLOCKS) {
            messages.send(sender, "admin.arena.too-big", tags);
            return;
        }
        int[] at = duels.pregen().freePlace(size[0], size[1], size[2], IMPORT_Y);
        World world = duels.pregen().world();
        worldEdit.paste(copy, world, at[0], at[1], at[2]).whenComplete((ignored, error) -> Tasks.sync(duels.plugin(), () -> {
            if (error != null) {
                importFailed(sender, name, error, tags);
                return;
            }
            if (arenas.get(name).isPresent()) {
                messages.send(sender, "admin.arena.exists", Placeholder.unparsed("id", name));
                return;
            }
            // Both changes happen here on the main thread; the second save writes the first one too.
            arenas.create(name, new Location(world, at[0], at[1], at[2]));
            CompletableFuture<Void> saving = arenas.update(arenas.get(name).orElseThrow()
                    .withCorner(1, new Arena.Position(at[0], at[1], at[2], 0, 0))
                    .withCorner(2, new Arena.Position(at[0] + size[0] - 1, at[1] + size[1] - 1, at[2] + size[2] - 1, 0, 0)));
            save(sender, saving, "admin.arena.imported", with(tags,
                    Placeholder.unparsed("world", world.getName()), Placeholder.unparsed("x", String.valueOf(at[0])),
                    Placeholder.unparsed("y", String.valueOf(at[1])), Placeholder.unparsed("z", String.valueOf(at[2]))));
        }));
    }

    private void importFailed(CommandSender sender, String name, Throwable error, TagResolver[] tags) {
        if (rootCause(error) instanceof NoSuchFileException) {
            messages.send(sender, "admin.arena.no-schematic", tags);
            return;
        }
        duels.plugin().getLogger().log(Level.WARNING, "Could not import arena " + name, error);
        messages.send(sender, "admin.arena.import-failed", tags);
    }

    /** The center must be inside the arena's corners. */
    private void setCenter(Player player, Arena arena) {
        if (!arena.inBox(player.getLocation())) {
            messages.send(player, "admin.arena.center-outside", arenaTags(arena));
            return;
        }
        save(player, arenas.update(arena.withCenter(Arena.Position.of(player.getLocation()))), "admin.arena.center-set", arenaTags(arena));
    }

    /** {@code category <arena> add|remove <category>} */
    private void category(CommandSender sender, Arena arena, String[] rest) {
        String action = Args.get(rest, 0).toLowerCase(Locale.ROOT);
        String category = Args.get(rest, 1).toLowerCase(Locale.ROOT);
        if (!ADD_REMOVE.contains(action) || !ArenaRegistry.validName(category)) {
            messages.send(sender, "admin.arena.category-usage");
            return;
        }
        boolean add = action.equals("add");
        Set<String> changed = new HashSet<>(arena.categories());
        TagResolver[] tags = with(arenaTags(arena), Placeholder.unparsed("category", category));
        if (add ? !changed.add(category) : !changed.remove(category)) {
            messages.send(sender, add ? "admin.arena.category-present" : "admin.arena.category-missing", tags);
            return;
        }
        save(sender, arenas.update(arena.withCategories(changed)), add ? "admin.arena.category-added" : "admin.arena.category-removed", tags);
    }

    /** {@code buildlimit <arena> <y|none>}: the Y must be inside the arena's box. */
    private void buildLimit(CommandSender sender, Arena arena, String[] rest) {
        String raw = Args.get(rest, 0);
        if (raw.equalsIgnoreCase(NONE)) {
            save(sender, arenas.update(arena.withBuildLimit(null)), "admin.arena.build-limit-cleared", arenaTags(arena));
            return;
        }
        if (arena.corner1() == null || arena.corner2() == null) {
            messages.send(sender, "admin.arena.not-ready", with(arenaTags(arena), Placeholder.component("problems", status(sender, arena))));
            return;
        }
        int min = (int) arena.bounds().getMinY();
        int max = (int) arena.bounds().getMaxY() - 1;
        OptionalInt y = Args.integer(raw, min, max);
        if (y.isEmpty()) {
            messages.send(sender, "admin.arena.build-limit-outside", with(arenaTags(arena),
                    Placeholder.unparsed("min", String.valueOf(min)), Placeholder.unparsed("max", String.valueOf(max))));
            return;
        }
        save(sender, arenas.update(arena.withBuildLimit(y.getAsInt())), "admin.arena.build-limit-set",
                with(arenaTags(arena), Placeholder.unparsed("y", String.valueOf(y.getAsInt()))));
    }

    /** {@code pregen <arena> <count>} pastes copies in the arenas world; {@code pregen <arena> clear} removes them. */
    private void pregen(CommandSender sender, Arena base, String[] rest) {
        ArenaPregen pregen = duels.pregen();
        if (base.copy() != null) {
            messages.send(sender, "admin.arena.is-copy", with(arenaTags(base), Placeholder.unparsed("source", base.copy().source())));
        } else if (!pregen.isAvailable()) {
            messages.send(sender, "admin.arena.no-arenas-world", Placeholder.unparsed("world", pregen.worldName()));
        } else if (pregen.isBusy(base.name())) {
            messages.send(sender, "admin.arena.in-use", arenaTags(base));
        } else if (Args.get(rest, 0).equalsIgnoreCase(CLEAR)) {
            clearCopies(sender, base);
        } else {
            makeCopies(sender, base, Args.get(rest, 0));
        }
    }

    private void clearCopies(CommandSender sender, Arena base) {
        List<Arena> copies = arenas.copiesOf(base.name());
        if (copies.isEmpty()) {
            messages.send(sender, "admin.arena.no-copies", arenaTags(base));
            return;
        }
        if (copies.stream().anyMatch(copy -> duels.matches().isArenaInUse(copy.name()))) {
            messages.send(sender, "admin.arena.in-use", arenaTags(base));
            return;
        }
        int removed = duels.pregen().clear(base);
        messages.send(sender, "admin.arena.copies-cleared", with(arenaTags(base), Placeholder.unparsed("count", String.valueOf(removed))));
    }

    private void makeCopies(CommandSender sender, Arena base, String rawCount) {
        int max = duels.settings().maxCopies();
        OptionalInt count = Args.integer(rawCount, 1, max);
        if (count.isEmpty()) {
            messages.send(sender, "admin.arena.pregen-count", Placeholder.unparsed("max", String.valueOf(max)));
            return;
        }
        if (!arenas.copiesOf(base.name()).isEmpty()) {
            messages.send(sender, "admin.arena.has-copies", arenaTags(base));
            return;
        }
        if (!base.isReady()) {
            messages.send(sender, "admin.arena.not-ready", with(arenaTags(base), Placeholder.component("problems", status(sender, base))));
            return;
        }
        for (int number = 1; number <= count.getAsInt(); number++) {
            String name = ArenaPregen.copyName(base.name(), number);
            if (!ArenaRegistry.validName(name)) {
                messages.send(sender, "admin.arena.name-too-long", arenaTags(base));
                return;
            }
            if (arenas.get(name).isPresent()) {
                messages.send(sender, "admin.arena.exists", Placeholder.unparsed("id", name));
                return;
            }
        }
        TagResolver[] tags = with(arenaTags(base), Placeholder.unparsed("count", String.valueOf(count.getAsInt())),
                Placeholder.unparsed("world", duels.pregen().worldName()));
        messages.send(sender, "admin.arena.pregen-started", tags);
        if (!duels.pregen().keepsBlockData()) {
            messages.send(sender, "admin.arena.pregen-blocks-only");
        }
        duels.pregen().pregen(base, count.getAsInt(),
                        done -> messages.send(sender, "admin.arena.pregen-progress", with(tags, Placeholder.unparsed("done", String.valueOf(done)))))
                .whenComplete((made, error) -> Tasks.sync(duels.plugin(), () -> {
                    if (error == null) {
                        messages.send(sender, "admin.arena.pregen-done", tags);
                    } else if (rootCause(error) instanceof NoSuchFileException) {
                        messages.send(sender, "admin.arena.no-snapshot", arenaTags(base));
                    } else {
                        duels.plugin().getLogger().log(Level.WARNING, "Could not make copies of arena " + base.name(), error);
                        messages.send(sender, "admin.arena.pregen-failed", arenaTags(base));
                    }
                }));
    }

    /** {@code arenas <kit> <category...>|any}: which arena categories the kit's duels use. */
    private void kitArenas(CommandSender sender, Kit kit, String[] rest) {
        if (rest.length == 0) {
            messages.send(sender, "admin.kit.arenas-usage");
            return;
        }
        if (rest.length == 1 && rest[0].equalsIgnoreCase(ANY)) {
            save(sender, kits.update(kit.withArenaCategories(Set.of())), "admin.kit.arenas-any", kitTags(kit));
            return;
        }
        Set<String> categories = new TreeSet<>();
        for (String raw : rest) {
            String category = raw.toLowerCase(Locale.ROOT);
            if (!ArenaRegistry.validName(category)) {
                messages.send(sender, "admin.kit.arenas-usage");
                return;
            }
            categories.add(category);
        }
        save(sender, kits.update(kit.withArenaCategories(categories)), "admin.kit.arenas-set",
                with(kitTags(kit), Placeholder.unparsed("categories", String.join(", ", categories))));
    }

    /** Every category some arena has, sorted; for tab completion. */
    private List<String> categories() {
        return arenas.all().stream().flatMap(arena -> arena.categories().stream()).distinct().sorted().toList();
    }

    private static List<String> withAny(List<String> categories) {
        List<String> all = new ArrayList<>(categories);
        all.add(ANY);
        return all;
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

    /** Like {@link #withArena}, refusing pregen copies: they change with the arena they copy. */
    private void withEditableArena(CommandSender sender, String[] args, BiConsumer<Arena, String[]> action) {
        withArena(sender, args, (arena, rest) -> {
            if (arena.copy() != null) {
                messages.send(sender, "admin.arena.is-copy", with(arenaTags(arena), Placeholder.unparsed("source", arena.copy().source())));
                return;
            }
            action.accept(arena, rest);
        });
    }

    private void withEditableArena(CommandSender sender, String[] args, ArenaAction action) {
        withEditableArena(sender, args, (arena, rest) -> action.run(sender, arena, rest));
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
