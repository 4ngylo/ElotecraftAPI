package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.Button;
import me.angylo.elotecraftAPI.menu.PaginatedMenu;
import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Cosmetics;
import me.angylo.elotecraftDuels.Cosmetics.Cosmetic;
import me.angylo.elotecraftDuels.Cosmetics.Kind;
import me.angylo.elotecraftDuels.Effects;
import me.angylo.elotecraftDuels.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * {@code /duel cosmetics}: the kill effects or kill messages of config.yml {@code cosmetics}, one button each.
 * A click picks one, or drops it when it was picked; locked ones need their permission; back goes to the
 * hub's cosmetics menu. Layout in menus.yml
 * {@code kill-effect} and {@code kill-message}.
 */
public final class CosmeticsMenu {

    /** The victim's name in a kill message's preview. */
    private static final String PREVIEW_VICTIM = "Steve";

    private final Plugin plugin;
    private final Messages messages;
    private final ConfigFile menus;
    private final Supplier<Settings> settings;

    public CosmeticsMenu(Plugin plugin, Messages messages, ConfigFile menus, Supplier<Settings> settings) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        this.settings = settings;
    }

    public void open(Player viewer, Kind kind) {
        ConfigurationSection section = menus.get().getConfigurationSection(kind.key());
        try {
            Cosmetics cosmetics = settings.get().cosmetics();
            Effects effects = settings.get().effects();
            Cosmetic chosen = cosmetics.chosen(viewer, kind).orElse(null);
            PaginatedMenu menu = MenuLayout.frame(plugin, section);
            menu.items(cosmetics.all(kind).stream()
                    .filter(cosmetic -> kind != Kind.KILL_MESSAGE || messages.has("kill-messages." + cosmetic.id())).map(cosmetic -> button(viewer, kind, section, cosmetic, cosmetic.equals(chosen))).toList());
            MenuLayout.place(menu, section, "back", MenuLayout.command(plugin, effects, section, "back"));
            MenuLayout.place(menu, section, "close", MenuLayout.close(plugin, effects));
            menu.open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, kind.key(), e);
        }
    }

    private Button button(Player viewer, Kind kind, ConfigurationSection section, Cosmetic cosmetic, boolean chosen) {
        Effects effects = settings.get().effects();
        TagResolver[] tags = {Placeholder.component("name", Text.mm(cosmetic.name())),
                Placeholder.component("preview", kind != Kind.KILL_MESSAGE ? Component.empty()
                        : messages.get(viewer, "kill-messages." + cosmetic.id(), Placeholder.unparsed("player", PREVIEW_VICTIM),
                        Placeholder.unparsed("victim", PREVIEW_VICTIM), Placeholder.unparsed("killer", viewer.getName())))};
        boolean allowed = cosmetic.allowed(viewer);
        String lore = !allowed ? "locked-lore" : chosen ? "chosen-lore" : "lore";
        return Button.of(MenuLayout.icon(cosmetic.icon(), section.getConfigurationSection("entry"), lore, chosen, tags),
                !allowed ? (player, click) -> {
                    effects.play(player, "denied");
                    messages.send(player, "cosmetics.locked", tags);
                } : MenuLayout.choose(plugin, effects, player -> {
                    Cosmetics.choose(player, kind, chosen ? null : cosmetic);
                    messages.send(player, chosen ? "cosmetics.removed" : "cosmetics.chosen", tags);
                    open(player, kind);
                }));
    }
}
