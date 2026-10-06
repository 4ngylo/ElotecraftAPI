package me.angylo.elotecraftDuels.command;

import me.angylo.elotecraftAPI.command.Args;
import me.angylo.elotecraftAPI.command.CommandBuilder;
import me.angylo.elotecraftAPI.util.Cooldowns;
import me.angylo.elotecraftAPI.util.Durations;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Duels;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.menu.ArenaMenu;
import me.angylo.elotecraftDuels.menu.KitMenu;
import me.angylo.elotecraftDuels.stats.PlayerStats;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.Optional;
import java.util.logging.Level;

/** {@code /duel}: challenges, answers, rematches, queues, spectating and stats. */
public final class DuelCommand {

    static final String DUEL = "duels.duel";
    static final String SELECT_ARENA = "duels.select-arena";
    private static final int TOP_SIZE = 10;
    private static final String TOP_ELO = "elo";
    /** Between database lookups and spectate attempts by one player, so they cannot be spammed. */
    private static final Duration LOOKUP_COOLDOWN = Duration.ofSeconds(3);

    private final Duels duels;
    private final Messages messages;
    private final KitMenu kitMenu;
    private final ArenaMenu arenaMenu;
    private final Cooldowns<String> lookups = new Cooldowns<>();

    public DuelCommand(Duels duels, KitMenu kitMenu, ArenaMenu arenaMenu) {
        this.duels = duels;
        this.messages = duels.messages();
        this.kitMenu = kitMenu;
        this.arenaMenu = arenaMenu;
    }

    /** Registers {@code /duel} and returns it, so it can stay allowed during duels. */
    public Command register() {
        return CommandBuilder.create("duel")
                .description(Text.plain(messages.get("command.duel-description")))
                .messages(sender -> messages.get(sender, "command.no-permission"),
                        sender -> messages.get(sender, "command.player-only"))
                .executes(this::challengeOrHelp, this::suggestChallenge)
                .playerSub("accept", DUEL, (player, args) -> duels.requests().accept(player, Args.get(args, 0)), this::suggestSenders)
                .playerSub("deny", DUEL, (player, args) -> duels.requests().deny(player, Args.get(args, 0)), this::suggestSenders)
                .playerSub("rematch", DUEL, (player, args) -> duels.requests().rematch(player))
                .playerSub("queue", "duels.queue", (player, args) -> queue(player, args, false),
                        (sender, args) -> Args.filter(usableKits(sender), args))
                .playerSub("ranked", "duels.queue.ranked", (player, args) -> queue(player, args, true),
                        (sender, args) -> Args.filter(usableKits(sender), args))
                .playerSub("leave", null, this::leave)
                .playerSub("editkit", "duels.kit.edit", this::editKit, (sender, args) -> args.length == 1
                        ? Args.filter(Stream.concat(Stream.of("save", "cancel", "reset"), usableKits(sender).stream()).toList(), args)
                        : args.length == 2 && args[0].equalsIgnoreCase("reset") ? Args.filter(usableKits(sender), args) : List.of())
                .playerSub("spectate", "duels.spectate", this::spectate, (sender, args) -> Args.players(args))
                .sub("stats", "duels.stats", (sender, args) -> limited(sender, () -> stats(sender, args)),
                        (sender, args) -> Args.players(args))
                .sub("top", "duels.top", (sender, args) -> limited(sender, () -> top(sender, args)),
                        (sender, args) -> Args.filter(List.of(TOP_ELO), args))
                .register(duels.plugin());
    }

    /** Runs {@code action} unless {@code sender} did a lookup moments ago; the console is never limited. */
    private void limited(CommandSender sender, Runnable action) {
        if (sender instanceof Player && !lookups.tryUse(sender.getName(), LOOKUP_COOLDOWN)) {
            messages.send(sender, "general.slow-down",
                    Placeholder.unparsed("time", Durations.format(lookups.remaining(sender.getName()))));
            return;
        }
        action.run();
    }

