package me.angylo.elotecraftDuels.match;

import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.Settings.Reward;
import me.angylo.elotecraftDuels.hook.VaultEconomy;
import me.angylo.elotecraftDuels.kit.Kit;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandException;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Pays out config.yml's {@code rewards} plus the kit's own {@code rewards} for a won duel,
 * {@code events.reward} for each winner of a hosted event and division season rewards: money through Vault
 * and console commands.
 */
public final class Rewards {

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final Logger logger;
    private final Messages messages;
    private final Supplier<Settings> settings;
    private final VaultEconomy economy;

    public Rewards(Plugin plugin, Messages messages, Supplier<Settings> settings) {
        this.logger = plugin.getLogger();
        this.messages = messages;
        this.settings = settings;
        this.economy = new VaultEconomy(logger);
    }

    void give(Player winner, Player loser, Match match) {
        Settings current = settings.get();
        Map<String, String> tags = Map.of("<winner>", winner.getName(), "<loser>", loser.getName(),
                "<kit>", match.kit().name(), "<arena>", match.arena().name());
        List<String> names = List.of(winner.getName(), loser.getName());
        Kit.Rewards kit = match.kit().rewards();
        // Added up, so a player gets one deposit and one message.
        give(winner, current.winReward().plus(kit.win()), "match.reward-win", tags, names);
        give(loser, current.lossReward().plus(kit.loss()), "match.reward-loss", tags, names);
    }

    /** The prize of one winner of a hosted event. */
    /** The event reward, to a winner of an event fight or a tournament's champion. */
    public void giveEvent(Player winner, String host, String kit, String arena) {
        give(winner, settings.get().events().reward(), "event.reward", Map.of("<winner>", winner.getName(), "<host>", host,
                "<kit>", kit, "<arena>", arena), List.of(winner.getName(), host));
    }

    /**
     * A division's season reward to {@code player}, online or not; {@code tags} fill the commands. Online players are
     * told about the money.
     */
    public void giveSeason(OfflinePlayer player, String name, Reward reward, Map<String, String> tags) {
        give(player, reward, "season.reward", tags, List.of(name));
    }

    /** @param names the player names among the tags, checked before they go into a console command */
    private void give(OfflinePlayer player, Reward reward, String messageKey, Map<String, String> tags, List<String> names) {
        if (reward.money() > 0) {
            economy.deposit(player, reward.money()).ifPresent(amount -> {
                if (player.getPlayer() != null) {
                    messages.send(player.getPlayer(), messageKey, Placeholder.unparsed("amount", amount));
                }
            });
        }
        if (!reward.commands().isEmpty() && !names.stream().allMatch(name -> SAFE_NAME.matcher(name).matches())) {
            // Offline-mode servers accept names like "@a", which would become a selector in the command.
            logger.warning("Skipped reward commands: " + String.join(" or ", names) + " is not a normal player name");
            return;
        }
        for (String command : reward.commands()) {
            String filled = command;
            for (Map.Entry<String, String> tag : tags.entrySet()) {
                filled = filled.replace(tag.getKey(), tag.getValue());
            }
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), filled);
            } catch (CommandException e) {
                logger.log(Level.WARNING, "Reward command failed: " + filled, e);
            }
        }
    }
}
