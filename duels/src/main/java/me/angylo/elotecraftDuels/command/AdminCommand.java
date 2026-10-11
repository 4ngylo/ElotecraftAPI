package me.angylo.elotecraftDuels.command;

import me.angylo.elotecraftAPI.command.Args;
import me.angylo.elotecraftAPI.command.CommandBuilder;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Duels;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaPool;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import me.angylo.elotecraftDuels.arena.ArenaTemplate;
import me.angylo.elotecraftDuels.hook.WorldEditHook;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.menu.ArenaAdminMenu;
import me.angylo.elotecraftDuels.menu.HubMenu;
import me.angylo.elotecraftDuels.menu.KitAdminMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
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
import java.util.stream.Stream;

/** {@code /duels}: arena setup, reload and stopping duels, with kit setup in {@link KitAdminCommand}. Needs {@code duels.staff} (granted by {@code duels.admin}), and each part its own permission. */
public final class AdminCommand {

    private static final List<String> NUMBERS = List.of("1", "2");
    private static final int BED_REACH = 5;
    private static final List<String> ADD_REMOVE = List.of("add", "remove");
    private static final String NONE = "none";
    private static final String CLEAR = "clear";
    /** Schematic files /duels arena import reads from plugins/ElotecraftDuels/schematics; no paths. */
    private static final Pattern SCHEMATIC = Pattern.compile("[a-z0-9_-]{1,64}\\.schem");
    /** Height the lowest layer of an imported schematic is pasted at. */
    private static final int IMPORT_Y = 64;
    private static final int MAX_WIDTH = 256;

    private final Duels duels;
    private final Messages messages;
    private final ArenaRegistry arenas;
    private final ArenaAdminMenu arenaMenu;
    private final KitAdminMenu kitMenu;
    private final HubMenu hubMenu;
    /** Arenas whose snapshot is being written, so two cannot write the same file at once. */
    private final Set<String> snapshotting = new HashSet<>();

    public AdminCommand(Duels duels, ArenaAdminMenu arenaMenu, KitAdminMenu kitMenu, HubMenu hubMenu) {
        this.duels = duels;
        this.messages = duels.messages();
        this.arenas = duels.arenas();
        this.arenaMenu = arenaMenu;
        this.kitMenu = kitMenu;
        this.hubMenu = hubMenu;
    }

