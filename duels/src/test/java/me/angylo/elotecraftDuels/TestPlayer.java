package me.angylo.elotecraftDuels;

import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.jetbrains.annotations.NotNull;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** MockBukkit leaves {@code teleportAsync} and {@code spigot().respawn()} unimplemented; these do them at once. */
class TestPlayer extends PlayerMock {

    /** While true, async teleports stay on their way: they never arrive, as on a slow chunk load. */
    private boolean holdTeleports;
    private int ping;

    TestPlayer(ServerMock server, String name) {
        super(server, name);
    }

    TestPlayer(ServerMock server, String name, UUID uuid) {
        super(server, name, uuid);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> teleportAsync(@NotNull Location location, @NotNull TeleportCause cause,
                                                             @NotNull TeleportFlag @NotNull ... flags) {
        if (holdTeleports) {
            return new CompletableFuture<>();
        }
        return CompletableFuture.completedFuture(teleport(location, cause, flags));
    }

    void holdTeleports(boolean hold) {
        holdTeleports = hold;
    }

    @Override
    public int getPing() {
        return ping;
    }

    void ping(int milliseconds) {
        ping = milliseconds;
    }

    @Override
    public Player.@NotNull Spigot spigot() {
        return new Player.Spigot() {
            @Override
            public void respawn() {
                TestPlayer.this.respawn();
            }
        };
    }
}
