package me.angylo.elotecraftDuels.command;

import me.angylo.elotecraftAPI.command.Args;
import me.angylo.elotecraftAPI.command.CommandBuilder;
import me.angylo.elotecraftAPI.util.Cooldowns;
import me.angylo.elotecraftAPI.util.Durations;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Cosmetics;
import me.angylo.elotecraftDuels.Duels;
import me.angylo.elotecraftDuels.PlayerOptions;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.match.Bets;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.menu.ArenaMenu;
import me.angylo.elotecraftDuels.menu.CosmeticsMenu;
import me.angylo.elotecraftDuels.menu.FightInventoryMenu;
import me.angylo.elotecraftDuels.menu.HistoryMenu;
import me.angylo.elotecraftDuels.menu.HubMenu;
import me.angylo.elotecraftDuels.menu.KitMenu;
import me.angylo.elotecraftDuels.menu.OptionsMenu;
import me.angylo.elotecraftDuels.menu.CustomKitMenu;
import me.angylo.elotecraftDuels.menu.RatingsMenu;
import me.angylo.elotecraftDuels.menu.SpectateMenu;
import me.angylo.elotecraftDuels.stats.Divisions;
import me.angylo.elotecraftDuels.stats.KitRating;
import me.angylo.elotecraftDuels.stats.PlayerStats;
import me.angylo.elotecraftDuels.stats.Ranking;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.logging.Level;

/** {@code /duel}: challenges, answers, rematches, queues, spectating and stats. */
public final class DuelCommand {

    static final String DUEL = "duels.duel";
    public static final String SELECT_ARENA = "duels.select-arena";
    private static final int TOP_SIZE = 10;
    private static final String TOP_ELO = "elo";
    private static final String TOP_SEASON = "season";
    private static final String BET = "bet";
    /** Between database lookups and spectate attempts by one player, so they cannot be spammed. */
    private static final Duration LOOKUP_COOLDOWN = Duration.ofSeconds(3);

    private final Duels duels;
    private final Messages messages;
    private final KitMenu kitMenu;
    private final ArenaMenu arenaMenu;
    private final FightInventoryMenu inventoryMenu;
    private final HistoryMenu historyMenu;
    private final OptionsMenu optionsMenu;
    private final CosmeticsMenu cosmeticsMenu;
    private final SpectateMenu spectateMenu;
    private final CustomKitMenu customKitMenu;
    private final HubMenu hubMenu;
    private final RatingsMenu ratingsMenu;
    private final Cooldowns<String> lookups = new Cooldowns<>();

    public DuelCommand(Duels duels, KitMenu kitMenu, ArenaMenu arenaMenu, FightInventoryMenu inventoryMenu, HistoryMenu historyMenu,
                       OptionsMenu optionsMenu, CosmeticsMenu cosmeticsMenu, SpectateMenu spectateMenu,
                       CustomKitMenu customKitMenu, HubMenu hubMenu, RatingsMenu ratingsMenu) {
        this.duels = duels;
        this.messages = duels.messages();
        this.kitMenu = kitMenu;
        this.arenaMenu = arenaMenu;
        this.inventoryMenu = inventoryMenu;
        this.historyMenu = historyMenu;
        this.optionsMenu = optionsMenu;
        this.cosmeticsMenu = cosmeticsMenu;
        this.spectateMenu = spectateMenu;
        this.customKitMenu = customKitMenu;
        this.hubMenu = hubMenu;
        this.ratingsMenu = ratingsMenu;
    }

