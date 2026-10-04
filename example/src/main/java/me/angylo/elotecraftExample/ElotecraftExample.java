package me.angylo.elotecraftExample;

import org.bukkit.plugin.java.JavaPlugin;

/** Demo plugin built on ElotecraftAPI: {@code /example} and {@code /countdown}. Not for production servers. */
public final class ElotecraftExample extends JavaPlugin {

    private ExampleCommand example;

    @Override
    public void onEnable() {
        example = ExampleCommand.register(this);
    }

    @Override
    public void onDisable() {
        if (example != null) {
            example.shutdown();
        }
    }
}
