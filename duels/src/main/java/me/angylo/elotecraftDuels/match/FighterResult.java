package me.angylo.elotecraftDuels.match;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.Arrays;
import java.util.List;

/**
 * A fighter as the fight left them, for {@code /duel inventory}: their inventory (armor and off hand
 * included), health, food, effects and fight counts. Items are copied in and out.
 */
public record FighterResult(String name, List<ItemStack> items, double health, int food, List<PotionEffect> effects,
                            int hits, int longestCombo, int potionsThrown, int potionsMissed) {

    public FighterResult {
        items = items.stream().map(item -> item == null ? ItemStack.empty() : item.clone()).toList();
        effects = List.copyOf(effects);
    }

    static FighterResult capture(Player fighter, FightStats stats) {
        return new FighterResult(fighter.getName(), Arrays.asList(fighter.getInventory().getContents()),
                fighter.getHealth(), fighter.getFoodLevel(), List.copyOf(fighter.getActivePotionEffects()),
                stats.hits(fighter), stats.longestCombo(fighter), stats.potionsThrown(fighter), stats.potionsMissed(fighter));
    }

    @Override
    public List<ItemStack> items() {
        return items.stream().map(ItemStack::clone).toList();
    }
}