    /** Registers {@code /duel} and returns it, so it can stay allowed during duels. */
    public Command register() {
        return CommandBuilder.create("duel")
                .description(Text.plain(messages.get("command.duel-description")))
                .messages(sender -> messages.get(sender, "command.no-permission"),
                        sender -> messages.get(sender, "command.player-only"))
                .executes(this::challengeOrHub, this::suggestChallenge)
                .sub("help", null, (sender, args) -> messages.send(sender, "command.help"))
                .playerSub("menu", null, (player, args) -> openHub(player, args.length == 0 ? HubMenu.MAIN : args[0]),
                        (sender, args) -> args.length == 1 ? Args.filter(hubMenu.names(), args) : List.of())
                .playerSub("accept", DUEL, (player, args) -> duels.requests().accept(player, Args.get(args, 0)), this::suggestSenders)
                .playerSub("deny", DUEL, (player, args) -> duels.requests().deny(player, Args.get(args, 0)), this::suggestSenders)
                .playerSub("cancel", DUEL, (player, args) -> duels.requests().cancel(player, Args.get(args, 0)),
                        this::suggestTargets)
                .playerSub("rematch", DUEL, (player, args) -> duels.requests().rematch(player))
                .playerSub("queue", "duels.queue", (player, args) -> queue(player, args, false),
                        (sender, args) -> Args.filter(usableKits(sender), args))
                .playerSub("ranked", "duels.queue.ranked", (player, args) -> queue(player, args, true),
                        (sender, args) -> Args.filter(usableKits(sender), args))
                .playerSub("leave", null, this::leave)
                .playerSub("toggle", null, this::toggle, (sender, args) -> args.length == 1 ? Args.filter(PlayerOptions.keys(), args) : List.of())
                .playerSub("options", null, (player, args) -> optionsMenu.open(player))
                .playerSub("cosmetics", "duels.cosmetics", this::cosmetics, (sender, args) -> args.length == 1
                        ? Args.filter(Arrays.stream(Cosmetics.Kind.values()).map(Cosmetics.Kind::key).toList(), args) : List.of())
                .playerSub("editkit", "duels.kit.edit", this::editKit, (sender, args) -> args.length == 1
                        ? Args.filter(Stream.concat(Stream.of("save", "cancel", "reset"), usableKits(sender).stream()).toList(), args)
                        : args.length == 2 && args[0].equalsIgnoreCase("reset") ? Args.filter(usableKits(sender), args) : List.of())
                .playerSub("customkit", "duels.kit.custom", this::customKit, (sender, args) -> args.length == 1
                        ? Args.filter(Stream.concat(Stream.of("items"), IntStream.rangeClosed(1, duels.customKits().slots())
                                .mapToObj(String::valueOf)).toList(), args) : List.of())
                .playerSub("spectate", "duels.spectate", this::spectate, (sender, args) -> Args.players(args))
                .sub("stats", "duels.stats", (sender, args) -> limited(sender, () -> stats(sender, args)),
                        (sender, args) -> Args.players(args))
                .playerSub("ratings", "duels.stats", (player, args) -> ratingsMenu.open(player))
                .playerSub("history", "duels.history", (player, args) -> limited(player, () -> history(player, args)),
                        (sender, args) -> Args.players(args))
                // Clicked in the result message; no suggestions, as the ids are not meant to be typed.
                .playerSub("inventory", null, this::inventory)
                .sub("top", "duels.top", (sender, args) -> limited(sender, () -> top(sender, args)),
                        (sender, args) -> args.length <= 1 ? Args.filter(List.of(TOP_ELO, TOP_SEASON), args)
                                : args.length == 2 && args[0].equalsIgnoreCase(TOP_ELO) ? Args.filter(duels.kits().names(), args) : List.of())
                .register(duels.plugin());
    }

