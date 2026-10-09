package me.angylo.elotecraftDuels.command;

import me.angylo.elotecraftAPI.command.Args;
import me.angylo.elotecraftAPI.command.CommandBuilder;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Duels;
import me.angylo.elotecraftDuels.match.QueueManager;
import me.angylo.elotecraftDuels.stats.Divisions;
import me.angylo.elotecraftDuels.stats.KitRating;
import me.angylo.elotecraftDuels.stats.PlayerStats;
import me.angylo.elotecraftDuels.stats.StatsService;
import me.angylo.elotecraftDuels.stats.StatsService.RatingChange;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * {@code /duels elo}: a player's ratings, and admin changes to them ({@code set}, {@code add}, {@code reset}) in one
 * kit or {@code all}, online or not. Every change is logged with who made it; {@code reset} asks to run it again with
 * {@code confirm} within {@value #CONFIRM_SECONDS} seconds. Players in a duel or a ranked queue are left alone, so a
 * result written afterwards cannot undo the change.
 */
final class EloAdminCommand {

    static final String PERMISSION = "duels.admin.elo";
    /** Changing ratings; {@link #PERMISSION} alone only shows them. */
    static final String EDIT = "duels.admin.elo.edit";
    private static final int CONFIRM_SECONDS = 30;
    private static final String CONFIRM = "confirm";
    private static final String ALL = "all";

    private final Duels duels;
    private final Messages messages;
    /** Sender name and what they asked to reset, to the time their {@code confirm} must come by. */
    private final Map<String, Long> armed = new HashMap<>();

    EloAdminCommand(Duels duels) {
        this.duels = duels;
        this.messages = duels.messages();
    }

    CommandBuilder node() {
        return CommandBuilder.create("elo").permission(PERMISSION)
                .executes(this::show, (sender, args) -> args.length == 1 ? Args.players(args) : List.of())
                .sub("set", EDIT, (sender, args) -> change(sender, args, RatingChange.SET), this::suggestChange)
                .sub("add", EDIT, (sender, args) -> change(sender, args, RatingChange.ADD), this::suggestChange)
                .sub("reset", EDIT, this::reset, (sender, args) -> args.length == 3 ? Args.filter(List.of(CONFIRM), args) : suggestChange(sender, args));
    }

    private List<String> suggestChange(CommandSender sender, String[] args) {
        return switch (args.length) {
            case 1 -> Args.players(args);
            case 2 -> Args.filter(Stream.concat(Stream.of(ALL), duels.kits().names().stream()).toList(), args);
            default -> List.of();
        };
    }

    /** {@code /duels elo <player>}: their rating in each kit and overall; the help without a player. */
    private void show(CommandSender sender, String[] args) {
        if (args.length == 0) {
            messages.send(sender, "command.elo-help");
            return;
        }
        duels.stats().find(args[0]).thenAccept(found -> found.ifPresentOrElse(stats -> {
            Divisions divisions = duels.settings().ranked().divisions();
            int overall = stats.overallElo(duels.kits().names());
            messages.send(sender, "admin.elo.header", Placeholder.unparsed("player", stats.name()),
                    Placeholder.unparsed("elo", String.valueOf(overall)), Placeholder.component("division", divisions.name(overall)));
            if (stats.ratings().isEmpty()) {
                messages.send(sender, "admin.elo.no-ratings");
            }
            stats.ratings().forEach((kit, rating) -> messages.send(sender, "stats.kit-line", kitLine(kit, rating, divisions)));
        }, () -> messages.send(sender, "stats.unknown", Placeholder.unparsed("player", args[0]))))
                .exceptionally(error -> failed(sender, error));
    }

    /** {@code set|add <player> <kit|all> <value>}. */
    private void change(CommandSender sender, String[] args, RatingChange change) {
        if (args.length < 3 || !args[2].matches("[+-]?\\d{1,6}")) {
            messages.send(sender, change == RatingChange.SET ? "admin.elo.set-usage" : "admin.elo.add-usage");
            return;
        }
        int value = Integer.parseInt(args[2]);
        if (change == RatingChange.SET && (value < 0 || value > StatsService.MAX_ELO)) {
            messages.send(sender, "admin.elo.out-of-range", Placeholder.unparsed("max", String.valueOf(StatsService.MAX_ELO)));
            return;
        }
        apply(sender, args[0], args[1], change, value);
    }

    /** {@code reset <player> [kit|all]}, then the same with {@code confirm}. */
    private void reset(CommandSender sender, String[] args) {
        if (args.length == 0) {
            messages.send(sender, "admin.elo.reset-usage");
            return;
        }
        String kit = args.length > 1 && !args[1].equalsIgnoreCase(CONFIRM) ? args[1] : ALL;
        boolean confirmed = args[args.length - 1].equalsIgnoreCase(CONFIRM);
        String key = sender.getName() + " " + args[0].toLowerCase(Locale.ROOT) + " " + kit.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        if (!confirmed) {
            armed.values().removeIf(until -> until < now);
            armed.put(key, now + Duration.ofSeconds(CONFIRM_SECONDS).toMillis());
            messages.send(sender, "admin.elo.reset-confirm", Placeholder.unparsed("player", args[0]), Placeholder.unparsed("kit", kit),
                    Placeholder.unparsed("seconds", String.valueOf(CONFIRM_SECONDS)));
            return;
        }
        Long until = armed.remove(key);
        if (until == null || until < now) {
            messages.send(sender, "admin.elo.reset-not-armed");
            return;
        }
        apply(sender, args[0], kit, RatingChange.RESET, 0);
    }

    /** Finds the player, checks the kit and that they are free, changes the ratings, then logs and reports each. */
    private void apply(CommandSender sender, String name, String kitArg, RatingChange change, int value) {
        boolean all = kitArg.equalsIgnoreCase(ALL);
        if (!all && duels.kits().get(kitArg).isEmpty()) {
            messages.send(sender, "top.unknown-kit", Placeholder.unparsed("kit", kitArg));
            return;
        }
        if (busy(sender, name)) {
            return;
        }
        // An online player by their id: a name may have belonged to someone else before.
        Player online = Bukkit.getPlayerExact(name);
        CompletableFuture<Optional<StatsService.Known>> lookup = online != null
                ? CompletableFuture.completedFuture(Optional.of(new StatsService.Known(online.getUniqueId(), online.getName())))
                : duels.stats().findId(name);
        lookup.thenAccept(found -> found.ifPresentOrElse(player -> {
            // Checked again: they may have joined a ranked queue or a duel during the lookup.
            if (busy(sender, player.name())) {
                return;
            }
            Collection<String> kits = all ? duels.kits().names() : List.of(duels.kits().get(kitArg).orElseThrow().name());
            duels.stats().changeRating(player.id(), kits, change, value).thenAccept(changed -> {
                if (changed.isEmpty()) {
                    messages.send(sender, "admin.elo.nothing", Placeholder.unparsed("player", player.name()));
                    return;
                }
                Divisions divisions = duels.settings().ranked().divisions();
                messages.send(sender, "admin.elo.changed", Placeholder.unparsed("player", player.name()));
                changed.forEach((kit, rating) -> {
                    int before = rating.before() == null ? PlayerStats.START_ELO : rating.before().elo();
                    duels.plugin().getLogger().info(sender.getName() + " changed " + player.name() + "'s " + kit + " rating ("
                            + change.name().toLowerCase(Locale.ROOT) + "): " + before + " -> " + rating.after().elo());
                    messages.send(sender, "admin.elo.changed-line", Placeholder.component("kit", kitName(kit)),
                            Placeholder.unparsed("before", String.valueOf(before)), Placeholder.unparsed("elo", String.valueOf(rating.after().elo())),
                            Placeholder.component("division", divisions.name(rating.after().elo())));
                });
            }).exceptionally(error -> failed(sender, error));
        }, () -> messages.send(sender, "stats.unknown", Placeholder.unparsed("player", name)))).exceptionally(error -> failed(sender, error));
    }

    /** Whether {@code name} is online in a duel or a ranked queue, which a rating change must not race; says so. */
    private boolean busy(CommandSender sender, String name) {
        Player online = Bukkit.getPlayerExact(name);
        boolean busy = online != null && (duels.matches().matchOf(online).isPresent()
                || duels.queues().queued(online.getUniqueId()).filter(QueueManager.QueueId::ranked).isPresent());
        if (busy) {
            messages.send(sender, "admin.elo.busy", Placeholder.unparsed("player", online.getName()));
        }
        return busy;
    }

    private TagResolver[] kitLine(String kit, KitRating rating, Divisions divisions) {
        return new TagResolver[]{Placeholder.component("kit", kitName(kit)), Placeholder.unparsed("elo", String.valueOf(rating.elo())),
                Placeholder.unparsed("peak", String.valueOf(rating.peak())), Placeholder.component("division", divisions.name(rating.elo())),
                Placeholder.unparsed("wins", String.valueOf(rating.wins())), Placeholder.unparsed("losses", String.valueOf(rating.losses()))};
    }

    private Component kitName(String kit) {
        return duels.kits().get(kit).map(found -> Text.mm(found.displayName())).orElse(Component.text(kit));
    }

    private Void failed(CommandSender sender, Throwable error) {
        duels.plugin().getLogger().log(Level.WARNING, "A /duels elo change failed", error);
        messages.send(sender, "stats.error");
        return null;
    }
}
