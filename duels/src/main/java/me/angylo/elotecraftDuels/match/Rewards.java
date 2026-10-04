package me.angylo.elotecraftDuels.match;

import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.Settings.Reward;
import me.angylo.elotecraftDuels.hook.VaultEconomy;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandException;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Pays out config.yml's {@code rewards} for a won duel: money through Vault and console commands. */
final class Rewards {

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final Logger logger;
    private final Messages messages;
    private final Supplier<Settings> settings;
    private final VaultEconomy economy;

    Rewards(Plugin plugin, Messages messages, Supplier<Settings> settings) {
        this.logger = plugin.getLogger();
        this.messages = messages;
        this.settings = settings;
        this.economy = new VaultEconomy(logger);
    }

    void give(Player winner, Player loser, Match match) {
        Settings current = settings.get();
        give(winner, current.winReward(), "match.reward-win", winner, loser, match);
        give(loser, current.lossReward(), "match.reward-loss", winner, loser, match);
    }

    private void give(Player player, Reward reward, String messageKey, Player winner, Player loser, Match match) {
        if (reward.money() > 0) {
            economy.deposit(player, reward.money()).ifPresent(amount ->
                    messages.send(player, messageKey, Placeholder.unparsed("amount", amount)));
        }
        if (!reward.commands().isEmpty() && !(SAFE_NAME.matcher(winner.getName()).matches()
                && SAFE_NAME.matcher(loser.getName()).matches())) {
            // Offline-mode servers accept names like "@a", which would become a selector in the command.
            logger.warning("Skipped duel reward commands: " + winner.getName() + " or " + loser.getName() + " is not a normal player name");
            return;
        }
        for (String command : reward.commands()) {
            String filled = command.replace("<winner>", winner.getName())
                    .replace("<loser>", loser.getName())
                    .replace("<kit>", match.kit().name())
                    .replace("<arena>", match.arena().name());
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), filled);
            } catch (CommandException e) {
                logger.log(Level.WARNING, "Duel reward command failed: " + filled, e);
            }
        }
    }
}
