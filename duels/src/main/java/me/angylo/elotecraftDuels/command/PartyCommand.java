package me.angylo.elotecraftDuels.command;

import me.angylo.elotecraftAPI.command.Args;
import me.angylo.elotecraftAPI.command.CommandBuilder;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Duels;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.menu.KitMenu;
import me.angylo.elotecraftDuels.menu.PartyMenu;
import me.angylo.elotecraftDuels.party.Party;
import me.angylo.elotecraftDuels.party.PartyManager;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/** {@code /party}: make a party and manage its members. {@code /party <player>} invites. */
public final class PartyCommand {

    private static final String PARTY = "duels.party";
    private static final String FIGHT = "duels.party.fight";

    private final Duels duels;
    private final Messages messages;
    private final PartyManager parties;
    private final KitMenu kitMenu;
    private final PartyMenu partyMenu;

    public PartyCommand(Duels duels, KitMenu kitMenu, PartyMenu partyMenu) {
        this.duels = duels;
        this.messages = duels.messages();
        this.parties = duels.parties();
        this.kitMenu = kitMenu;
        this.partyMenu = partyMenu;
    }

    public void register() {
        CommandBuilder.create("party")
                .description(Text.plain(messages.get("command.party-description")))
                .permission(PARTY)
                .messages(sender -> messages.get(sender, "command.no-permission"),
                        sender -> messages.get(sender, "command.player-only"))
                .executes(this::inviteOrHelp, (sender, args) -> args.length == 1 ? Args.players(args) : List.of())
                .sub("help", null, (sender, args) -> messages.send(sender, "command.party-help"))
                .playerSub("create", null, (player, args) -> parties.create(player))
                .playerSub("public", null, (player, args) -> parties.toggleOpen(player))
                .playerSub("join", null, (player, args) -> join(player, args),
                        (sender, args) -> args.length == 1 ? Args.filter(openLeaders(), args) : List.of())
                .playerSub("chat", null, (player, args) -> parties.chat(player, String.join(" ", args)))
                .playerSub("invite", null, (player, args) -> invite(player, Args.get(args, 0)), (sender, args) -> Args.players(args))
                .playerSub("accept", null, (player, args) -> parties.accept(player, Args.get(args, 0)), (sender, args) -> Args.players(args))
                .playerSub("deny", null, (player, args) -> parties.deny(player, Args.get(args, 0)), (sender, args) -> Args.players(args))
                .playerSub("kick", null, (player, args) -> parties.kick(player, Args.get(args, 0)), this::suggestMembers)
                .playerSub("promote", null, (player, args) -> parties.promote(player, Args.get(args, 0)), this::suggestMembers)
                .playerSub("leave", null, (player, args) -> parties.leave(player))
                .playerSub("disband", null, (player, args) -> parties.disband(player))
                .playerSub("info", null, (player, args) -> parties.info(player))
                .playerSub("split", FIGHT, (player, args) -> fight(player, args, 0, (kit, arena) -> duels.partyFights().split(player, kit, arena)), this::suggestKitArena)
                .playerSub("ffa", FIGHT, (player, args) -> fight(player, args, 0, (kit, arena) -> duels.partyFights().ffa(player, kit, arena)), this::suggestKitArena)
                .playerSub("duel", FIGHT, this::challenge, (sender, args) -> args.length == 1 ? Args.players(args)
                        : suggestKitArena(sender, Arrays.copyOfRange(args, 1, args.length)))
                .playerSub("duelaccept", FIGHT, (player, args) -> duels.partyFights().accept(player, Args.get(args, 0)), (sender, args) -> Args.players(args))
                .playerSub("dueldeny", FIGHT, (player, args) -> duels.partyFights().deny(player, Args.get(args, 0)), (sender, args) -> Args.players(args))
                .register(duels.plugin());
        // /pc <message>: party chat in two letters.
        CommandBuilder.create("pc")
                .description(Text.plain(messages.get("command.party-chat-description")))
                .permission(PARTY)
                .messages(sender -> messages.get(sender, "command.no-permission"),
                        sender -> messages.get(sender, "command.player-only"))
                .executes((sender, args) -> {
                    if (sender instanceof Player player) {
                        parties.chat(player, String.join(" ", args));
                    } else {
                        messages.send(sender, "command.player-only");
                    }
                })
                .register(duels.plugin());
    }