    private void challengeOrHelp(CommandSender sender, String[] args) {
        if (args.length == 0) {
            messages.send(sender, "command.help");
            return;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.player-only");
            return;
        }
        if (!player.hasPermission(DUEL)) {
            messages.send(player, "command.no-permission");
            return;
        }
        Optional<Player> target = Args.player(args[0]);
        if (target.isEmpty()) {
            messages.send(player, "general.player-not-found", Placeholder.unparsed("player", args[0]));
            return;
        }
        if (target.get().equals(player)) {
            messages.send(player, "request.self");
            return;
        }
        if (duels.matches().isBusy(player)) {
            messages.send(player, "general.busy-self");
            return;
        }
        if (args.length == 1) {
            kitMenu.open(player, KitMenu.Mode.CHALLENGE, kit -> chooseArena(player, target.get(), kit));
            return;
        }
        Optional<Kit> kit = duels.kits().get(args[1]).filter(found -> !found.isEmpty());
        if (kit.isEmpty()) {
            messages.send(player, "general.kit-not-found", Placeholder.unparsed("kit", args[1]));
            return;
        }
        if (args.length == 2) {
            chooseArena(player, target.get(), kit.get());
            return;
        }
        if (!player.hasPermission(SELECT_ARENA)) {
            messages.send(player, "command.no-permission");
            return;
        }
        Optional<Arena> arena = duels.arenas().get(args[2]).filter(Arena::isReady);
        if (arena.isEmpty()) {
            messages.send(player, "general.arena-not-found", Placeholder.unparsed("arena", args[2]));
            return;
        }
        duels.requests().send(player, target.get(), kit.get(), arena.get(), false);
    }

    /** Opens the arena menu if the player may and can choose; otherwise the arena is random. */
    private void chooseArena(Player player, Player target, Kit kit) {
        if (player.hasPermission(SELECT_ARENA) && duels.arenas().all().stream().filter(arena -> arena.isReady() && kit.accepts(arena)).count() > 1) {
            arenaMenu.open(player, kit, arena -> duels.requests().send(player, target, kit, arena.orElse(null), false));
        } else {
            duels.requests().send(player, target, kit, null, false);
        }
    }

    private List<String> suggestChallenge(CommandSender sender, String[] args) {
        return switch (args.length) {
            case 1 -> Args.players(args).stream().filter(name -> !name.equals(sender.getName())).toList();
            case 2 -> Args.filter(usableKits(sender), args);
            case 3 -> sender.hasPermission(SELECT_ARENA)
                    ? Args.filter(duels.arenas().all().stream().filter(arena -> arena.isReady()
                            && duels.kits().get(args[1]).map(kit -> kit.accepts(arena)).orElse(true)).map(Arena::name).toList(), args)
                    : List.of();
            default -> List.of();
        };
    }

    private List<String> suggestSenders(CommandSender sender, String[] args) {
        return sender instanceof Player player && args.length == 1
                ? Args.filter(duels.requests().sendersOf(player), args) : List.of();
    }

    private List<String> usableKits(CommandSender sender) {
        return duels.kits().all().stream().filter(kit -> kit.canUse(sender) && !kit.isEmpty()).map(Kit::name).toList();
    }

    /** {@code /duel queue} and {@code /duel ranked}: joins or leaves a kit's queue; the kit menu without a kit. */
    private void queue(Player player, String[] args, boolean ranked) {
        if (args.length == 0) {
            kitMenu.open(player, ranked ? KitMenu.Mode.RANKED : KitMenu.Mode.QUEUE,
                    kit -> duels.queues().toggle(player, kit, ranked));
            return;
        }
        Optional<Kit> kit = duels.kits().get(args[0]).filter(found -> !found.isEmpty());
        if (kit.isEmpty()) {
            messages.send(player, "general.kit-not-found", Placeholder.unparsed("kit", args[0]));
            return;
        }
        duels.queues().toggle(player, kit.get(), ranked);
    }

    /** {@code /duel editkit [kit] | save | cancel | reset <kit>}; the kit menu opens without a kit. */
    private void editKit(Player player, String[] args) {
        String first = Args.get(args, 0).toLowerCase(Locale.ROOT);
        switch (first) {
            case "" -> kitMenu.open(player, KitMenu.Mode.EDIT, kit -> duels.editor().start(player, kit));
            case "save" -> duels.editor().save(player);
            case "cancel" -> duels.editor().cancel(player);
            case "reset" -> usableKit(player, Args.get(args, 1)).ifPresent(kit -> duels.editor().reset(player, kit));
            default -> usableKit(player, first).ifPresent(kit -> duels.editor().start(player, kit));
        }
    }

