package me.angylo.elotecraftDuels.kit;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SpawnEggMeta;

/**
 * Spawn eggs that make a set-up mob, such as a horse with armor or a charged creeper. The only user of the experimental
 * {@code EntityFactory}: the mob travels on the egg as vanilla entity data, so it spawns that way anywhere.
 */
final class SpawnEggs {

    private SpawnEggs() {
    }

    /**
     * Makes {@code egg} spawn the mob {@code nbt} describes, e.g. {@code {id:"minecraft:creeper",powered:1b}}.
     *
     * @throws IllegalArgumentException if {@code egg} is not a spawn egg or {@code nbt} is not a mob
     */
    static void spawning(ItemStack egg, String nbt) {
        if (!(egg.getItemMeta() instanceof SpawnEggMeta)) {
            throw new IllegalArgumentException("not a spawn egg");
        }
        @SuppressWarnings("UnstableApiUsage")
        var snapshot = Bukkit.getEntityFactory().createEntitySnapshot(nbt);
        egg.editMeta(SpawnEggMeta.class, meta -> meta.setSpawnedEntity(snapshot));
    }
}
