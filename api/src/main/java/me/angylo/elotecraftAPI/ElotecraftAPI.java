package me.angylo.elotecraftAPI;

import me.angylo.elotecraftAPI.menu.MenuListener;
import org.bukkit.plugin.java.JavaPlugin;

public final class ElotecraftAPI extends JavaPlugin {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
    }

    @Override
    public void onDisable() {
        MenuListener.closeAll();
    }
}
