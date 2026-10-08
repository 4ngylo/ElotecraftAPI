package me.angylo.elotecraftDuels.stats;

import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Settings.Reward;
import net.kyori.adventure.text.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Named rating bands (config.yml {@code ranked.divisions}), lowest first; none means divisions are off. */
public record Divisions(List<Division> list) {

    public static final Divisions NONE = new Divisions(List.of());

    /**
     * @param name         MiniMessage text
     * @param min          the lowest rating in this division
     * @param seasonReward paid to players whose overall rating is in this division when a season ends
     */
    public record Division(String name, int min, Reward seasonReward) {

        public Division(String name, int min) {
            this(name, min, Reward.NONE);
        }
    }

    public Divisions {
        list = list.stream().sorted(Comparator.comparingInt(Division::min)).toList();
    }

    /** The highest division {@code elo} reaches; empty below the lowest or with divisions off. */
    public Optional<Division> of(int elo) {
        Division found = null;
        for (Division division : list) {
            if (elo >= division.min()) {
                found = division;
            }
        }
        return Optional.ofNullable(found);
    }

    /** The name of {@code elo}'s division, or nothing. */
    public Component name(int elo) {
        return of(elo).map(division -> Text.mm(division.name())).orElse(Component.empty());
    }
}
