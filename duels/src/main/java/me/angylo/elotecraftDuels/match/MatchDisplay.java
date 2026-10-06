package me.angylo.elotecraftDuels.match;

import me.angylo.elotecraftAPI.util.Durations;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.match.Match.EndReason;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Everything players see and hear during a match: chat, titles, the boss bar and effects. */
final class MatchDisplay {

    private final Messages messages;
    private final Supplier<Settings> settings;

    MatchDisplay(Messages messages, Supplier<Settings> settings) {
        this.messages = messages;
        this.settings = settings;
    }

    /** {@code <kit>} and {@code <arena>}, with their display names. */
    static TagResolver[] setup(Match match) {
        return new TagResolver[]{
                Placeholder.component("kit", Text.mm(match.kit().displayName())),
                Placeholder.component("arena", Text.mm(match.arena().displayName()))};
    }

    void starting(Match match) {
        for (Player fighter : match.fighters()) {
            messages.send(fighter, "match.starting", with(setup(match), opponent(match, fighter)));
        }
    }

    void countdown(Match match, int seconds) {
        for (Player fighter : match.fighters()) {
            title(fighter, "match.countdown-title", "match.countdown-subtitle",
                    Placeholder.unparsed("seconds", String.valueOf(seconds)), opponent(match, fighter));
            settings.get().effects().play(fighter, "countdown");
        }
    }

    void fightStarted(Match match) {
        for (Player fighter : match.fighters()) {
            title(fighter, "match.fight-title", "match.fight-subtitle", opponent(match, fighter));
            settings.get().effects().play(fighter, "fight-start");
        }
        if (settings.get().bossBar()) {
            BossBar bar = BossBar.bossBar(timeLeftText(match.maxFightSeconds()), 1f, settings.get().bossBarColor(),
                    BossBar.Overlay.PROGRESS);
            match.bossBar(bar);
            match.participants().forEach(player -> player.showBossBar(bar));
        }
    }

    void timeLeft(Match match, int seconds) {
        BossBar bar = match.bossBar();
        if (bar != null) {
            bar.name(timeLeftText(seconds));
            bar.progress(Math.clamp((float) seconds / Math.max(1, match.maxFightSeconds()), 0f, 1f));
        }
    }

    void removeBossBar(Match match) {
        BossBar bar = match.bossBar();
        if (bar != null) {
            match.participants().forEach(player -> player.hideBossBar(bar));
            match.bossBar(null);
        }
    }

    /** Titles, effects and a summary in chat for a duel someone won. */
    void result(Match match, Player winner, Player loser, EndReason reason) {
        TagResolver[] tags = with(setup(match),
                Placeholder.unparsed("winner", winner.getName()),
                Placeholder.unparsed("loser", loser.getName()),
                Placeholder.unparsed("health", String.format(Locale.ROOT, "%.1f", winner.getHealth() / 2)),
                Placeholder.unparsed("time", Durations.format(Duration.ofSeconds(match.fightSeconds()))));
        String summary = switch (reason) {
            case QUIT -> "match.result-quit";
            case FORFEIT -> "match.result-forfeit";
            default -> "match.result";
        };
        for (Player participant : match.participants()) {
            messages.send(participant, summary, tags);
        }
        if (match.isParticipant(winner)) {
            title(winner, "match.victory-title", "match.victory-subtitle", Placeholder.unparsed("opponent", loser.getName()));
            settings.get().effects().play(winner, "victory");
        }
        if (match.isParticipant(loser)) {
            title(loser, "match.defeat-title", "match.defeat-subtitle", with(tags, Placeholder.unparsed("opponent", winner.getName())));
            settings.get().effects().play(loser, "defeat");
        }
    }

