package me.angylo.elotecraftDuels.command;

import me.angylo.elotecraftAPI.command.Args;
import me.angylo.elotecraftAPI.command.CommandBuilder;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Duels;
import me.angylo.elotecraftDuels.Settings.Reward;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.stats.Divisions;
import me.angylo.elotecraftDuels.stats.Ranking;
import me.angylo.elotecraftDuels.stats.SeasonEnder;
import me.angylo.elotecraftDuels.stats.Seasons;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * {@code /duels season}: the season running and the ended ones, their leaderboards, players, divisions and kits;
 * planning the end ({@code schedule}, {@code auto}), names and CSV exports; and {@code end} to finish the season
 * ({@link SeasonEnder}). Ending cannot be undone, so {@code end} only warns, {@code end preview} shows what it would
 * do, and {@code end confirm} within {@value #CONFIRM_SECONDS} seconds does it.
 */
final class SeasonAdminCommand {

    private static final int CONFIRM_SECONDS = 30;
    private static final String CONFIRM = "confirm";
    private static final String PREVIEW = "preview";
    private static final int TOP_SIZE = 10;
    private static final int SUMMARY_TOP = 3;
    private static final int ENDED_TOP = 5;
    private static final int KIT_LINES = 10;
    private static final int MAX_NAME = 64;
    private static final int MAX_DAYS = 3650;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final List<String> AUTO = List.of("on", "off", "toggle");

    private final Duels duels;
    private final Messages messages;
    private final SeasonEnder ender;
    /** Sender name to the time their {@code end confirm} must come by. */
    private final Map<String, Long> armed = new HashMap<>();

    SeasonAdminCommand(Duels duels, SeasonEnder ender) {
        this.duels = duels;
        this.messages = duels.messages();
        this.ender = ender;
    }

    CommandBuilder node() {
        return CommandBuilder.create("season").permission(SeasonEnder.PERMISSION)
                .executes((sender, args) -> current(sender))
                .sub("list", null, (sender, args) -> list(sender))
                .sub("info", null, (sender, args) -> withEnded(sender, Args.get(args, 0), summary -> ended(sender, summary)),
                        (sender, args) -> args.length == 1 ? Args.filter(seasonNumbers(false), args) : List.of())
                .sub("top", null, this::top, (sender, args) -> args.length == 1 ? Args.filter(seasonNumbers(true), args)
                        : args.length == 2 ? Args.filter(duels.kits().names(), args) : List.of())
                .sub("player", null, this::player, (sender, args) -> args.length == 1 ? Args.players(args)
                        : args.length == 2 ? Args.filter(seasonNumbers(true), args) : List.of())
                .sub("divisions", null, (sender, args) -> divisions(sender, false))
                .sub("compare", null, this::compare, (sender, args) -> args.length <= 2 ? Args.filter(seasonNumbers(true), args) : List.of())
                .sub("kits", null, this::kits, (sender, args) -> args.length == 1 ? Args.filter(seasonNumbers(true), args) : List.of())
                .sub("end", SeasonEnder.MANAGE, this::end, (sender, args) -> args.length == 1 ? Args.filter(List.of(PREVIEW, CONFIRM), args) : List.of())
                .sub("schedule", SeasonEnder.MANAGE, this::schedule, (sender, args) -> args.length == 1
                        ? Args.filter(List.of("30", "60", "90", "off", LocalDate.now().plusMonths(1).format(DATE)), args) : List.of())
                .sub("auto", SeasonEnder.MANAGE, this::auto, (sender, args) -> args.length == 1 ? Args.filter(AUTO, args) : List.of())
                .sub("name", SeasonEnder.MANAGE, this::name, (sender, args) -> args.length == 1 ? Args.filter(seasonNumbers(true), args) : List.of())
                .sub("export", SeasonEnder.MANAGE, this::export, (sender, args) -> args.length == 1 ? Args.filter(seasonNumbers(true), args) : List.of());
    }

    /** The season running: its name, dates, numbers and leaders. */
    private void current(CommandSender sender) {
        Seasons.Info info = duels.seasons().info();
        duels.seasons().ratings(info.season()).thenCombine(duels.stats().topByElo(duels.kits().names(), SUMMARY_TOP), (ratings, top) -> {
            Seasons.Totals totals = Seasons.Totals.of(ratings);
            long now = System.currentTimeMillis();
            messages.send(sender, "admin.season.info", with(seasonTags(sender, info.season(), info.name()),
                    Placeholder.unparsed("started", date(info.startedAt())),
                    Placeholder.unparsed("age", SeasonEnder.length(Duration.ofMillis(now - info.startedAt()))),
                    Placeholder.component("since", messages.get(sender, info.startKnown() ? "admin.season.start-known" : "admin.season.start-unknown")),
                    Placeholder.component("ends", ends(sender, info, now)),
                    Placeholder.component("auto", messages.get(sender, info.autoEnd() ? "admin.season.on" : "admin.season.off")),
                    Placeholder.unparsed("players", String.valueOf(totals.players())),
                    Placeholder.unparsed("duels", String.valueOf(totals.duels()))));
            rankings(sender, top);
            return null;
        }).exceptionally(error -> failed(sender, error));
    }

    private Component ends(CommandSender sender, Seasons.Info info, long now) {
        if (!info.planned()) {
            return messages.get(sender, "admin.season.no-end");
        }
        TagResolver date = Placeholder.unparsed("date", date(info.endsAt()));
        return info.endsAt() <= now ? messages.get(sender, "admin.season.end-passed", date)
                : messages.get(sender, "admin.season.end-in", date,
                Placeholder.unparsed("time", SeasonEnder.length(Duration.ofMillis(info.endsAt() - now))));
    }

    private void list(CommandSender sender) {
        duels.seasons().ended().thenAccept(seasons -> {
            if (seasons.isEmpty()) {
                messages.send(sender, "top.no-seasons");
                return;
            }
            messages.send(sender, "admin.season.list-header");
            seasons.forEach(summary -> messages.send(sender, "admin.season.list-line", summaryTags(sender, summary)));
        }).exceptionally(error -> failed(sender, error));
    }

    /** An ended season: its dates, numbers and final leaders. */
    private void ended(CommandSender sender, Seasons.Summary summary) {
        duels.seasons().top(summary.season(), ENDED_TOP).thenAccept(top -> {
            messages.send(sender, "admin.season.ended-info", summaryTags(sender, summary));
            rankings(sender, top);
        }).exceptionally(error -> failed(sender, error));
    }

    /** {@code top [season] [kit]}: the running season's or an ended one's best ratings, overall or in one kit. */
    private void top(CommandSender sender, String[] args) {
        int season = args.length > 0 ? number(args[0]) : duels.seasons().current();
        Optional<Kit> kit = args.length > 1 ? duels.kits().get(args[1]) : Optional.empty();
        if (args.length > 1 && kit.isEmpty()) {
            messages.send(sender, "top.unknown-kit", Placeholder.unparsed("kit", args[1]));
            return;
        }
        withSeason(sender, season, () -> {
            boolean running = season == duels.seasons().current();
            CompletableFuture<List<Ranking>> top = kit.isPresent()
                    ? running ? duels.stats().topByElo(kit.get().name(), TOP_SIZE) : duels.seasons().top(season, kit.get().name(), TOP_SIZE)
                    : running ? duels.stats().topByElo(duels.kits().names(), TOP_SIZE) : duels.seasons().top(season, TOP_SIZE);
            top.thenAccept(rankings -> {
                messages.send(sender, "admin.season.top-header", Placeholder.unparsed("season", String.valueOf(season)),
                        Placeholder.component("kit", kit.map(found -> Text.mm(found.displayName()))
                                .orElseGet(() -> messages.get(sender, "admin.season.all-kits"))));
                rankings(sender, rankings);
            }).exceptionally(error -> failed(sender, error));
        });
    }

    /** {@code player <name> [season]}: a player's ratings in each kit. */
    private void player(CommandSender sender, String[] args) {
        if (args.length == 0) {
            messages.send(sender, "admin.season.player-usage");
            return;
        }
        String name = args[0];
        int season = args.length > 1 ? number(args[1]) : duels.seasons().current();
        withSeason(sender, season, () -> duels.seasons().ratings(season).thenAccept(ratings -> {
            List<Seasons.Rating> theirs = ratings.stream().filter(rating -> rating.name().equalsIgnoreCase(name))
                    .sorted(Comparator.comparingInt(Seasons.Rating::elo).reversed()).toList();
            if (theirs.isEmpty()) {
                messages.send(sender, "admin.season.player-none", Placeholder.unparsed("player", name),
                        Placeholder.unparsed("season", String.valueOf(season)));
                return;
            }
            Divisions divisions = duels.settings().ranked().divisions();
            messages.send(sender, "admin.season.player-header", Placeholder.unparsed("player", theirs.getFirst().name()),
                    Placeholder.unparsed("season", String.valueOf(season)));
            for (Seasons.Rating rating : theirs) {
                messages.send(sender, "stats.kit-line", Placeholder.component("kit", kitName(rating.kit())),
                        Placeholder.unparsed("elo", String.valueOf(rating.elo())), Placeholder.unparsed("peak", String.valueOf(rating.peak())),
                        Placeholder.component("division", divisions.name(rating.elo())),
                        Placeholder.unparsed("wins", String.valueOf(rating.wins())), Placeholder.unparsed("losses", String.valueOf(rating.losses())));
            }
        }).exceptionally(error -> failed(sender, error)));
    }

    /** Players per division now, and what ending the season now would pay; {@code preview} adds what it would archive. */
    private void divisions(CommandSender sender, boolean preview) {
        ender.preview().thenAccept(result -> {
            TagResolver[] tags = {Placeholder.unparsed("season", String.valueOf(duels.seasons().current())),
                    Placeholder.unparsed("ratings", String.valueOf(result.ratings())),
                    Placeholder.unparsed("players", String.valueOf(result.players())),
                    Placeholder.unparsed("rewards", String.valueOf(result.rewards()))};
            messages.send(sender, preview ? "admin.season.preview" : "admin.season.divisions-header", tags);
            result.divisions().forEach((division, count) -> messages.send(sender, "admin.season.divisions-line",
                    Placeholder.component("division", Text.mm(division.name())), Placeholder.unparsed("count", String.valueOf(count)),
                    Placeholder.component("reward", division.seasonReward().equals(Reward.NONE) ? Component.empty()
                            : messages.get(sender, "admin.season.has-reward"))));
            messages.send(sender, "admin.season.divisions-footer", tags);
        }).exceptionally(error -> failed(sender, error));
    }

    /** {@code compare <a> <b>}: two seasons side by side. */
    private void compare(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages.send(sender, "admin.season.compare-usage");
            return;
        }
        int first = number(args[0]);
        int second = number(args[1]);
        withSeason(sender, first, () -> withSeason(sender, second, () -> duels.seasons().ratings(first)
                .thenCombine(duels.seasons().ratings(second), (a, b) -> {
                    Seasons.Totals one = Seasons.Totals.of(a);
                    Seasons.Totals two = Seasons.Totals.of(b);
                    messages.send(sender, "admin.season.compare",
                            Placeholder.unparsed("a", String.valueOf(first)), Placeholder.unparsed("b", String.valueOf(second)),
                            Placeholder.unparsed("a_players", String.valueOf(one.players())), Placeholder.unparsed("b_players", String.valueOf(two.players())),
                            Placeholder.unparsed("a_duels", String.valueOf(one.duels())), Placeholder.unparsed("b_duels", String.valueOf(two.duels())),
                            Placeholder.unparsed("a_elo", String.valueOf(one.averageElo())), Placeholder.unparsed("b_elo", String.valueOf(two.averageElo())),
                            Placeholder.component("a_kit", topKit(sender, one)), Placeholder.component("b_kit", topKit(sender, two)));
                    return null;
                }).exceptionally(error -> failed(sender, error))));
    }

    /** {@code kits [season]}: the kits played ranked most. */
    private void kits(CommandSender sender, String[] args) {
        int season = args.length > 0 ? number(args[0]) : duels.seasons().current();
        withSeason(sender, season, () -> duels.seasons().ratings(season).thenAccept(ratings -> {
            List<Seasons.KitUse> kits = Seasons.Totals.of(ratings).kits();
            messages.send(sender, "admin.season.kits-header", Placeholder.unparsed("season", String.valueOf(season)));
            if (kits.isEmpty()) {
                messages.send(sender, "admin.season.kits-empty");
            }
            kits.stream().limit(KIT_LINES).forEach(use -> messages.send(sender, "admin.season.kits-line",
                    Placeholder.component("kit", kitName(use.kit())), Placeholder.unparsed("duels", String.valueOf(use.duels())),
                    Placeholder.unparsed("players", String.valueOf(use.players()))));
        }).exceptionally(error -> failed(sender, error)));
    }

    private void end(CommandSender sender, String[] args) {
        String option = Args.get(args, 0).toLowerCase(Locale.ROOT);
        if (option.equals(PREVIEW)) {
            divisions(sender, true);
            return;
        }
        if (ender.isEnding()) {
            messages.send(sender, "admin.season.busy");
            return;
        }
        long now = System.currentTimeMillis();
        if (!option.equals(CONFIRM)) {
            armed.values().removeIf(until -> until < now);
            armed.put(sender.getName(), now + Duration.ofSeconds(CONFIRM_SECONDS).toMillis());
            messages.send(sender, "admin.season.confirm", season(duels.seasons().current()),
                    Placeholder.unparsed("seconds", String.valueOf(CONFIRM_SECONDS)));
            return;
        }
        Long until = armed.remove(sender.getName());
        if (until == null || until < now) {
            messages.send(sender, "admin.season.not-armed");
            return;
        }
        ender.end(sender);
    }

    /** {@code schedule <days|yyyy-MM-dd|off>}: the planned end of the season running, days counted from now. */
    private void schedule(CommandSender sender, String[] args) {
        String raw = Args.get(args, 0).toLowerCase(Locale.ROOT);
        long endsAt;
        if (raw.equals("off")) {
            endsAt = 0;
        } else {
            endsAt = plannedEnd(raw);
            if (endsAt < 0) {
                messages.send(sender, "admin.season.schedule-usage");
                return;
            }
            if (endsAt <= System.currentTimeMillis()) {
                messages.send(sender, "admin.season.schedule-past");
                return;
            }
        }
        duels.seasons().schedule(endsAt).thenAccept(info -> {
            if (!info.planned()) {
                messages.send(sender, "admin.season.unscheduled", season(info.season()));
                return;
            }
            messages.send(sender, info.autoEnd() ? "admin.season.scheduled-auto" : "admin.season.scheduled", season(info.season()),
                    Placeholder.unparsed("date", date(info.endsAt())),
                    Placeholder.unparsed("time", SeasonEnder.length(Duration.ofMillis(info.endsAt() - System.currentTimeMillis()))));
        }).exceptionally(error -> failed(sender, error));
    }

    /** {@code days} from now, or the start of a date in the server's time zone; -1 if neither. */
    private static long plannedEnd(String raw) {
        if (raw.matches("\\d{1,4}")) {
            int days = Integer.parseInt(raw);
            return days < 1 || days > MAX_DAYS ? -1 : System.currentTimeMillis() + Duration.ofDays(days).toMillis();
        }
        try {
            return LocalDate.parse(raw, DATE).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return -1;
        }
    }

    /** {@code auto <on|off|toggle>}: whether the season running ends by itself at its planned end. */
    private void auto(CommandSender sender, String[] args) {
        String raw = Args.get(args, 0).toLowerCase(Locale.ROOT);
        if (!AUTO.contains(raw)) {
            messages.send(sender, "admin.season.auto-usage", Placeholder.component("auto",
                    messages.get(sender, duels.seasons().info().autoEnd() ? "admin.season.on" : "admin.season.off")));
            return;
        }
        boolean on = raw.equals("toggle") ? !duels.seasons().info().autoEnd() : raw.equals("on");
        duels.seasons().autoEnd(on).thenAccept(info -> {
            duels.plugin().getLogger().info(sender.getName() + " turned auto end of duel season " + info.season() + (on ? " on" : " off"));
            String key = !on ? "admin.season.auto-off" : info.planned() ? "admin.season.auto-on" : "admin.season.auto-on-unplanned";
            messages.send(sender, key, season(info.season()), Placeholder.unparsed("date", date(info.endsAt())));
        }).exceptionally(error -> failed(sender, error));
    }

    /** {@code name <season> <name...>}, or {@code name <season> off}: a season's name, in MiniMessage. */
    private void name(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages.send(sender, "admin.season.name-usage");
            return;
        }
        int season = number(args[0]);
        String name = String.join(" ", List.of(args).subList(1, args.length)).strip();
        if (name.equalsIgnoreCase("off")) {
            name = "";
        }
        if (name.length() > MAX_NAME) {
            messages.send(sender, "admin.season.name-too-long", Placeholder.unparsed("max", String.valueOf(MAX_NAME)));
            return;
        }
        String chosen = name;
        withSeason(sender, season, () -> duels.seasons().name(season, chosen).thenAccept(ignored ->
                messages.send(sender, chosen.isEmpty() ? "admin.season.name-cleared" : "admin.season.named",
                        seasonTags(sender, season, chosen))).exceptionally(error -> failed(sender, error)));
    }

    /** {@code export <season>}: every kit rating of a season to {@code seasons/season-<n>.csv}, written off the main thread. */
    private void export(CommandSender sender, String[] args) {
        int season = args.length > 0 ? number(args[0]) : duels.seasons().current();
        withSeason(sender, season, () -> duels.seasons().ratings(season).thenAccept(ratings -> {
            Path file = duels.plugin().getDataFolder().toPath().resolve("seasons").resolve("season-" + season + ".csv");
            String csv = csv(ratings);
            Tasks.async(duels.plugin(), () -> {
                try {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, csv, StandardCharsets.UTF_8);
                    Tasks.sync(duels.plugin(), () -> messages.send(sender, "admin.season.exported",
                            Placeholder.unparsed("file", "seasons/" + file.getFileName()), Placeholder.unparsed("ratings", String.valueOf(ratings.size()))));
                } catch (IOException e) {
                    duels.plugin().getLogger().log(Level.WARNING, "Could not write " + file, e);
                    Tasks.sync(duels.plugin(), () -> messages.send(sender, "admin.season.export-failed"));
                }
            });
        }).exceptionally(error -> failed(sender, error)));
    }

    /** Ratings as CSV, best first; names and kits are letters, digits and _ or -, so they need no quoting. */
    static String csv(List<Seasons.Rating> ratings) {
        StringBuilder out = new StringBuilder("player,uuid,kit,elo,peak,wins,losses\n");
        ratings.stream().sorted(Comparator.comparing(Seasons.Rating::kit).thenComparing(Comparator.comparingInt(Seasons.Rating::elo).reversed()))
                .forEach(rating -> out.append(String.join(",", rating.name(), rating.player().toString(), rating.kit(),
                        String.valueOf(rating.elo()), String.valueOf(rating.peak()), String.valueOf(rating.wins()),
                        String.valueOf(rating.losses()))).append('\n'));
        return out.toString();
    }

    /** Runs {@code then} if {@code season} is the season running or an ended one; explains otherwise. */
    private void withSeason(CommandSender sender, int season, Runnable then) {
        if (season >= 1 && season <= duels.seasons().current()) {
            then.run();
        } else {
            messages.send(sender, "admin.season.no-season", Placeholder.unparsed("last", String.valueOf(duels.seasons().current())));
        }
    }

    /** Runs {@code then} with the ended season {@code raw} names; explains otherwise. */
    private void withEnded(CommandSender sender, String raw, Consumer<Seasons.Summary> then) {
        int season = number(raw);
        int last = duels.seasons().current() - 1;
        if (last < 1) {
            messages.send(sender, "top.no-seasons");
            return;
        }
        if (season < 1 || season > last) {
            messages.send(sender, "top.season-usage", Placeholder.unparsed("last", String.valueOf(last)));
            return;
        }
        duels.seasons().ended(season).thenAccept(summary -> summary.ifPresentOrElse(then,
                () -> messages.send(sender, "top.season-usage", Placeholder.unparsed("last", String.valueOf(last)))))
                .exceptionally(error -> failed(sender, error));
    }

    private void rankings(CommandSender sender, List<Ranking> rankings) {
        if (rankings.isEmpty()) {
            messages.send(sender, "admin.season.top-empty");
            return;
        }
        Divisions divisions = duels.settings().ranked().divisions();
        for (int i = 0; i < rankings.size(); i++) {
            Ranking ranking = rankings.get(i);
            messages.send(sender, "top.elo-line", Placeholder.unparsed("rank", String.valueOf(i + 1)),
                    Placeholder.unparsed("player", ranking.name()), Placeholder.unparsed("elo", String.valueOf(ranking.elo())),
                    Placeholder.component("division", divisions.name(ranking.elo())),
                    Placeholder.unparsed("wins", String.valueOf(ranking.wins())), Placeholder.unparsed("losses", String.valueOf(ranking.losses())));
        }
    }

    private TagResolver[] summaryTags(CommandSender sender, Seasons.Summary summary) {
        return with(seasonTags(sender, summary.season(), summary.name()),
                Placeholder.unparsed("started", summary.startedAt() > 0 ? date(summary.startedAt()) : "?"),
                Placeholder.unparsed("ended", date(summary.endedAt())),
                Placeholder.unparsed("length", summary.startedAt() > 0
                        ? SeasonEnder.length(Duration.ofMillis(summary.endedAt() - summary.startedAt())) : "?"),
                Placeholder.unparsed("players", String.valueOf(summary.players())),
                Placeholder.unparsed("duels", String.valueOf(summary.duels())));
    }

    private static TagResolver[] with(TagResolver[] tags, TagResolver... more) {
        return Stream.concat(Stream.of(tags), Stream.of(more)).toArray(TagResolver[]::new);
    }

    private TagResolver[] seasonTags(CommandSender sender, int season, String name) {
        return new TagResolver[]{season(season), Placeholder.component("name", SeasonEnder.name(messages, sender, season, name))};
    }

    private Component topKit(CommandSender sender, Seasons.Totals totals) {
        return totals.kits().isEmpty() ? messages.get(sender, "admin.season.none") : kitName(totals.kits().getFirst().kit());
    }

    /** A kit's display name, or its id once the kit is gone. */
    private Component kitName(String kit) {
        return duels.kits().get(kit).map(found -> Text.mm(found.displayName())).orElse(Component.text(kit));
    }

    /** Season numbers for tab completion: the ended ones, and the running one with {@code withRunning}. */
    private List<String> seasonNumbers(boolean withRunning) {
        int last = duels.seasons().current() - (withRunning ? 0 : 1);
        return IntStream.rangeClosed(1, last).map(i -> last + 1 - i).mapToObj(String::valueOf).toList();
    }

    private static int number(String raw) {
        return raw.matches("\\d{1,6}") ? Integer.parseInt(raw) : -1;
    }

    private static String date(long millis) {
        return SeasonEnder.date(millis);
    }

    private Void failed(CommandSender sender, Throwable error) {
        duels.plugin().getLogger().log(Level.WARNING, "A /duels season lookup failed", error);
        messages.send(sender, "stats.error");
        return null;
    }

    private static TagResolver season(int number) {
        return Placeholder.unparsed("season", String.valueOf(number));
    }
}
