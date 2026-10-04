package me.angylo.elotecraftAPI;

import me.angylo.elotecraftAPI.example.ExampleCommand;
import me.angylo.elotecraftAPI.menu.MenuListener;
import org.bukkit.plugin.java.JavaPlugin;

public final class ElotecraftAPI extends JavaPlugin {

    private ExampleCommand example;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        // Demo only: remove this line (and the example package and resources) before shipping.
        example = ExampleCommand.register(this);
    }

    @Override
    public void onDisable() {
        if (example != null) {
            example.saveNow();
        }
        MenuListener.closeAll();
    }
}