    /** Tells both fighters, if still there, how a ranked result moved their rating. */
    void eloChange(Match match, Player winner, Player loser, int change, int winnerElo, int loserElo) {
        if (match.isParticipant(winner)) {
            messages.send(winner, "match.elo-gained", Placeholder.unparsed("change", String.valueOf(change)),
                    Placeholder.unparsed("elo", String.valueOf(winnerElo)));
        }
        if (match.isParticipant(loser)) {
            messages.send(loser, "match.elo-lost", Placeholder.unparsed("change", String.valueOf(change)),
                    Placeholder.unparsed("elo", String.valueOf(loserElo)));
        }
    }

    /** In a team fight, {@code <first>} is the first team and {@code <second>} everyone else. */
    void draw(Match match) {
        TagResolver[] tags = with(setup(match),
                Placeholder.unparsed("first", names(match.teams().getFirst())),
                Placeholder.unparsed("second", match.opponentNames(match.first().getUniqueId())));
        for (Player participant : match.participants()) {
            messages.send(participant, match.isDuel() ? "match.result-draw" : "match.result-draw-team", tags);
        }
        for (Player fighter : match.fighters()) {
            if (match.isParticipant(fighter)) {
                title(fighter, "match.draw-title", "match.draw-subtitle");
                settings.get().effects().play(fighter, "draw");
            }
        }
    }

    /** Tells everyone in a team fight that {@code fighter} is out. */
    void knockedOut(Match match, Player fighter) {
        for (Player participant : match.participants()) {
            messages.send(participant, "match.knocked-out", Placeholder.unparsed("player", fighter.getName()));
        }
    }

    /** Titles and a summary for a team fight that team {@code winnerTeam} won. */
    void teamResult(Match match, int winnerTeam) {
        List<Player> winners = match.teams().get(winnerTeam);
        TagResolver[] tags = with(setup(match), Placeholder.unparsed("winners", names(winners)),
                Placeholder.unparsed("time", Durations.format(Duration.ofSeconds(match.fightSeconds()))));
        for (Player participant : match.participants()) {
            messages.send(participant, "match.result-team", tags);
        }
        for (Player fighter : match.fighters()) {
            if (!match.isParticipant(fighter)) {
                continue;
            }
            if (winners.contains(fighter)) {
                title(fighter, "match.victory-title", "match.victory-subtitle", opponent(match, fighter));
                settings.get().effects().play(fighter, "victory");
            } else {
                title(fighter, "match.defeat-title", "match.team-defeat-subtitle", tags);
                settings.get().effects().play(fighter, "defeat");
            }
        }
    }

    /** A clickable offer to challenge the same opponent again. */
    void rematchOffer(Player player, Player opponent) {
        messages.send(player, "match.rematch-offer",
                Placeholder.unparsed("opponent", opponent.getName()),
                Placeholder.styling("rematch", ClickEvent.runCommand("/duel rematch"),
                        HoverEvent.showText(messages.get(player, "match.rematch-hover"))));
    }

    private void title(Player player, String titleKey, String subtitleKey, TagResolver... tags) {
        player.showTitle(Title.title(messages.get(player, titleKey, tags), messages.get(player, subtitleKey, tags),
                settings.get().titleTimes()));
    }

    private Component timeLeftText(int seconds) {
        return messages.get("match.boss-bar", Placeholder.unparsed("time", Durations.format(Duration.ofSeconds(seconds))));
    }

    /** {@code <opponent>}: the other fighter, or everyone fighting against {@code fighter}. */
    private static TagResolver opponent(Match match, Player fighter) {
        return Placeholder.unparsed("opponent", match.opponentNames(fighter.getUniqueId()));
    }

    private static String names(List<Player> players) {
        return String.join(", ", players.stream().map(Player::getName).toList());
    }

    static TagResolver[] with(TagResolver[] tags, TagResolver... more) {
        TagResolver[] all = new TagResolver[tags.length + more.length];
        System.arraycopy(tags, 0, all, 0, tags.length);
        System.arraycopy(more, 0, all, tags.length, more.length);
        return all;
    }
}
