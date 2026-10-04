package me.angylo.elotecraftDuels;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/** 1v1 duels built on ElotecraftAPI; everything is wired in {@link Duels}. */
public final class ElotecraftDuels extends JavaPlugin {

    private Duels duels;

    @Override
    public void onEnable() {
        try {
            duels = Duels.start(this);
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Could not start: " + e.getMessage() + ". Disabling ElotecraftDuels.", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (duels != null) {
            duels.shutdown();
            duels = null;
        }
    }
}
