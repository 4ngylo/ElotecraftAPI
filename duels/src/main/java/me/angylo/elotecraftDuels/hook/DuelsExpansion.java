package me.angylo.elotecraftDuels.hook;

import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Duels;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.match.QueueManager;
import me.angylo.elotecraftDuels.party.Party;
import me.angylo.elotecraftDuels.stats.PlayerStats;
import me.angylo.elotecraftDuels.stats.SeasonEnder;
import me.angylo.elotecraftDuels.stats.Seasons;
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

    private static final String ELO_PREFIX = "elo_";
    private static final String DIVISION_PREFIX = "division_";
    private static final String PEAK_PREFIX = "peak_";

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
                "%duels_win_rate%", "%duels_elo%", "%duels_elo_<kit>%", "%duels_division%", "%duels_division_<kit>%", "%duels_peak%", "%duels_peak_<kit>%", "%duels_season%", "%duels_season_name%", "%duels_season_days%", "%duels_season_started%",
                "%duels_season_ends_in%", "%duels_in_match%", "%duels_opponent%", "%duels_kit%", "%duels_arena%",
                "%duels_queue%", "%duels_queue_type%", "%duels_party_size%", "%duels_party_leader%", "%duels_active_matches%");
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (params.equals("active_matches")) {
            return String.valueOf(duels.matches().activeMatches());
        }
        Seasons.Info season = duels.seasons().info();
        switch (params) {
            case "season_name" -> {
                return season.name().isEmpty() ? String.valueOf(season.season()) : Text.plain(Text.mm(season.name()));
            }
            case "season_days" -> {
                return String.valueOf(SeasonEnder.days(season));
            }
            case "season_started" -> {
                return SeasonEnder.date(season.startedAt());
            }
            case "season_ends_in" -> {
                return SeasonEnder.left(season).map(SeasonEnder::length).orElse("");
            }
            default -> {
            }
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
            case "kills" -> stat(player, found -> found.progress().kills());
            case "deaths" -> stat(player, found -> found.progress().deaths());
            case "xp" -> stat(player, found -> found.progress().xp());
            case "level" -> stat(player, found -> found.progress().level(duels.settings().progression().levelXp()));
            case "elo" -> stat(player, found -> found.overallElo(duels.kits().names()));
            case "division" -> division(player, null);
            case "peak" -> stat(player, found -> found.peak(duels.kits().names()));
            case "season" -> String.valueOf(duels.seasons().current());
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
            default -> kitRating(player, params);
        };
    }

    /** {@code elo_<kit>}, {@code division_<kit>} and {@code peak_<kit>} of an existing kit; null for anything else. */
    private String kitRating(OfflinePlayer player, String params) {
        String prefix = params.startsWith(DIVISION_PREFIX) ? DIVISION_PREFIX : params.startsWith(PEAK_PREFIX) ? PEAK_PREFIX
                : params.startsWith(ELO_PREFIX) ? ELO_PREFIX : null;
        if (prefix == null) {
            return null;
        }
        String kit = params.substring(prefix.length());
        if (duels.kits().get(kit).isEmpty()) {
            return null;
        }
        return switch (prefix) {
            case DIVISION_PREFIX -> division(player, kit);
            case PEAK_PREFIX -> stat(player, found -> found.peak(kit));
            default -> stat(player, found -> found.elo(kit));
        };
    }

    /** The plain name of the division of the rating in {@code kit}, or of the overall one when null. */
    private String division(OfflinePlayer player, String kit) {
        return duels.stats().cached(player.getUniqueId())
                .map(found -> kit == null ? found.overallElo(duels.kits().names()) : found.elo(kit))
                .map(elo -> Text.plain(duels.settings().ranked().divisions().name(elo)))
                .orElse("");
    }

    private String stat(OfflinePlayer player, Function<PlayerStats, Integer> value) {
        return String.valueOf(duels.stats().cached(player.getUniqueId()).map(value).orElse(0));
    }

}