    /** The kit called {@code name} if {@code player} may use it; explains otherwise. */
    private Optional<Kit> usableKit(Player player, String name) {
        Optional<Kit> kit = duels.kits().get(name).filter(found -> !found.isEmpty());
        if (kit.isEmpty()) {
            messages.send(player, "general.kit-not-found", Placeholder.unparsed("kit", name));
        } else if (!kit.get().canUse(player)) {
            messages.send(player, "general.kit-locked", Placeholder.component("kit", Text.mm(kit.get().displayName())));
            return Optional.empty();
        }
        return kit;
    }

    private void leave(Player player, String[] args) {
        // The editor counts as busy, and the busy message points to /duel leave.
        if (duels.editor().isEditing(player)) {
            duels.editor().cancel(player);
        } else if (!duels.queues().leave(player) && !duels.matches().leave(player)) {
            messages.send(player, "general.nothing-to-leave");
        }
    }

    private void spectate(Player player, String[] args) {
        Optional<Player> target = Args.player(Args.get(args, 0));
        if (target.isEmpty()) {
            messages.send(player, "general.player-not-found", Placeholder.unparsed("player", Args.get(args, 0)));
            return;
        }
        Optional<Match> match = duels.matches().matchOf(target.get())
                .filter(found -> found.isFighter(target.get()) && found.state() != Match.State.ENDING);
        if (match.isEmpty()) {
            messages.send(player, "general.not-dueling", Placeholder.unparsed("player", target.get().getName()));
            return;
        }
        if (duels.matches().isBusy(player)) {
            messages.send(player, "general.busy-self");
            return;
        }
        // Each spectate saves the player's state, so starting one is rate limited.
        limited(player, () -> {
            duels.queues().leave(player);
            duels.matches().spectate(player, match.get());
        });
    }

    private void stats(CommandSender sender, String[] args) {
        String name = args.length > 0 ? args[0] : sender.getName();
        if (args.length == 0 && !(sender instanceof Player)) {
            messages.send(sender, "command.player-only");
            return;
        }
        duels.stats().find(name).thenAccept(found -> found.ifPresentOrElse(
                stats -> showStats(sender, stats),
                () -> messages.send(sender, "stats.unknown", Placeholder.unparsed("player", name))
        )).exceptionally(error -> {
            duels.plugin().getLogger().log(Level.WARNING, "Could not load duel stats of " + name, error);
            messages.send(sender, "stats.error");
            return null;
        });
    }

    private void showStats(CommandSender sender, PlayerStats stats) {
        messages.send(sender, "stats.show",
                Placeholder.unparsed("player", stats.name()),
                Placeholder.unparsed("wins", String.valueOf(stats.wins())),
                Placeholder.unparsed("losses", String.valueOf(stats.losses())),
                Placeholder.unparsed("rate", String.valueOf(stats.winRate())),
                Placeholder.unparsed("streak", String.valueOf(stats.winStreak())),
                Placeholder.unparsed("best", String.valueOf(stats.bestWinStreak())),
                Placeholder.unparsed("elo", String.valueOf(stats.elo())));
    }

    /** {@code /duel top} by wins, {@code /duel top elo} by rating. */
    private void top(CommandSender sender, String[] args) {
        boolean byElo = args.length > 0 && args[0].equalsIgnoreCase(TOP_ELO);
        (byElo ? duels.stats().topByElo(TOP_SIZE) : duels.stats().top(TOP_SIZE)).thenAccept(top -> {
            if (top.isEmpty()) {
                messages.send(sender, "top.empty");
                return;
            }
            messages.send(sender, byElo ? "top.elo-header" : "top.header");
            for (int i = 0; i < top.size(); i++) {
                PlayerStats stats = top.get(i);
                messages.send(sender, byElo ? "top.elo-line" : "top.line",
                        Placeholder.unparsed("rank", String.valueOf(i + 1)),
                        Placeholder.unparsed("player", stats.name()),
                        Placeholder.unparsed("wins", String.valueOf(stats.wins())),
                        Placeholder.unparsed("losses", String.valueOf(stats.losses())),
                        Placeholder.unparsed("elo", String.valueOf(stats.elo())));
            }
        }).exceptionally(error -> {
            duels.plugin().getLogger().log(Level.WARNING, "Could not load the duel leaderboard", error);
            messages.send(sender, "stats.error");
            return null;
        });
    }
}
