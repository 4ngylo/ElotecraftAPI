package me.angylo.elotecraftDuels.command;

import me.angylo.elotecraftAPI.command.Args;
import me.angylo.elotecraftAPI.command.CommandBuilder;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftDuels.Duels;
import me.angylo.elotecraftDuels.arena.Arena.Position;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import me.angylo.elotecraftDuels.hud.LeaderboardHolograms;
import me.angylo.elotecraftDuels.hud.LeaderboardHolograms.Board;
import me.angylo.elotecraftDuels.hud.LeaderboardHolograms.Type;
import me.angylo.elotecraftDuels.kit.Kit;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** {@code /duels hologram}: places and removes leaderboard holograms ({@link LeaderboardHolograms}). */
final class HologramAdminCommand {

    private static final List<String> TYPES = Arrays.stream(Type.values()).map(Type::key).toList();

    private final AdminCommand admin;
    private final Duels duels;
    private final Messages messages;

    HologramAdminCommand(AdminCommand admin, Duels duels) {
        this.admin = admin;
        this.duels = duels;
        this.messages = duels.messages();
    }

    CommandBuilder node() {
        return CommandBuilder.create("hologram").permission("duels.admin.hologram")
                .executes((sender, args) -> messages.send(sender, "command.hologram-help"))
                .playerSub("create", null, this::create, (sender, args) -> args.length == 2 ? Args.filter(TYPES, args)
                        : args.length == 3 && args[1].equalsIgnoreCase(Type.ELO.key()) ? Args.filter(duels.kits().names(), args) : List.of())
                .sub("delete", null, this::delete, (sender, args) -> args.length == 1 ? Args.filter(names(), args) : List.of())
                .sub("list", null, (sender, args) -> list(sender));
    }

    /** {@code create <name> <wins|elo> [kit]} where the player stands; an existing name is moved and replaced. */
    private void create(Player player, String[] args) {
        if (args.length < 2) {
            messages.send(player, "command.hologram-help");
            return;
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        if (!ArenaRegistry.validName(name)) {
            messages.send(player, "admin.invalid-name");
            return;
        }
        Optional<Type> type = Type.byKey(args[1]);
        if (type.isEmpty() || (type.get() == Type.WINS && args.length > 2)) {
            messages.send(player, "command.hologram-help");
            return;
        }
        String kit = null;
        if (args.length > 2) {
            Optional<Kit> found = duels.kits().get(args[2]);
            if (found.isEmpty()) {
                messages.send(player, "top.unknown-kit", Placeholder.unparsed("kit", args[2]));
                return;
            }
            kit = found.get().name();
        }
        Board board = new Board(name, type.get(), kit, player.getWorld().getName(), Position.of(player.getLocation()));
        admin.save(player, duels.holograms().put(board), "admin.hologram.created", Placeholder.unparsed("name", name));
    }

    private void delete(CommandSender sender, String[] args) {
        if (args.length == 0) {
            messages.send(sender, "command.hologram-help");
            return;
        }
        Optional<Board> board = duels.holograms().get(args[0].toLowerCase(Locale.ROOT));
        if (board.isEmpty()) {
            messages.send(sender, "admin.hologram.unknown", Placeholder.unparsed("name", args[0]));
            return;
        }
        admin.save(sender, duels.holograms().delete(board.get().name()), "admin.hologram.deleted",
                Placeholder.unparsed("name", board.get().name()));
    }

    private void list(CommandSender sender) {
        if (duels.holograms().all().isEmpty()) {
            messages.send(sender, "admin.hologram.none");
            return;
        }
        messages.send(sender, "admin.hologram.list-header");
        for (Board board : duels.holograms().all()) {
            messages.send(sender, "admin.hologram.list-line", Placeholder.unparsed("name", board.name()),
                    Placeholder.unparsed("type", board.type().key() + (board.kit() == null ? "" : " " + board.kit())),
                    Placeholder.unparsed("world", board.world()),
                    Placeholder.unparsed("x", String.valueOf((int) Math.floor(board.position().x()))),
                    Placeholder.unparsed("y", String.valueOf((int) Math.floor(board.position().y()))),
                    Placeholder.unparsed("z", String.valueOf((int) Math.floor(board.position().z()))));
        }
    }

    private List<String> names() {
        return duels.holograms().all().stream().map(Board::name).toList();
    }
}