    private void join(Player player, String[] args) {
        if (args.length == 0) {
            partyMenu.open(player);
            return;
        }
        parties.join(player, args[0]);
    }

    private List<String> openLeaders() {
        return parties.openParties().stream().map(party -> Bukkit.getPlayer(party.leader())).filter(Objects::nonNull)
                .map(Player::getName).toList();
    }

    private void inviteOrHelp(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                partyMenu.open(player);
            } else {
                messages.send(sender, "command.party-help");
            }
        } else if (sender instanceof Player player) {
            invite(player, args[0]);
        } else {
            messages.send(sender, "command.player-only");
        }
    }

    private void invite(Player player, String targetName) {
        Optional<Player> target = Args.player(targetName);
        if (target.isEmpty()) {
            messages.send(player, "general.player-not-found", Placeholder.unparsed("player", targetName));
            return;
        }
        parties.invite(player, target.get());
    }

    /** {@code <kit> [arena]} from {@code args[from]}; the kit menu opens without a kit. */
    private void fight(Player player, String[] args, int from, BiConsumer<Kit, Arena> start) {
        if (args.length <= from) {
            kitMenu.open(player, KitMenu.Mode.CHALLENGE, kit -> start.accept(kit, null));
            return;
        }
        Optional<Kit> kit = duels.kits().get(args[from]).filter(found -> !found.isEmpty());
        if (kit.isEmpty()) {
            messages.send(player, "general.kit-not-found", Placeholder.unparsed("kit", args[from]));
            return;
        }
        if (args.length <= from + 1) {
            start.accept(kit.get(), null);
            return;
        }
        if (!player.hasPermission(DuelCommand.SELECT_ARENA)) {
            messages.send(player, "command.no-permission");
            return;
        }
        Optional<Arena> arena = duels.arenas().get(args[from + 1]).filter(Arena::isReady);
        if (arena.isEmpty()) {
            messages.send(player, "general.arena-not-found", Placeholder.unparsed("arena", args[from + 1]));
            return;
        }
        start.accept(kit.get(), arena.get());
    }

    /** {@code duel <leader> [kit] [arena]} */
    private void challenge(Player player, String[] args) {
        Optional<Player> target = Args.player(Args.get(args, 0));
        if (target.isEmpty()) {
            messages.send(player, "general.player-not-found", Placeholder.unparsed("player", Args.get(args, 0)));
            return;
        }
        fight(player, args, 1, (kit, arena) -> duels.partyFights().challenge(player, target.get(), kit, arena));
    }

    private List<String> suggestKitArena(CommandSender sender, String[] args) {
        return switch (args.length) {
            case 1 -> Args.filter(duels.kits().all().stream().filter(kit -> kit.canUse(sender) && !kit.isEmpty()).map(Kit::name).toList(), args);
            case 2 -> sender.hasPermission(DuelCommand.SELECT_ARENA)
                    ? Args.filter(duels.arenas().all().stream().filter(arena -> arena.isReady()
                            && duels.kits().get(args[0]).map(kit -> kit.accepts(arena)).orElse(true)).map(Arena::name).toList(), args)
                    : List.of();
            default -> List.of();
        };
    }

    private List<String> suggestMembers(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return List.of();
        }
        List<UUID> members = parties.partyOf(player.getUniqueId()).map(Party::members).orElse(List.of());
        return Args.filter(members.stream().map(Bukkit::getPlayer).filter(Objects::nonNull)
                .map(Player::getName).filter(name -> !name.equals(player.getName())).toList(), args);
    }
}