    /** {@code /duel cosmetics [kill-effect|kill-message]}: the hub's cosmetics menu without a kind. */
    private void cosmetics(Player player, String[] args) {
        if (args.length == 0) {
            hubMenu.open(player, "cosmetics");
            return;
        }
        Optional<Cosmetics.Kind> kind = Arrays.stream(Cosmetics.Kind.values()).filter(found -> found.key().equalsIgnoreCase(args[0])).findFirst();
        kind.ifPresentOrElse(found -> cosmeticsMenu.open(player, found), () -> messages.send(player, "cosmetics.usage"));
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

    /** Opens a hub menu; players in a match or the kit editor, who may not open menus, get the help instead. */
    private void openHub(Player player, String name) {
        if (duels.matches().isRestricted(player)) {
            messages.send(player, "command.help");
        } else {
            hubMenu.open(player, name);
        }
    }

    /** {@code /duel <player> ...} challenges; {@code /duel} alone opens the hub, or shows the help to the console. */
    private void challengeOrHub(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                openHub(player, HubMenu.MAIN);
            } else {
                messages.send(sender, "command.help");
            }
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
        if (duels.requests().refuses(player, target.get())) {
            return;
        }
        if (duels.matches().isBusy(player)) {
            messages.send(player, "general.busy-self");
            return;
        }
        // "... bet <amount>" at the end: each player's stake.
        double bet = 0;
        if (args.length >= 4 && args[args.length - 2].equalsIgnoreCase(BET)) {
            bet = Bets.parse(args[args.length - 1]);
            if (Double.isNaN(bet)) {
                messages.send(player, "bet.invalid", Placeholder.unparsed("amount", args[args.length - 1]));
                return;
            }
            args = Arrays.copyOf(args, args.length - 2);
        }
        if (args.length == 1) {
            kitMenu.open(player, KitMenu.Mode.CHALLENGE, duels.customKits().of(player), kit -> chooseArena(player, target.get(), kit, 0));
            return;
        }
        Optional<Kit> kit = duels.customKits().resolve(player, args[1]).filter(found -> !found.isEmpty());
        if (kit.isEmpty()) {
            messages.send(player, "general.kit-not-found", Placeholder.unparsed("kit", args[1]));
            return;
        }
        if (args.length == 2) {
            chooseArena(player, target.get(), kit.get(), bet);
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
        duels.requests().send(player, target.get(), kit.get(), arena.get(), false, bet);
    }

    /** Opens the arena menu if the player may and can choose; otherwise the arena is random. */
    private void chooseArena(Player player, Player target, Kit kit, double bet) {
        if (player.hasPermission(SELECT_ARENA) && duels.arenas().all().stream().filter(arena -> arena.isReady() && kit.accepts(arena)).count() > 1) {
            arenaMenu.open(player, kit, arena -> duels.requests().send(player, target, kit, arena.orElse(null), false, bet));
        } else {
            duels.requests().send(player, target, kit, null, false, bet);
        }
    }

    private List<String> suggestChallenge(CommandSender sender, String[] args) {
        return switch (args.length) {
            case 1 -> Args.players(args).stream().filter(name -> !name.equals(sender.getName())).toList();
            case 2 -> Args.filter(Stream.concat(usableKits(sender).stream(), sender instanceof Player player
                    ? duels.customKits().arguments(player).stream() : Stream.<String>empty()).toList(), args);
            case 3 -> Args.filter(Stream.concat(betWord(sender), sender.hasPermission(SELECT_ARENA)
                    ? duels.arenas().all().stream().filter(arena -> arena.isReady()
                            && duels.kits().get(args[1]).map(kit -> kit.accepts(arena)).orElse(true)).map(Arena::name)
                    : Stream.empty()).toList(), args);
            case 4 -> args[2].equalsIgnoreCase(BET) ? List.of() : Args.filter(betWord(sender).toList(), args);
            default -> List.of();
        };
    }

    private static Stream<String> betWord(CommandSender sender) {
        return sender.hasPermission(Bets.PERMISSION) ? Stream.of(BET) : Stream.empty();
    }

    private List<String> suggestTargets(CommandSender sender, String[] args) {
        return sender instanceof Player player && args.length == 1
                ? Args.filter(duels.requests().targetsOf(player), args) : List.of();
    }

    private List<String> suggestSenders(CommandSender sender, String[] args) {
        return sender instanceof Player player && args.length == 1
                ? Args.filter(duels.requests().sendersOf(player), args) : List.of();
    }

    private List<String> usableKits(CommandSender sender) {
        return duels.kits().all().stream().filter(kit -> kit.canUse(sender) && !kit.isEmpty()).map(Kit::name).toList();
    }

    /** {@code /duel queue} and {@code /duel ranked}: joins or leaves a kit's queue; the kit menu without a kit. */
    /** {@code /duel toggle <option>}; its usage without one. Duel requests keep their own messages. */
    private void toggle(Player player, String[] args) {
        if (args.length == 0) {
            messages.send(player, "options.usage", Placeholder.unparsed("options", String.join(", ", PlayerOptions.keys())));
            return;
        }
        String key = args[0];
        Optional<PlayerOptions> option = PlayerOptions.byKey(key);
        if (option.isEmpty()) {
            messages.send(player, "options.unknown", Placeholder.unparsed("option", key),
                    Placeholder.unparsed("options", String.join(", ", PlayerOptions.keys())));
            return;
        }
        if (option.get() == PlayerOptions.REQUESTS) {
            duels.requests().toggle(player);
            return;
        }
        boolean on = option.get().toggle(player);
        messages.send(player, on ? "options.toggled-on" : "options.toggled-off",
                Placeholder.component("option", messages.get(player, "options.names." + option.get().key())));
    }

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

    /** {@code /duel customkit [<slot> | items]}: the custom kit menu, building one, or the items to build it from. */
    private void customKit(Player player, String[] args) {
        String first = Args.get(args, 0).toLowerCase(Locale.ROOT);
        if (first.isEmpty()) {
            customKitMenu.openSlots(player);
        } else if (first.equals("items")) {
            customKitMenu.openItems(player);
        } else {
            duels.editor().startCustom(player, first.matches("[1-9]") ? Integer.parseInt(first) : -1);
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
        // The editor counts as busy, and the busy message points to /duel leave. Matches go before events:
        // a player between tournament fights who watches one stops watching first.
        if (duels.editor().isEditing(player)) {
            duels.editor().cancel(player);
        } else if (!duels.queues().leave(player) && !duels.matches().leave(player) && !duels.events().leave(player)) {
            messages.send(player, "general.nothing-to-leave");
        }
    }

    private void spectate(Player player, String[] args) {
        Optional<Match> watching = duels.matches().matchOf(player).filter(found -> found.isWatching(player));
        if (args.length == 0) {
            watching.ifPresentOrElse(match -> spectateMenu.openFighters(player, match), () -> spectateMenu.open(player));
            return;
        }
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
        // Watching this fight already: go to the fighter, if they are still in it.
        if (watching.equals(match)) {
            if (match.get().isAlive(target.get())) {
                player.teleport(target.get());
            } else {
                messages.send(player, "general.not-dueling", Placeholder.unparsed("player", target.get().getName()));
            }
            return;
        }
        if (!match.get().watchableBy(player)) {
            messages.send(player, "spectate.not-allowed");
            return;
        }
        if (duels.matches().isBusy(player) && (duels.matches().isRestricted(player) || !duels.events().mayWatch(player, match.get()))) {
            messages.send(player, "general.busy-self");
            return;
        }
        // Each spectate saves the player's state, so starting one is rate limited.
        limited(player, () -> {
            duels.queues().leave(player);
            duels.matches().spectate(player, match.get());
        });
    }

    /** {@code inventory <id> <player>}: a fighter of a fight that just ended, as the fight left them. */
    private void inventory(Player player, String[] args) {
        if (args.length != 2) {
            messages.send(player, "match.inventory-gone");
            return;
        }
        // The result links go out while the fight is ending, and menus do not open in a match.
        if (duels.matches().isRestricted(player)) {
            messages.send(player, "match.inventory-after-fight");
            return;
        }
        duels.matches().fightResult(args[0], args[1]).ifPresentOrElse(fighter -> inventoryMenu.open(player, fighter),
                () -> messages.send(player, "match.inventory-gone"));
    }

    /** {@code history [player]}: the latest duels of a player, online or not. */
    private void history(Player viewer, String[] args) {
        Player online = Bukkit.getPlayerExact(args.length > 0 ? args[0] : viewer.getName());
        String name = online != null ? online.getName() : args[0];
        (online != null ? duels.history().of(online.getUniqueId()) : duels.history().of(name)).thenAccept(entries -> {
            if (!viewer.isOnline()) {
                return;
            }
            if (entries.isEmpty()) {
                messages.send(viewer, "history.empty", Placeholder.unparsed("player", name));
            } else {
                historyMenu.open(viewer, name, entries);
            }
        }).exceptionally(error -> {
            duels.plugin().getLogger().log(Level.WARNING, "Could not load the duel history of " + name, error);
            messages.send(viewer, "history.error");
            return null;
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

    /** The record, the overall rating, then one line per kit they played ranked. */
    private void showStats(CommandSender sender, PlayerStats stats) {
        Divisions divisions = duels.settings().ranked().divisions();
        int overall = stats.overallElo(duels.kits().names());
        messages.send(sender, "stats.show",
                Placeholder.unparsed("player", stats.name()),
                Placeholder.unparsed("wins", String.valueOf(stats.wins())),
                Placeholder.unparsed("losses", String.valueOf(stats.losses())),
                Placeholder.unparsed("rate", String.valueOf(stats.winRate())),
                Placeholder.unparsed("streak", String.valueOf(stats.winStreak())),
                Placeholder.unparsed("best", String.valueOf(stats.bestWinStreak())),
                Placeholder.unparsed("elo", String.valueOf(overall)),
                Placeholder.component("division", divisions.name(overall)));
        for (Kit kit : duels.kits().all()) {
            KitRating rating = stats.ratings().get(kit.name());
            if (rating != null) {
                messages.send(sender, "stats.kit-line", Placeholder.component("kit", Text.mm(kit.displayName())),
                        Placeholder.unparsed("elo", String.valueOf(rating.elo())),
                        Placeholder.unparsed("peak", String.valueOf(rating.peak())),
                        Placeholder.component("division", divisions.name(rating.elo())),
                        Placeholder.unparsed("wins", String.valueOf(rating.wins())),
                        Placeholder.unparsed("losses", String.valueOf(rating.losses())));
            }
        }
    }

    /** {@code /duel top} by wins, {@code /duel top elo [kit]} by rating. */
    private void top(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase(TOP_ELO)) {
            topByElo(sender, args.length > 1 ? args[1] : null);
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase(TOP_SEASON)) {
            topOfSeason(sender, args);
            return;
        }
        duels.stats().top(TOP_SIZE).thenAccept(top -> {
            if (top.isEmpty()) {
                messages.send(sender, "top.empty");
                return;
            }
            messages.send(sender, "top.header");
            for (int i = 0; i < top.size(); i++) {
                PlayerStats stats = top.get(i);
                messages.send(sender, "top.line",
                        Placeholder.unparsed("rank", String.valueOf(i + 1)),
                        Placeholder.unparsed("player", stats.name()),
                        Placeholder.unparsed("wins", String.valueOf(stats.wins())),
                        Placeholder.unparsed("losses", String.valueOf(stats.losses())));
            }
        }).exceptionally(error -> leaderboardFailed(sender, error));
    }

    /** The ratings in {@code kitName}, or the overall ratings when null. */
    private void topByElo(CommandSender sender, String kitName) {
        Optional<Kit> kit = kitName == null ? Optional.empty() : duels.kits().get(kitName);
        if (kitName != null && kit.isEmpty()) {
            messages.send(sender, "top.unknown-kit", Placeholder.unparsed("kit", kitName));
            return;
        }
        Divisions divisions = duels.settings().ranked().divisions();
        (kit.isPresent() ? duels.stats().topByElo(kit.get().name(), TOP_SIZE)
                : duels.stats().topByElo(duels.kits().names(), TOP_SIZE)).thenAccept(top -> {
            if (top.isEmpty()) {
                messages.send(sender, "top.empty");
                return;
            }
            kit.ifPresentOrElse(found -> messages.send(sender, "top.elo-kit-header", Placeholder.component("kit", Text.mm(found.displayName()))),
                    () -> messages.send(sender, "top.elo-header"));
            for (int i = 0; i < top.size(); i++) {
                Ranking ranking = top.get(i);
                messages.send(sender, "top.elo-line",
                        Placeholder.unparsed("rank", String.valueOf(i + 1)),
                        Placeholder.unparsed("player", ranking.name()),
                        Placeholder.unparsed("elo", String.valueOf(ranking.elo())),
                        Placeholder.component("division", divisions.name(ranking.elo())),
                        Placeholder.unparsed("wins", String.valueOf(ranking.wins())),
                        Placeholder.unparsed("losses", String.valueOf(ranking.losses())));
            }
        }).exceptionally(error -> leaderboardFailed(sender, error));
    }

    /** {@code /duel top season <number>}: an ended season's best overall ratings. */
    private void topOfSeason(CommandSender sender, String[] args) {
        int ended = duels.seasons().current() - 1;
        OptionalInt season = args.length == 2 ? Args.integer(args[1], 1, Math.max(1, ended)) : OptionalInt.empty();
        if (ended < 1 || season.isEmpty()) {
            messages.send(sender, ended < 1 ? "top.no-seasons" : "top.season-usage", Placeholder.unparsed("last", String.valueOf(ended)));
            return;
        }
        Divisions divisions = duels.settings().ranked().divisions();
        duels.seasons().top(season.getAsInt(), TOP_SIZE).thenAccept(top -> {
            messages.send(sender, "top.season-header", Placeholder.unparsed("season", String.valueOf(season.getAsInt())));
            for (int i = 0; i < top.size(); i++) {
                Ranking ranking = top.get(i);
                messages.send(sender, "top.elo-line",
                        Placeholder.unparsed("rank", String.valueOf(i + 1)),
                        Placeholder.unparsed("player", ranking.name()),
                        Placeholder.unparsed("elo", String.valueOf(ranking.elo())),
                        Placeholder.component("division", divisions.name(ranking.elo())),
                        Placeholder.unparsed("wins", String.valueOf(ranking.wins())),
                        Placeholder.unparsed("losses", String.valueOf(ranking.losses())));
            }
        }).exceptionally(error -> leaderboardFailed(sender, error));
    }

    private Void leaderboardFailed(CommandSender sender, Throwable error) {
        duels.plugin().getLogger().log(Level.WARNING, "Could not load the duel leaderboard", error);
        messages.send(sender, "stats.error");
        return null;
    }
}
