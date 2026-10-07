package me.angylo.elotecraftDuels.hook;

import me.angylo.elotecraftDuels.Duels;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.match.QueueManager;
import me.angylo.elotecraftDuels.party.Party;
import me.angylo.elotecraftDuels.stats.PlayerStats;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@code %duels_<name>%}. PlaceholderAPI may ask from any thread, so this only reads thread-safe caches
 * and never the database: stats are those of online players, 0 for offline ones.
 */
final class DuelsExpansion extends PlaceholderExpansion {

    private final Duels duels;

    DuelsExpansion(Duels duels) {
        this.duels = duels;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "duels";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", duels.plugin().getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return duels.plugin().getPluginMeta().getVersion();
    }

    /** Kept across {@code /papi reload}; unregistered when the plugin disables. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @NotNull List<String> getPlaceholders() {
        return List.of("%duels_wins%", "%duels_losses%", "%duels_win_streak%", "%duels_best_win_streak%",
                "%duels_win_rate%", "%duels_elo%", "%duels_in_match%", "%duels_opponent%", "%duels_kit%", "%duels_arena%",
                "%duels_queue%", "%duels_queue_type%", "%duels_party_size%", "%duels_party_leader%", "%duels_active_matches%");
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (params.equals("active_matches")) {
            return String.valueOf(duels.matches().activeMatches());
        }
        if (player == null) {
            return "";
        }
        Optional<Match> match = duels.matches().matchOf(player.getUniqueId());
        return switch (params) {
            case "wins" -> stat(player, PlayerStats::wins);
            case "losses" -> stat(player, PlayerStats::losses);
            case "win_streak" -> stat(player, PlayerStats::winStreak);
            case "best_win_streak" -> stat(player, PlayerStats::bestWinStreak);
            case "win_rate" -> stat(player, PlayerStats::winRate);
            case "elo" -> stat(player, PlayerStats::elo);
            case "in_match" -> String.valueOf(match.isPresent());
            case "opponent" -> match.filter(m -> m.teamOf(player.getUniqueId()) >= 0)
                    .map(m -> m.opponentNames(player.getUniqueId())).orElse("");
            case "kit" -> match.map(m -> m.kit().name()).orElse("");
            case "arena" -> match.map(m -> m.arena().name()).orElse("");
            case "party_size" -> String.valueOf(duels.parties().partyOf(player.getUniqueId()).map(Party::size).orElse(0));
            case "party_leader" -> duels.parties().partyOf(player.getUniqueId())
                    .map(party -> Optional.ofNullable(Bukkit.getOfflinePlayer(party.leader()).getName()).orElse("")).orElse("");
            case "queue" -> duels.queues().queued(player.getUniqueId()).map(QueueManager.QueueId::kit).orElse("");
            case "queue_type" -> duels.queues().queued(player.getUniqueId()).map(id -> id.ranked() ? "ranked" : "unranked").orElse("");
            default -> null;
        };
    }

    private String stat(OfflinePlayer player, Function<PlayerStats, Integer> value) {
        return String.valueOf(duels.stats().cached(player.getUniqueId()).map(value).orElse(0));
    }

}
