package me.angylo.elotecraftDuels.hook;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * Optional Vault payouts and bet stakes. Vault's classes are only touched in {@link Hook}, which is loaded only while
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

    /** Whether Vault and an economy plugin are there to take money. */
    public boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("Vault") && Hook.economy() != null;
    }

    /** Whether {@code player} has {@code amount}; false without an economy. */
    public boolean has(OfflinePlayer player, double amount) {
        return available() && Hook.economy().has(player, amount);
    }

    /**
     * Takes {@code amount} from {@code player}. Main thread only.
     *
     * @return false if there is no economy or the payment failed (logged)
     */
    public boolean withdraw(OfflinePlayer player, double amount) {
        return available() && Hook.withdraw(this, player, amount);
    }

    /** {@code amount} as the economy plugin writes money, or the plain number without one. */
    public String format(double amount) {
        return available() ? Hook.economy().format(amount) : String.valueOf(amount);
    }

    private void warnMissing(String reason) {
        if (!warnedMissing) {
            warnedMissing = true;
            logger.warning(reason + ", so duel money rewards are skipped; set them to 0 in config.yml to hide this");
        }
    }

    private static final class Hook {

        static Economy economy() {
            RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
            return provider == null ? null : provider.getProvider();
        }

        static boolean withdraw(VaultEconomy owner, OfflinePlayer player, double amount) {
            EconomyResponse response = economy().withdrawPlayer(player, amount);
            if (!response.transactionSuccess()) {
                owner.logger.warning("Could not take a duel bet of " + amount + " from " + player.getName() + ": " + response.errorMessage);
            }
            return response.transactionSuccess();
        }

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