    public void register() {
        BiFunction<CommandSender, String[], List<String>> arenaNames = (sender, args) -> args.length == 1 ? Args.filter(arenas.names(), args) : List.of();
        BiFunction<CommandSender, String[], List<String>> arenaNumber = (sender, args) ->
                args.length == 1 ? Args.filter(arenas.names(), args) : args.length == 2 ? Args.filter(NUMBERS, args) : List.of();

        CommandBuilder.create("duels")
                .description(Text.plain(messages.get("command.admin-description")))
                .permission("duels.staff")
                .messages(sender -> messages.get(sender, "command.no-permission"),
                        sender -> messages.get(sender, "command.player-only"))
                .executes(this::hubOrHelp)
                .sub("help", null, (sender, args) -> messages.send(sender, "command.admin-help"))
                .sub(CommandBuilder.create("arena").permission("duels.admin.arena")
                        .executes(menu(messages, "command.arena-help", arenaMenu::openList,
                                (player, args) -> withArena(player, args, (arena, rest) -> arenaMenu.openSettings(player, arena.name()))), arenaNames)
                        .sub("help", null, (sender, args) -> messages.send(sender, "command.arena-help"))
                        .playerSub("create", null, this::createArena)
                        .sub("delete", null, (sender, args) -> withArena(sender, args, this::deleteArena), arenaNames)
                        .playerSub("setspawn", null, (player, args) -> setPoint(player, args, true), arenaNumber)
                        .playerSub("setcorner", null, (player, args) -> setPoint(player, args, false), arenaNumber)
                        .playerSub("setgoal", null, (player, args) -> setModePoint(player, args, false), arenaNumber)
                        .playerSub("setbed", null, (player, args) -> setModePoint(player, args, true), arenaNumber)
                        .playerSub("setspectator", null, (player, args) -> withArena(player, args, (arena, rest) ->
                                inWorld(player, arena, () -> save(player, arenas.update(arena.withSpectator(Arena.Position.of(player.getLocation()))),
                                        "admin.arena.spectator-set", arenaTags(arena)))), arenaNames)
                        .playerSub("setbox", null, (player, args) -> withArena(player, args, (arena, rest) ->
                                inWorld(player, arena, () -> setBox(player, arena))), arenaNames)
                        .sub("import", null, this::importSchematic)
                        .playerSub("addspawn", null, (player, args) -> withArena(player, args, (arena, rest) ->
                                inWorld(player, arena, () -> addSpawn(player, arena))), arenaNames)
                        .sub("clearspawns", null, (sender, args) -> withArena(sender, args, (arena, rest) ->
                                save(sender, arenas.update(arena.withExtraSpawns(List.of())), "admin.arena.spawns-cleared", arenaTags(arena))), arenaNames)
                        .playerSub("setcenter", null, (player, args) -> withArena(player, args, (arena, rest) ->
                                inWorld(player, arena, () -> setCenter(player, arena))), arenaNames)
                        .playerSub("seticon", null, (player, args) -> withArena(player, args, (arena, rest) ->
                                heldIcon(player).ifPresent(icon -> save(player, arenas.update(arena.withIcon(icon)),
                                        "admin.arena.icon-set", arenaTags(arena)))), arenaNames)
                        .sub("setname", null, (sender, args) -> withArena(sender, args, (arena, rest) -> rename(sender, rest,
                                text -> save(sender, arenas.update(arena.withDisplayName(text)), "admin.arena.name-set",
                                        arenaTags(arena.withDisplayName(text))))), arenaNames)
                        .sub("category", null, (sender, args) -> withArena(sender, args, this::category),
                                (sender, args) -> args.length == 1 ? Args.filter(arenas.names(), args)
                                        : args.length == 2 ? Args.filter(ADD_REMOVE, args)
                                        : args.length == 3 ? Args.filter(categories(), args) : List.of())
                        .sub("buildlimit", null, (sender, args) -> withArena(sender, args, this::buildLimit),
                                (sender, args) -> args.length == 1 ? Args.filter(arenas.names(), args)
                                        : args.length == 2 ? Args.filter(List.of(NONE), args) : List.of())
                        .sub("ffa", null, (sender, args) -> withArena(sender, args, this::ffa),
                                (sender, args) -> args.length == 1 ? Args.filter(arenas.names(), args)
                                        : args.length == 2 ? Args.filter(Stream.concat(Stream.of(NONE),
                                                duels.kits().names().stream()).toList(), args) : List.of())
                        .sub("pool", null, (sender, args) -> withArena(sender, args, this::pool),
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
                        .sub("snapshot", null, (sender, args) -> withArena(sender, args, (arena, rest) -> snapshot(sender, arena)), arenaNames)
                        .sub("reset", null, (sender, args) -> withArena(sender, args, (arena, rest) -> reset(sender, arena)), arenaNames))
                .sub(new KitAdminCommand(this, duels, kitMenu).node())
                .sub(new HologramAdminCommand(this, duels).node())
                .sub(new SeasonAdminCommand(duels, duels.seasonEnder()).node())
                .sub(new EloAdminCommand(duels).node())
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
        duels.pool().forget(arena.name());
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
            inWorld(player, arena, () -> {
                if (!spawn) {
                    // Its copies would be pasted from the old box.
                    duels.pool().forget(arena.name());
                }
                save(player, arenas.update(changed), spawn ? "admin.arena.spawn-set" : "admin.arena.corner-set",
                        with(arenaTags(arena), Placeholder.unparsed("number", number)));
            });
        });
    }

    /**
     * {@code setgoal <arena> <1|2>}: side 1 or 2's bridge goal where the player stands. {@code setbed <arena> <1|2>}:
     * the bed fight bed the player looks at.
     */
    private void setModePoint(Player player, String[] args, boolean bed) {
        withArena(player, args, (arena, rest) -> {
            String number = Args.get(rest, 0);
            if (!NUMBERS.contains(number)) {
                messages.send(player, "admin.use-number");
                return;
            }
            int side = Integer.parseInt(number);
            Arena.Position position;
            if (bed) {
                Block target = player.getTargetBlockExact(BED_REACH);
                if (target == null || !Tag.BEDS.isTagged(target.getType())) {
                    messages.send(player, "admin.arena.not-a-bed");
                    return;
                }
                position = Arena.Position.of(target.getLocation());
            } else {
                position = Arena.Position.of(player.getLocation());
            }
            Arena.ModePoints points = bed ? arena.points().withBed(side, position) : arena.points().withGoal(side, position);
            inWorld(player, arena, () -> save(player, arenas.update(arena.withPoints(points)),
                    bed ? "admin.arena.bed-set" : "admin.arena.goal-set", with(arenaTags(arena), Placeholder.unparsed("number", number))));
        });
    }

    private void info(CommandSender sender, Arena arena) {
        messages.send(sender, "admin.arena.info", with(arenaTags(arena),
                Placeholder.unparsed("world", arena.world()),
                Placeholder.unparsed("categories", arena.categories().isEmpty() ? NONE : String.join(", ", new TreeSet<>(arena.categories()))),
                Placeholder.unparsed("build-limit", arena.buildLimit() == null ? NONE : String.valueOf(arena.buildLimit())),
                Placeholder.unparsed("extra-spawns", String.valueOf(arena.extraSpawns().size())),
                Placeholder.component("copies", copies(sender, arena)),
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

    /** Saves the arena's blocks, to rebuild it if a crash cuts a build duel short and to paste its copies. */
    private void snapshot(CommandSender sender, Arena arena) {
        if (arena.corner1() == null || arena.corner2() == null || Bukkit.getWorld(arena.world()) == null) {
            messages.send(sender, "admin.arena.not-ready", with(arenaTags(arena), Placeholder.component("problems", status(sender, arena))));
            return;
        }
        if (!ArenaTemplate.fitsLimits(arena)) {
            messages.send(sender, "admin.arena.too-big", arenaTags(arena));
            return;
        }
        if (duels.instances().baseInUse(arena.name()) || !snapshotting.add(arena.name())) {
            messages.send(sender, "admin.arena.in-use", arenaTags(arena));
            return;
        }
        boolean wasMarked = arenas.needingReset().contains(arena.name());
        CompletableFuture<Void> saving = CompletableFuture.allOf(ArenaTemplate.save(duels.plugin(), arena), duels.pool().snapshot(arena));
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
        if (duels.instances().baseInUse(arena.name())) {
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
        Optional<BoundingBox> box = duels.worldEdit().selection(player);
        if (box.isEmpty()) {
            messages.send(player, "admin.arena.no-selection");
            return;
        }
        BoundingBox b = box.get();
        Arena changed = arena.withCorner(1, new Arena.Position(b.getMinX(), b.getMinY(), b.getMinZ(), 0, 0))
                .withCorner(2, new Arena.Position(b.getMaxX() - 1, b.getMaxY() - 1, b.getMaxZ() - 1, 0, 0));
        duels.pool().forget(arena.name());
        save(player, arenas.update(changed), "admin.arena.box-set", arenaTags(arena));
    }

    /** {@code import <name> <file.schem>}: a new arena from a schematic, pasted at a free place in the arenas world. */
    private void importSchematic(CommandSender sender, String[] args) {
        String name = Args.get(args, 0).toLowerCase(Locale.ROOT);
        String file = Args.get(args, 1);
        WorldEditHook worldEdit = duels.worldEdit();
        if (!ArenaRegistry.validName(name)) {
            messages.send(sender, "admin.invalid-name");
        } else if (arenas.get(name).isPresent()) {
            messages.send(sender, "admin.arena.exists", Placeholder.unparsed("id", name));
        } else if (!SCHEMATIC.matcher(file).matches()) {
            messages.send(sender, "admin.arena.bad-schematic");
        } else if (!duels.pool().isAvailable()) {
            messages.send(sender, "admin.arena.no-arenas-world", Placeholder.unparsed("world", duels.pool().worldName()));
        } else {
            TagResolver[] tags = {Placeholder.unparsed("id", name), Placeholder.unparsed("arena", name), Placeholder.unparsed("file", file)};
            messages.send(sender, "admin.arena.importing", tags);
            Path path = duels.schematicsFolder().resolve(file);
            worldEdit.load(path).whenComplete((copy, error) -> Tasks.sync(duels.plugin(), () -> {
                if (error != null) {
                    importFailed(sender, name, error, tags);
                    return;
                }
                pasteImport(sender, worldEdit, copy, name, tags);
            }));
        }
    }

    private void pasteImport(CommandSender sender, WorldEditHook worldEdit, WorldEditHook.Copy copy, String name, TagResolver[] tags) {
        int[] size = copy.size();
        if (size[0] > MAX_WIDTH || size[2] > MAX_WIDTH || (long) size[0] * size[1] * size[2] > ArenaTemplate.MAX_BLOCKS) {
            messages.send(sender, "admin.arena.too-big", tags);
            return;
        }
        BoundingBox reserved = duels.pool().reserve(size[0], size[1], size[2], IMPORT_Y);
        int[] at = {(int) reserved.getMinX(), (int) reserved.getMinY(), (int) reserved.getMinZ()};
        World world = duels.pool().world();
        worldEdit.paste(copy, world, at[0], at[1], at[2]).whenComplete((ignored, error) -> Tasks.sync(duels.plugin(), () -> {
            // Registered below, or failed: either way the place needs no reservation any more.
            duels.pool().unreserve(reserved);
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

    /** One more spawn for fights with more than two sides; it must be inside the arena's corners. */
    private void addSpawn(Player player, Arena arena) {
        if (!arena.inBox(player.getLocation())) {
            messages.send(player, "admin.arena.extra-spawn-outside", arenaTags(arena));
            return;
        }
        List<Arena.Position> spawns = new ArrayList<>(arena.extraSpawns());
        spawns.add(Arena.Position.of(player.getLocation()));
        save(player, arenas.update(arena.withExtraSpawns(spawns)), "admin.arena.extra-spawn-added",
                with(arenaTags(arena), Placeholder.unparsed("count", String.valueOf(spawns.size()))));
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

    /** {@code ffa <arena> <kit|none>}: the kit whose free-for-all the arena holds instead of duels; never a build kit. */
    private void ffa(CommandSender sender, Arena arena, String[] rest) {
        String raw = Args.get(rest, 0);
        if (raw.equalsIgnoreCase(NONE)) {
            save(sender, arenas.update(arena.withFfa(null)), "admin.arena.ffa-cleared", arenaTags(arena));
            return;
        }
        Optional<Kit> kit = duels.kits().get(raw);
        if (kit.isEmpty()) {
            messages.send(sender, "general.kit-not-found", Placeholder.unparsed("kit", raw));
            return;
        }
        TagResolver[] tags = with(arenaTags(arena), Placeholder.component("kit", Text.mm(kit.get().displayName())));
        if (kit.get().flag(KitRule.BUILD, duels.settings())) {
            messages.send(sender, "admin.arena.ffa-build-kit", tags);
            return;
        }
        save(sender, arenas.update(arena.withFfa(kit.get().name())), "admin.arena.ffa-set", tags);
    }

    /** {@code pool <arena> [clear]}: how many copies the arena has; {@code clear} clears the free ones now. */
    private void pool(CommandSender sender, Arena base, String[] rest) {
        if (Args.get(rest, 0).equalsIgnoreCase(CLEAR)) {
            int cleared = duels.pool().clearFree(base.name());
            messages.send(sender, cleared == 0 ? "admin.arena.no-copies" : "admin.arena.copies-cleared",
                    with(arenaTags(base), Placeholder.unparsed("count", String.valueOf(cleared))));
            return;
        }
        messages.send(sender, "admin.arena.pool", with(arenaTags(base), Placeholder.component("copies", copies(sender, base))));
    }

    /** The copies line of {@code arena}: copies in use and free, or why it gets none. */
    private Component copies(CommandSender viewer, Arena arena) {
        ArenaPool pool = duels.pool();
        if (!pool.isAvailable()) {
            return messages.get(viewer, "admin.arena.copies-no-world", Placeholder.unparsed("world", pool.worldName()));
        }
        ArenaPool.Count count = pool.count(arena.name());
        return messages.get(viewer, pool.hasTemplate(arena) ? "admin.arena.copies" : "admin.arena.copies-no-snapshot",
                Placeholder.unparsed("in-use", String.valueOf(count.inUse())), Placeholder.unparsed("free", String.valueOf(count.free())),
                Placeholder.unparsed("max", String.valueOf(duels.settings().pool().maxCopies())));
    }

    /** Every category some arena has, sorted; for tab completion. */
    List<String> categories() {
        return arenas.all().stream().flatMap(arena -> arena.categories().stream()).distinct().sorted().toList();
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** {@code arena|kit [name]}: the list or one entry's settings menu for players, the help for the console. */
    /** {@code /duels}: the admin menu; the help for the console and for players who may not open menus now. */
    private void hubOrHelp(CommandSender sender, String[] args) {
        if (sender instanceof Player player && !duels.matches().isRestricted(player)) {
            hubMenu.open(player, HubMenu.ADMIN);
        } else {
            messages.send(sender, "command.admin-help");
        }
    }

    static BiConsumer<CommandSender, String[]> menu(Messages messages, String helpKey, Consumer<Player> list,
                                                            BiConsumer<Player, String[]> one) {
        return (sender, args) -> {
            if (!(sender instanceof Player player)) {
                messages.send(sender, helpKey);
            } else if (args.length == 0) {
                list.accept(player);
            } else {
                one.accept(player, args);
            }
        };
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

    /** Points must be in the arena's world. */
    private void inWorld(Player player, Arena arena, Runnable action) {
        if (!player.getWorld().getName().equals(arena.world())) {
            messages.send(player, "admin.arena.wrong-world", with(arenaTags(arena), Placeholder.unparsed("world", arena.world())));
            return;
        }
        action.run();
    }

    /** The held item's material, or empty after telling the player to hold one. */
    Optional<Material> heldIcon(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.isEmpty()) {
            messages.send(player, "admin.hold-item");
            return Optional.empty();
        }
        return Optional.of(held.getType());
    }

    void rename(CommandSender sender, String[] rest, Consumer<String> action) {
        String text = Args.join(rest, 0).strip();
        if (text.isEmpty()) {
            messages.send(sender, "admin.invalid-display-name");
            return;
        }
        action.accept(text);
    }

    /** Sends {@code successKey} once the file is written, or a failure message; files are written off the main thread. */
    void save(CommandSender sender, CompletableFuture<?> saving, String successKey, TagResolver... tags) {
        saving.whenComplete((ignored, error) -> Tasks.sync(duels.plugin(), () -> {
            if (error == null) {
                messages.send(sender, successKey, tags);
                return;
            }
            duels.plugin().getLogger().log(Level.WARNING, "Could not save a duels admin change", error);
            messages.send(sender, "admin.save-failed");
        }));
    }

    private Component status(CommandSender viewer, Arena arena) {
        return ArenaAdminMenu.status(messages, viewer, arena);
    }

    private static TagResolver[] arenaTags(Arena arena) {
        return new TagResolver[]{Placeholder.unparsed("id", arena.name()), Placeholder.component("arena", Text.mm(arena.displayName()))};
    }

    static TagResolver[] with(TagResolver[] tags, TagResolver... more) {
        TagResolver[] all = Arrays.copyOf(tags, tags.length + more.length);
        System.arraycopy(more, 0, all, tags.length, more.length);
        return all;
    }
}
