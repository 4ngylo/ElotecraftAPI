package me.angylo.elotecraftAPI;

import me.angylo.elotecraftAPI.input.InputListener;
import me.angylo.elotecraftAPI.menu.MenuListener;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class ElotecraftAPI extends JavaPlugin {

    @Override
    public void onEnable() {
        for (Listener listener : List.of(new MenuListener(), new InputListener(), new CleanupListener())) {
            getServer().getPluginManager().registerEvents(listener, this);
        }
    }

    @Override
    public void onDisable() {
        MenuListener.closeAll();
    }
}
