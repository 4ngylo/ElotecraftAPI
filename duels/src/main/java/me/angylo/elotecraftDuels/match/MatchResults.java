package me.angylo.elotecraftDuels.match;

import me.angylo.elotecraftAPI.util.Durations;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.match.Match.EndReason;
import me.angylo.elotecraftDuels.match.Match.Type;
import me.angylo.elotecraftDuels.match.MatchManager.Rematch;
import me.angylo.elotecraftDuels.stats.MatchHistory;
import me.angylo.elotecraftDuels.stats.PlayerStats;
import me.angylo.elotecraftDuels.stats.StatsService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * What a fight's result changes, for {@link MatchManager}: stats, ratings, history and rewards of duels, event
 * rewards, the result shown, rematch offers, the log line and the fighters' kept inventories. Main thread only.
 */
final class MatchResults {

    private static final long MILLIS_PER_TICK = 50;

    private final Logger logger;
    private final Supplier<Settings> settings;
    private final StatsService stats;
    private final MatchHistory history;
    private final Rewards rewards;
    private final MatchDisplay display;
    private final Map<UUID, Rematch> rematches = new HashMap<>();
    private final FightResults results = new FightResults();

    MatchResults(Logger logger, Supplier<Settings> settings, StatsService stats, MatchHistory history, Rewards rewards,
                 MatchDisplay display) {
        this.logger = logger;
        this.settings = settings;
        this.stats = stats;
        this.history = history;
        this.rewards = rewards;
        this.display = display;
    }

    /** Everything a result changes; {@code winnerTeams} empty for a draw. */
    void record(Match match, List<Integer> winnerTeams, EndReason reason) {
        keepResults(match);
        if (winnerTeams.isEmpty()) {
            display.draw(match);
        } else if (match.isDuel()) {
            endDuel(match, match.teams().get(winnerTeams.getFirst()).getFirst(), reason);
        } else {
            display.teamResult(match, winnerTeams);
            // Like duels, only a real fight pays out: not an event the last opponents quit or forfeited.
            // A tournament pays its champion only.
            if (match.type() == Type.EVENT && reason == EndReason.ELIMINATED && !match.options().bracket()) {
                winnerTeams.forEach(team -> match.teams().get(team).stream().filter(match::isParticipant)
                        .forEach(winner -> rewards.giveEvent(winner, match.options().host(), match.kit().name(), match.arena().name())));
            }
        }
        offerRematch(match);
        logResult(match, winnerTeams, reason);
    }

    /** {@code player}'s last opponent, while the rematch window is open. */
    Optional<Rematch> rematchOf(Player player) {
        Rematch rematch = rematches.get(player.getUniqueId());
        if (rematch == null || rematch.expired()) {
            rematches.remove(player.getUniqueId());
            return Optional.empty();
        }
        return Optional.of(rematch);
    }

    Optional<FighterResult> fightResult(String id, String name) {
        return results.get(id, name);
    }

    /** Every fighter of the fight whose inventories were kept under {@code id}. */
    List<FighterResult> kept(UUID id) {
        return results.all(id);
    }

    void purgeExpired() {
        rematches.values().removeIf(Rematch::expired);
        results.purgeExpired();
    }

    void clear() {
        rematches.clear();
        results.clear();
    }

    /** Stats, rating, rewards and the result of a duel {@code winner} won. */
    private void endDuel(Match match, Player winner, EndReason reason) {
        Player loser = match.opponentOf(winner);
        // Forfeits and quits move the rating too, so leaving a losing ranked duel does not save it.
        String kit = match.kit().name();
        int winnerElo = stats.elo(winner.getUniqueId(), kit);
        int loserElo = stats.elo(loser.getUniqueId(), kit);
        int eloChange = match.isRanked() ? PlayerStats.eloChange(winnerElo, loserElo, settings.get().ranked().kFactor()) : 0;
        if (match.isRanked()) {
            stats.recordResult(winner, loser, kit, eloChange);
        } else {
            stats.recordResult(winner, loser);
        }
        history.record(new MatchHistory.Duel(winner.getUniqueId(), winner.getName(), loser.getUniqueId(), loser.getName(),
                System.currentTimeMillis(), match.kit().name(), match.arena().name(), match.isRanked(), eloChange,
                match.fightSeconds(), reason.name().toLowerCase(Locale.ROOT), winner.getHealth()));
        // Only a real fight pays out, so two accounts cannot farm rewards by forfeiting to each other.
        if (reason == EndReason.ELIMINATED) {
            rewards.give(winner, loser, match);
        }
        display.result(match, winner, loser, reason);
        if (match.isRanked()) {
            display.eloChange(match, winner, loser, eloChange, winnerElo, loserElo);
        }
        if (match.isParticipant(loser) && !loser.isDead()) {
            loser.setGameMode(GameMode.SPECTATOR);
        }
    }

    /**
     * Keeps every fighter's final state for {@code /duel inventory}, before the result goes out: a duel's result
     * links the fighters' names to it. Other fights send the links once they are over.
     */
    private void keepResults(Match match) {
        match.fighters().forEach(match::recordFinal);
        match.resultsId(results.keep(match.finals()));
    }

    private void offerRematch(Match match) {
        if (!match.isDuel()) {
            return;
        }
        Player first = match.first();
        Player second = match.second();
        if (!match.isParticipant(first) || !match.isParticipant(second)) {
            return;
        }
        long expiresAt = Bukkit.getCurrentTick() + settings.get().rematchWindow().toMillis() / MILLIS_PER_TICK;
        for (Player player : List.of(first, second)) {
            Player opponent = match.opponentOf(player);
            rematches.put(player.getUniqueId(), new Rematch(opponent.getUniqueId(), opponent.getName(),
                    match.kit().name(), match.arena().name(), expiresAt));
            display.rematchOffer(player, opponent);
        }
    }

    private void logResult(Match match, List<Integer> winnerTeams, EndReason reason) {
        if (!settings.get().logResults()) {
            return;
        }
        String details = " (" + match.kit().name() + ", " + match.arena().name() + ", "
                + Durations.format(Duration.ofSeconds(match.fightSeconds())) + ")";
        String how = " by " + reason.name().toLowerCase(Locale.ROOT) + details;
        String fight = switch (match.type()) {
            case DUEL -> "Duel";
            case PARTY -> "Party fight";
            case EVENT -> match.options().host() + "'s event";
        };
        if (winnerTeams.isEmpty()) {
            logger.info(fight + " " + MatchManager.names(match.fighters(), " vs ") + " was a draw" + details);
        } else if (match.isDuel()) {
            Player winner = match.teams().get(winnerTeams.getFirst()).getFirst();
            logger.info(winner.getName() + " beat " + match.opponentOf(winner).getName() + how);
        } else {
            List<Player> winners = winnerTeams.stream().flatMap(team -> match.teams().get(team).stream()).toList();
            List<Player> losers = match.fighters().stream().filter(fighter -> !winners.contains(fighter)).toList();
            logger.info(MatchManager.names(winners, ", ") + " won " + (match.type() == Type.EVENT ? fight : "a party fight")
                    + " against " + MatchManager.names(losers, ", ") + how);
        }
    }
}
