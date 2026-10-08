package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.Menu;
import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftAPI.util.Durations;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.match.FighterResult;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /duel inventory}: a fighter's items as the fight left them, laid out like the kit preview, with
 * health, food, effects and fight counts on the bottom row. Layout in menus.yml {@code fight-inventory}.
 */
public final class FightInventoryMenu {

    private static final int ROWS = 6;
    private static final List<String> INFO_BUTTONS = List.of("health", "food", "effects", "hits", "potions");
    private static final int TICKS_PER_SECOND = 20;

    private final Plugin plugin;
    private final Messages messages;
    private final ConfigFile menus;
    private final Supplier<Settings> settings;

    public FightInventoryMenu(Plugin plugin, Messages messages, ConfigFile menus, Supplier<Settings> settings) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        this.settings = settings;
    }

    public void open(Player viewer, FighterResult fighter) {
        ConfigurationSection section = menus.get().getConfigurationSection("fight-inventory");
        try {
            if (section == null) {
                throw new IllegalArgumentException("Missing fight-inventory section");
            }
            TagResolver[] tags = tags(section, fighter);
            Menu menu = new Menu(plugin, ROWS, Text.mm(section.getString("title", ""), tags));
            List<ItemStack> items = fighter.items();
            for (int slot = 0; slot < items.size(); slot++) {
                int shownAt = KitMenu.previewSlot(slot);
                if (shownAt >= 0 && !items.get(slot).isEmpty()) {
                    menu.set(shownAt, items.get(slot));
                }
            }
            for (String key : INFO_BUTTONS) {
                MenuLayout.bottom(menu, section, key, (player, click) -> { }, tags);
            }
            MenuLayout.bottom(menu, section, "close", MenuLayout.close(plugin, settings.get().effects()));
            menu.open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, "fight-inventory", e);
        }
    }

    private static TagResolver[] tags(ConfigurationSection section, FighterResult fighter) {
        int thrown = fighter.potionsThrown();
        String accuracy = thrown == 0 ? "-" : Math.round(100f * (thrown - fighter.potionsMissed()) / thrown) + "%";
        return new TagResolver[]{
                Placeholder.unparsed("player", fighter.name()),
                Placeholder.unparsed("health", String.format(Locale.ROOT, "%.1f", fighter.health() / 2)),
                Placeholder.unparsed("food", String.valueOf(fighter.food())),
                Placeholder.component("effects", effects(section, fighter.effects())),
                Placeholder.unparsed("hits", String.valueOf(fighter.hits())),
                Placeholder.unparsed("combo", String.valueOf(fighter.longestCombo())),
                Placeholder.unparsed("thrown", String.valueOf(thrown)),
                Placeholder.unparsed("missed", String.valueOf(fighter.potionsMissed())),
                Placeholder.unparsed("accuracy", accuracy)
        };
    }

    /** Effect names in the viewer's language, with their level and time left, or the section's {@code values.none}. */
    private static Component effects(ConfigurationSection section, List<PotionEffect> effects) {
        if (effects.isEmpty()) {
            return MenuLayout.value(section, "none");
        }
        return Component.join(JoinConfiguration.commas(true), effects.stream().map(effect -> {
            Component name = Component.translatable(effect.getType());
            if (effect.getAmplifier() > 0) {
                name = name.append(Component.text(" " + (effect.getAmplifier() + 1)));
            }
            return effect.isInfinite() ? name
                    : name.append(Component.text(" (" + Durations.format(Duration.ofSeconds(effect.getDuration() / TICKS_PER_SECOND)) + ")"));
        }).toList());
    }
}
