package me.angylo.elotecraftDuels.hook;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * Optional Vault payouts. Vault's classes are only touched in {@link Hook}, which is loaded only while
 * Vault is enabled, so the plugin runs without it.
 */
public final class VaultEconomy {

    private final Logger logger;
    private boolean warnedMissing;

    public VaultEconomy(Logger logger) {
        this.logger = logger;
    }

    /**
     * Pays {@code amount} to {@code player}. Main thread only.
     *
     * @return the amount formatted by the economy plugin, or empty if Vault, an economy plugin or the
     * payment is missing (logged)
     */
    public Optional<String> deposit(OfflinePlayer player, double amount) {
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            warnMissing("Vault is not installed");
            return Optional.empty();
        }
        return Hook.deposit(this, player, amount);
    }

    private void warnMissing(String reason) {
        if (!warnedMissing) {
            warnedMissing = true;
            logger.warning(reason + ", so duel money rewards are skipped; set them to 0 in config.yml to hide this");
        }
    }

    private static final class Hook {

        static Optional<String> deposit(VaultEconomy owner, OfflinePlayer player, double amount) {
            RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (provider == null) {
                owner.warnMissing("No economy plugin is hooked into Vault");
                return Optional.empty();
            }
            Economy economy = provider.getProvider();
            EconomyResponse response = economy.depositPlayer(player, amount);
            if (!response.transactionSuccess()) {
                owner.logger.warning("Could not pay " + player.getName() + " a duel reward of " + amount + ": " + response.errorMessage);
                return Optional.empty();
            }
            return Optional.of(economy.format(amount));
        }
    }
}
