package me.angylo.elotecraftDuels.command;

import me.angylo.elotecraftAPI.command.Args;
import me.angylo.elotecraftAPI.command.CommandBuilder;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Duels;
import me.angylo.elotecraftDuels.Settings.Reward;
import me.angylo.elotecraftDuels.match.Rewards;
import me.angylo.elotecraftDuels.stats.Divisions;
import me.angylo.elotecraftDuels.stats.Seasons;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

/**
 * {@code /duels season}: the season running, and {@code end} to finish it: ratings are archived and reset, and each
 * player's division season reward is paid. It cannot be undone, so {@code end} only warns, and {@code end confirm}
 * within {@value #CONFIRM_SECONDS} seconds does it.
 */
final class SeasonAdminCommand {

    private static final int CONFIRM_SECONDS = 30;
    private static final String CONFIRM = "confirm";

    private final Duels duels;
    private final Messages messages;
    private final Rewards rewards;
    /** Sender name to the time their {@code end confirm} must come by. */
    private final Map<String, Long> armed = new HashMap<>();
    private boolean ending;

    SeasonAdminCommand(Duels duels) {
        this.duels = duels;
        this.messages = duels.messages();
        this.rewards = new Rewards(duels.plugin(), messages, duels::settings);
    }

    CommandBuilder node() {
        return CommandBuilder.create("season").permission("duels.admin.season")
                .executes((sender, args) -> messages.send(sender, "admin.season.info", season(duels.seasons().current())))
                .sub("end", null, this::end, (sender, args) -> args.length == 1 ? Args.filter(List.of(CONFIRM), args) : List.of());
    }

    private void end(CommandSender sender, String[] args) {
        if (ending) {
            messages.send(sender, "admin.season.busy");
            return;
        }
        long now = System.currentTimeMillis();
        if (args.length == 0 || !args[0].equalsIgnoreCase(CONFIRM)) {
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
        ending = true;
        messages.send(sender, "admin.season.ending", season(duels.seasons().current()));
        duels.seasons().end(duels.kits().names()).whenComplete((ended, error) -> {
            ending = false;
            if (error != null) {
                duels.plugin().getLogger().log(Level.SEVERE, "Could not end the duel season; nothing was changed", error);
                messages.send(sender, "admin.season.failed");
                return;
            }
            duels.stats().seasonReset();
            int paid = payRewards(ended);
            duels.plugin().getLogger().info("Ended duel season " + ended.season() + ": archived " + ended.ratings()
                    + " ratings, paid " + paid + " season rewards");
            for (Player online : Bukkit.getOnlinePlayers()) {
                messages.send(online, "season.ended", season(ended.season()),
                        Placeholder.unparsed("next", String.valueOf(ended.season() + 1)));
            }
            messages.send(sender, "admin.season.ended", season(ended.season()),
                    Placeholder.unparsed("ratings", String.valueOf(ended.ratings())), Placeholder.unparsed("rewards", String.valueOf(paid)));
        });
    }

    /** Each player's division reward, by the overall rating they ended the season with; returns how many were paid. */
    private int payRewards(Seasons.Ended ended) {
        Divisions divisions = duels.settings().ranked().divisions();
        int paid = 0;
        for (Seasons.Standing standing : ended.standings()) {
            Optional<Divisions.Division> division = divisions.of(standing.elo());
            if (division.isEmpty() || division.get().seasonReward().equals(Reward.NONE)) {
                continue;
            }
            rewards.giveSeason(Bukkit.getOfflinePlayer(standing.player()), standing.name(), division.get().seasonReward(),
                    Map.of("<player>", standing.name(), "<division>", Text.plain(Text.mm(division.get().name())),
                            "<elo>", String.valueOf(standing.elo()), "<season>", String.valueOf(ended.season())));
            paid++;
        }
        return paid;
    }

    private static TagResolver season(int number) {
        return Placeholder.unparsed("season", String.valueOf(number));
    }
}
