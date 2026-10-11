package me.angylo.elotecraftDuels.match;

import me.angylo.elotecraftAPI.util.Durations;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Cosmetics;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.match.Match.EndReason;
import me.angylo.elotecraftDuels.stats.Divisions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Everything players see and hear during a match: chat, titles and effects. */
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
            // A tournament fight is a duel to its fighters; the tournament announces its rounds.
            if (match.type() == Match.Type.EVENT && !match.options().bracket()) {
                messages.send(fighter, "event.starting", with(setup(match), host(match),
                        Placeholder.unparsed("players", String.valueOf(match.fighters().size()))));
            } else {
                messages.send(fighter, "match.starting", with(setup(match), opponent(match, fighter)));
            }
        }
    }

    /** In a fight of rounds, the subtitle shows the round and the score instead of the opponent. */
    void countdown(Match match, int seconds) {
        String subtitle = match.roundsToWin() > 1 ? "match.round-countdown-subtitle" : "match.countdown-subtitle";
        for (Player fighter : match.fighters()) {
            title(fighter, "match.countdown-title", subtitle, Placeholder.unparsed("seconds", String.valueOf(seconds)),
                    opponent(match, fighter), round(match, fighter));
            settings.get().effects().play(fighter, "countdown");
        }
    }

    void fightStarted(Match match) {
        for (Player fighter : match.fighters()) {
            title(fighter, "match.fight-title", "match.fight-subtitle", opponent(match, fighter));
            settings.get().effects().play(fighter, "fight-start");
        }
    }

    /** Titles, effects and a summary in chat for a duel someone won; the names in it open the fighters' inventories. */
    void result(Match match, Player winner, Player loser, EndReason reason) {
        TagResolver[] tags = with(setup(match),
                Placeholder.unparsed("health", String.format(Locale.ROOT, "%.1f", winner.getHealth() / 2)),
                Placeholder.unparsed("time", Durations.format(Duration.ofSeconds(match.fightSeconds()))),
                Placeholder.unparsed("score", match.score(match.teamOf(winner.getUniqueId()))));
        String summary = switch (reason) {
            case QUIT -> "match.result-quit";
            case FORFEIT -> "match.result-forfeit";
            default -> match.roundsToWin() > 1 ? "match.result-rounds" : "match.result";
        };
        for (Player participant : match.participants()) {
            messages.send(participant, summary, with(tags, inventoryLink(participant, match, "winner", winner.getName()),
                    inventoryLink(participant, match, "loser", loser.getName())));
        }
        if (match.isParticipant(winner)) {
            title(winner, "match.victory-title", "match.victory-subtitle", Placeholder.unparsed("opponent", loser.getName()));
            settings.get().effects().play(winner, "victory");
        }
        if (match.isParticipant(loser)) {
            title(loser, "match.defeat-title", "match.defeat-subtitle", with(tags, Placeholder.unparsed("winner", winner.getName()),
                    Placeholder.unparsed("loser", loser.getName()), Placeholder.unparsed("opponent", winner.getName())));
            settings.get().effects().play(loser, "defeat");
        }
    }

    /** Tells everyone that {@code team} won the round that just ended, and the fighters the score. */
    void roundWon(Match match, int team) {
        TagResolver winner = Placeholder.unparsed("winner", names(match.teams().get(team)));
        for (Player participant : match.participants()) {
            messages.send(participant, "match.round-won", winner, Placeholder.unparsed("round", String.valueOf(match.round())),
                    Placeholder.unparsed("score", match.score(team)));
        }
        for (Player fighter : match.fighters()) {
            if (match.isParticipant(fighter)) {
                title(fighter, "match.round-won-title", "match.round-won-subtitle", winner, round(match, fighter));
                settings.get().effects().play(fighter, "round-won");
            }
        }
    }

    /**
     * Tells both fighters, if still there, how a ranked result moved their rating in the duel's kit, and
     * when it took them into another division.
     *
     * @param winnerElo the winner's rating before the duel
     * @param loserElo  the loser's rating before the duel
     */
    void eloChange(Match match, Player winner, Player loser, int change, int winnerElo, int loserElo) {
        rating(match, winner, "match.elo-gained", change, winnerElo, winnerElo + change);
        rating(match, loser, "match.elo-lost", change, loserElo, loserElo - change);
    }

    private void rating(Match match, Player player, String key, int change, int before, int after) {
        if (!match.isParticipant(player)) {
            return;
        }
        Divisions divisions = settings.get().ranked().divisions();
        TagResolver[] tags = with(setup(match), Placeholder.unparsed("change", String.valueOf(change)),
                Placeholder.unparsed("elo", String.valueOf(after)), Placeholder.component("division", divisions.name(after)));
        messages.send(player, key, tags);
        Optional<Divisions.Division> was = divisions.of(before);
        Optional<Divisions.Division> now = divisions.of(after);
        if (now.isPresent() && !now.equals(was)) {
            messages.send(player, after > before ? "match.division-up" : "match.division-down", tags);
        }
    }

    /**
     * In a team fight, {@code <first>} is the first team and {@code <second>} everyone else. In a duel they are the
     * fighters, each opening their inventory.
     */
    void draw(Match match) {
        TagResolver[] tags = with(setup(match),
                Placeholder.unparsed("first", names(match.teams().getFirst())),
                Placeholder.unparsed("second", match.opponentNames(match.first().getUniqueId())));
        for (Player participant : match.participants()) {
            if (match.isDuel()) {
                messages.send(participant, "match.result-draw", with(setup(match),
                        inventoryLink(participant, match, "first", match.first().getName()),
                        inventoryLink(participant, match, "second", match.second().getName())));
            } else {
                messages.send(participant, "match.result-draw-team", tags);
            }
        }
        for (Player fighter : match.fighters()) {
            if (match.isParticipant(fighter)) {
                title(fighter, "match.draw-title", "match.draw-subtitle");
                settings.get().effects().play(fighter, "draw");
            }
        }
    }

    /** Tells everyone in a team fight that {@code fighter} is out. */
    /**
     * {@code fighter} is out: {@code killer}'s kill effect plays, and everyone reads their kill message, or the
     * plain knocked-out line while the fight goes on (the result says the rest).
     *
     * @param killer the opponent who gets the kill; null for a quit, a forfeit or a fall nobody caused
     */
    void knockedOut(Match match, Player fighter, Player killer, boolean fightGoesOn) {
        Cosmetics cosmetics = settings.get().cosmetics();
        Optional<String> killMessage = Optional.empty();
        if (killer != null) {
            cosmetics.playKillEffect(killer, fighter, match.participants());
            // One without a text in messages.yml (Duels warns at load) falls back to the plain line.
            killMessage = cosmetics.chosen(killer, Cosmetics.Kind.KILL_MESSAGE).map(chosen -> "kill-messages." + chosen.id())
                    .filter(messages::has);
        }
        String plain = match.type() != Match.Type.FFA ? "match.knocked-out" : killer == null ? "ffa.died" : "ffa.killed";
        // The victim's death message stands in for a killer without a kill message.
        Optional<String> deathMessage = cosmetics.chosen(fighter, Cosmetics.Kind.DEATH_MESSAGE)
                .map(chosen -> "death-messages." + chosen.id()).filter(messages::has);
        Optional<String> key = killMessage.or(() -> deathMessage).or(() -> fightGoesOn ? Optional.of(plain) : Optional.empty());
        if (key.isEmpty()) {
            return;
        }
        TagResolver[] tags = {Placeholder.unparsed("player", fighter.getName()), Placeholder.unparsed("victim", fighter.getName()),
                Placeholder.unparsed("killer", killer == null ? "" : killer.getName())};
        for (Player participant : match.participants()) {
            messages.send(participant, key.get(), tags);
        }
    }

    /** Bridge and bed fight: {@code fighter} was knocked out and is back at their spawn. */
    void respawned(Match match, Player fighter) {
        title(fighter, "match.respawned-title", "match.respawned-subtitle");
    }

    /** Bridge: {@code scorer} walked into the other side's goal. */
    void scored(Match match, Player scorer) {
        for (Player participant : match.participants()) {
            messages.send(participant, "match.scored", Placeholder.unparsed("player", scorer.getName()));
        }
    }

    /** MLG Rush: {@code breaker} broke the other side's bed, which wins their side the round. */
    void bedScored(Match match, Player breaker) {
        for (Player participant : match.participants()) {
            messages.send(participant, "match.bed-scored", Placeholder.unparsed("player", breaker.getName()));
        }
    }

    /** Bed fight: {@code breaker} broke {@code team}'s bed; that team hears it in a title. */
    void bedBroken(Match match, int team, Player breaker) {
        TagResolver[] tags = {Placeholder.unparsed("player", breaker.getName()), Placeholder.unparsed("team", names(match.teams().get(team)))};
        for (Player participant : match.participants()) {
            messages.send(participant, "match.bed-broken", tags);
        }
        for (Player fighter : match.teams().get(team)) {
            if (match.isParticipant(fighter)) {
                title(fighter, "match.bed-broken-title", "match.bed-broken-subtitle", tags);
            }
        }
    }

    /**
     * Titles and a summary for a team fight the teams {@code winnerTeams} won. The result of an event goes to
     * the whole server unless config.yml {@code events.broadcast-result} is off.
     */
    void teamResult(Match match, List<Integer> winnerTeams) {
        List<Player> winners = winnerTeams.stream().flatMap(team -> match.teams().get(team).stream()).toList();
        TagResolver[] tags = with(setup(match), Placeholder.unparsed("winners", names(winners)),
                Placeholder.unparsed("time", Durations.format(Duration.ofSeconds(match.fightSeconds()))));
        if (match.type() == Match.Type.EVENT && !match.options().bracket()) {
            TagResolver[] eventTags = with(tags, host(match));
            boolean everyone = settings.get().events().broadcastResult();
            (everyone ? List.copyOf(Bukkit.getOnlinePlayers()) : match.participants())
                    .forEach(player -> messages.send(player, "event.result", eventTags));
        } else {
            for (Player participant : match.participants()) {
                messages.send(participant, "match.result-team", tags);
            }
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

    /** Clickable names of every fighter, each opening their inventory as the fight left it. For fights but duels. */
    void inventories(List<Player> viewers, UUID id, List<FighterResult> fighters) {
        for (Player viewer : viewers) {
            List<Component> names = fighters.stream().map(fighter -> openInventory(viewer, id, fighter.name(),
                    messages.get(viewer, "match.inventory-entry", Placeholder.unparsed("player", fighter.name())))).toList();
            messages.send(viewer, "match.inventories",
                    Placeholder.component("players", Component.join(JoinConfiguration.spaces(), names)));
        }
    }

    /** {@code <tag>}: {@code name}, opening that fighter's inventory once the match kept it. */
    private TagResolver inventoryLink(Player viewer, Match match, String tag, String name) {
        UUID id = match.resultsId();
        return id == null ? Placeholder.unparsed(tag, name)
                : Placeholder.component(tag, openInventory(viewer, id, name, Component.text(name)));
    }

    private Component openInventory(Player viewer, UUID id, String name, Component text) {
        return text.clickEvent(ClickEvent.runCommand("/duel inventory " + id + " " + name))
                .hoverEvent(HoverEvent.showText(messages.get(viewer, "match.inventory-hover", Placeholder.unparsed("player", name))));
    }

    private void title(Player player, String titleKey, String subtitleKey, TagResolver... tags) {
        player.showTitle(Title.title(messages.get(player, titleKey, tags), messages.get(player, subtitleKey, tags),
                settings.get().titleTimes()));
    }


    private static TagResolver host(Match match) {
        return Placeholder.unparsed("host", match.options().host());
    }

    /** {@code <round>} and {@code <score>}: the round being fought and {@code fighter}'s rounds against the others'. */
    private static TagResolver round(Match match, Player fighter) {
        return TagResolver.resolver(Placeholder.unparsed("round", String.valueOf(match.round())),
                Placeholder.unparsed("score", match.score(match.teamOf(fighter.getUniqueId()))));
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
