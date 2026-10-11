package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.command.Args;
import me.angylo.elotecraftAPI.menu.Button;
import me.angylo.elotecraftAPI.menu.Menu;
import me.angylo.elotecraftAPI.menu.MenuConfig;
import me.angylo.elotecraftAPI.menu.PaginatedMenu;
import me.angylo.elotecraftAPI.util.LocalizedFile;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Effects;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitEditor;
import me.angylo.elotecraftDuels.kit.KitEditor.Session;
import me.angylo.elotecraftDuels.kit.KitPalette;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import me.angylo.elotecraftDuels.kit.KitRule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

import static me.angylo.elotecraftDuels.menu.MenuLayout.value;

/**
 * The kit editor's menus (menus.yml {@code kit-editor} and the {@code kit-editor-*} sections): the kit laid out like an
 * inventory, the armor and item selectors, and the enchantment, count, map and rules menus, all changing the player's
 * {@link KitEditor} session. Closing them without opening another one ends the session, which saves it.
 */
public final class KitEditorMenu implements Listener {

    private static final int ROWS = 6;
    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int NEXT_SLOT = 53;
    /** Enchantment rows: up to 5 from the left, then 5 more from this column. */
    private static final int ENCHANT_ROWS = 5;
    private static final int SECOND_COLUMN = 5;
    private static final Duration ANVIL_TIME = Duration.ofSeconds(60);
    private static final String ANVIL_TEXT = "#";
    /** The editor slot of each inventory slot: hotbar in the bottom row, storage above it, helmet to boots, off hand. */
    private static final int[] SLOT_OF = new int[KitEditor.SLOTS];

    static {
        for (int slot = 0; slot < 9; slot++) {
            SLOT_OF[slot] = 45 + slot;
        }
        for (int slot = 9; slot < 36; slot++) {
            SLOT_OF[slot] = slot + 9;
        }
        SLOT_OF[39] = 10;
        SLOT_OF[38] = 11;
        SLOT_OF[37] = 12;
        SLOT_OF[36] = 13;
        SLOT_OF[40] = 15;
    }

    private final Plugin plugin;
    private final Messages messages;
    private final LocalizedFile menus;
    private final Supplier<Settings> settings;
    private final KitEditor editor;
    private final KitPalette palette;
    private final KitRegistry kits;
    private final ArenaRegistry arenas;
    private final KitAdminMenu kitAdmin;

    public KitEditorMenu(Plugin plugin, Messages messages, LocalizedFile menus, Supplier<Settings> settings, KitEditor editor,
                         KitPalette palette, KitRegistry kits, ArenaRegistry arenas, KitAdminMenu kitAdmin) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        this.settings = settings;
        this.editor = editor;
        this.palette = palette;
        this.kits = kits;
        this.arenas = arenas;
        this.kitAdmin = kitAdmin;
    }

    /** The editor slot showing inventory slot {@code inventorySlot} (0 to 40). */
    public static int slotOf(int inventorySlot) {
        return SLOT_OF[inventorySlot];
    }

    /** Opens the editor on {@code player}'s layout of {@code kit}. */
    public void openLayout(Player player, Kit kit) {
        editor.start(player, kit).ifPresent(session -> open(player, session));
    }

    /** Opens the editor on {@code player}'s custom kit in {@code slot}. */
    public void openCustom(Player player, int slot) {
        editor.startCustom(player, slot).ifPresent(session -> open(player, session));
    }

    /** Opens the editor on the admin kit {@code kit}. */
    public void openAdmin(Player player, Kit kit) {
        editor.startAdmin(player, kit).ifPresent(session -> open(player, session));
    }

    /** A menu of the editor: closing it without opening another ends the session. */
    private interface Part {
    }

    private static final class Page extends Menu implements Part {

        Page(Plugin plugin, Component title) {
            super(plugin, ROWS, title);
        }
    }

    private static final class ListPage extends PaginatedMenu implements Part {

        ListPage(Plugin plugin, int rows, Component title) {
            super(plugin, rows, title);
        }
    }

    /** A tick after an editor menu closes, ends the session unless another one (or an anvil asking for text) took its place. */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Part) || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        editor.session(player).ifPresent(session -> Tasks.sync(plugin, () -> {
            if (editor.session(player).orElse(null) != session || session.asking()) {
                return;
            }
            Inventory top = player.isOnline() ? player.getOpenInventory().getTopInventory() : null;
            if (top == null || !(top.getHolder() instanceof Part)) {
                editor.finish(player);
            }
        }));
    }

    // ---- The editor itself.

    private void open(Player player, Session session) {
        ConfigurationSection section = section(player, "kit-editor");
        try {
            Page page = new Page(plugin, Text.mm(section.getString("title", ""), nameTag("name", section, session)));
            draw(page, section, session);
            page.open(player);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, player, "kit-editor", e);
            editor.finish(player);
        }
    }

    private void draw(Page page, ConfigurationSection section, Session session) {
        Effects effects = settings.get().effects();
        boolean layout = session.kind() == KitEditor.Kind.LAYOUT;
        for (int slot = 0; slot < KitEditor.SLOTS; slot++) {
            int inventorySlot = slot;
            ItemStack item = session.item(slot);
            if (item.isEmpty()) {
                item = MenuConfig.item(section.getConfigurationSection("empty." + paneKind(slot)));
                if (layout && session.picked() >= 0) {
                    item.lore(section.getStringList("layout-empty-lore").stream().map(Text::mm).toList());
                }
            } else if (layout && session.picked() == slot) {
                item.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            }
            page.set(SLOT_OF[slot], Button.of(item, (player, click) -> {
                effects.play(player, "menu-click");
                if (layout) {
                    move(page, section, session, inventorySlot);
                } else {
                    clickSlot(player, page, section, session, inventorySlot, click);
                }
            }));
        }
        TagResolver changes = Placeholder.unparsed("changes", String.valueOf(session.changes()));
        if (session.kind() == KitEditor.Kind.CUSTOM) {
            MenuLayout.put(page, section, "map", MenuLayout.choose(plugin, effects, player -> openArenas(player, session)),
                    Placeholder.unparsed("arena", session.arena() == null ? section.getString("values.random", "Random")
                            : arenas.get(session.arena()).map(arena -> Text.plain(Text.mm(arena.displayName()))).orElse(session.arena())));
        }
        if (!layout) {
            MenuLayout.put(page, section, "rules", MenuLayout.choose(plugin, effects, player -> {
                if (session.kind() == KitEditor.Kind.ADMIN) {
                    editor.finish(player);
                    kitAdmin.openRules(player, session.kit().name());
                } else {
                    openRules(player, session);
                }
            }));
        }
        ConfigurationSection info = section.getConfigurationSection("info");
        if (info != null) {
            page.set(MenuLayout.slot(page, info), MenuLayout.icon(MenuLayout.material(info, "material"), info,
                    layout ? "layout-lore" : "lore", false, changes));
        }
        MenuLayout.put(page, section, "reset", (player, click) -> {
            effects.play(player, "menu-click");
            editor.resetItems(session);
            draw(page, section, session);
        });
        MenuLayout.put(page, section, "rename", MenuLayout.choose(plugin, effects, player -> rename(player, session)),
                nameTag("name", section, session));
    }

    /** Layouts: the first click picks an item up, the next swaps it with the clicked slot's. The armor stays where it is. */
    private void move(Page page, ConfigurationSection section, Session session, int slot) {
        int picked = session.picked();
        if (armorSlot(slot) != null) {
            return;
        }
        if (picked < 0) {
            session.picked(session.item(slot).isEmpty() ? -1 : slot);
        } else {
            ItemStack moving = session.item(picked);
            if (picked != slot) {
                session.set(picked, session.item(slot));
                session.set(slot, moving);
            }
            session.picked(-1);
        }
        draw(page, section, session);
    }

    /** Whether {@code item} may go in inventory slot {@code slot}: armor slots only take the armor offered for them. */
    private boolean fits(ItemStack item, int slot) {
        EquipmentSlot armor = armorSlot(slot);
        return armor == null || palette.armor(armor).stream().anyMatch(piece -> piece.getType() == item.getType());
    }

    private void clickSlot(Player player, Page page, ConfigurationSection section, Session session, int slot, ClickType click) {
        ItemStack item = session.item(slot);
        switch (click) {
            case SHIFT_LEFT -> {
                session.set(slot, null);
                draw(page, section, session);
            }
            case SHIFT_RIGHT -> session.last().filter(last -> fits(last, slot)).ifPresent(last -> {
                session.set(slot, last);
                draw(page, section, session);
            });
            case RIGHT -> later(player, () -> {
                if (item.isEmpty()) {
                    openSelector(player, session, slot);
                } else if (KitPalette.enchantable(item)) {
                    openEnchants(player, session, slot);
                } else {
                    openCount(player, session, slot);
                }
            });
            default -> later(player, () -> openSelector(player, session, slot));
        }
    }

    private void openSelector(Player player, Session session, int slot) {
        EquipmentSlot armor = armorSlot(slot);
        if (armor == null) {
            openCategories(player, session, slot);
        } else {
            openArmor(player, session, slot, armor);
        }
    }

    private void rename(Player player, Session session) {
        ask(player, session, "editor.anvil-name", new TagResolver[0], text -> {
            if (text.isEmpty() || text.length() > KitEditor.MAX_NAME) {
                messages.send(player, "editor.bad-name", Placeholder.unparsed("max", String.valueOf(KitEditor.MAX_NAME)));
            } else if (session.kind() == KitEditor.Kind.ADMIN) {
                player.performCommand("duels kit setname " + session.kit().name() + " " + text);
            } else {
                session.name(text);
            }
        }, () -> open(player, session));
    }

    // ---- Selectors.

    private void openArmor(Player player, Session session, int slot, EquipmentSlot armor) {
        ConfigurationSection section = section(player, "kit-editor-armor");
        Effects effects = settings.get().effects();
        Page page = new Page(plugin, Text.mm(section.getString("title", "")));
        int next = section.getInt("first", 19);
        for (ItemStack piece : palette.armor(armor)) {
            if (next < PAGE_SIZE) {
                page.set(next++, Button.of(piece, pick(effects, session, slot, piece)));
            }
        }
        MenuLayout.put(page, section, "remove", pick(effects, session, slot, null));
        MenuLayout.put(page, section, "back", MenuLayout.choose(plugin, effects, clicker -> open(clicker, session)));
        page.open(player);
    }

    private void openCategories(Player player, Session session, int slot) {
        ConfigurationSection section = section(player, "kit-editor-categories");
        Effects effects = settings.get().effects();
        try {
            Page page = new Page(plugin, Text.mm(section.getString("title", "")));
            for (KitPalette.Category category : palette.categories()) {
                ConfigurationSection button = categorySection(section, category);
                page.set(MenuLayout.slot(page, button), Button.of(MenuConfig.item(button), MenuLayout.choose(plugin, effects,
                        clicker -> openCategory(clicker, session, slot, category, 0, KitPalette.PotionForm.POTION))));
            }
            MenuLayout.put(page, section, "remove", pick(effects, session, slot, null));
            MenuLayout.put(page, section, "back", MenuLayout.choose(plugin, effects, clicker -> open(clicker, session)));
            page.open(player);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, player, "kit-editor-categories", e);
        }
    }

    /** One page of a category: 45 items, its bottom-row items, the potion form buttons and the page arrows. */
    private void openCategory(Player player, Session session, int slot, KitPalette.Category category, int pageIndex,
                              KitPalette.PotionForm form) {
        ConfigurationSection section = section(player, "kit-editor-categories");
        Effects effects = settings.get().effects();
        List<ItemStack> items = palette.items(category, form);
        int pages = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        String title = categorySection(section, category).getString("title", category.key()) + (pages > 1 ? " (" + (pageIndex + 1) + "/" + pages + ")" : "");
        Page page = new Page(plugin, Text.mm(title));
        List<ItemStack> shown = items.subList(pageIndex * PAGE_SIZE, Math.min(items.size(), (pageIndex + 1) * PAGE_SIZE));
        for (int i = 0; i < shown.size(); i++) {
            page.set(i, Button.of(shown.get(i), pick(effects, session, slot, shown.get(i))));
        }
        for (KitPalette.Fixed fixed : category.bottom()) {
            page.set(fixed.slot(), Button.of(fixed.item(), pick(effects, session, slot, fixed.item())));
        }
        if (category.potions()) {
            List<Integer> formSlots = section.getIntegerList("potion-forms.slots");
            List<KitPalette.PotionForm> others = Arrays.stream(KitPalette.PotionForm.values()).filter(other -> other != form).toList();
            for (int i = 0; i < Math.min(formSlots.size(), others.size()); i++) {
                KitPalette.PotionForm other = others.get(i);
                ConfigurationSection button = section.getConfigurationSection("potion-forms." + other.key());
                if (button != null) {
                    page.set(formSlots.get(i), Button.of(MenuConfig.item(button), MenuLayout.choose(plugin, effects,
                            clicker -> openCategory(clicker, session, slot, category, 0, other))));
                }
            }
        }
        if (pageIndex > 0) {
            page.set(PREVIOUS_SLOT, Button.of(MenuConfig.item(section.getConfigurationSection("previous")), MenuLayout.choose(plugin, effects,
                    clicker -> openCategory(clicker, session, slot, category, pageIndex - 1, form))));
        } else {
            MenuLayout.put(page, section, "back", MenuLayout.choose(plugin, effects, clicker -> openCategories(clicker, session, slot)));
        }
        if (pageIndex < pages - 1) {
            page.set(NEXT_SLOT, Button.of(MenuConfig.item(section.getConfigurationSection("next")), MenuLayout.choose(plugin, effects,
                    clicker -> openCategory(clicker, session, slot, category, pageIndex + 1, form))));
        }
        page.open(player);
    }

    /** A click that puts {@code item} (null: nothing) in inventory slot {@code slot} and goes back to the editor. */
    private BiConsumer<Player, ClickType> pick(Effects effects, Session session, int slot, ItemStack item) {
        return MenuLayout.choose(plugin, effects, player -> {
            session.set(slot, item);
            open(player, session);
        });
    }

    // ---- Enchantments and count.

    private void openEnchants(Player player, Session session, int slot) {
        ConfigurationSection section = section(player, "kit-editor-enchants");
        Effects effects = settings.get().effects();
        ItemStack item = session.item(slot);
        Page page = new Page(plugin, Text.mm(section.getString("title", "")));
        List<Enchantment> enchantments = KitPalette.enchantments(item).stream()
                .sorted((a, b) -> Boolean.compare(b.getMaxLevel() >= ENCHANT_ROWS, a.getMaxLevel() >= ENCHANT_ROWS)).toList();
        for (int i = 0; i < Math.min(enchantments.size(), 2 * ENCHANT_ROWS); i++) {
            Enchantment enchantment = enchantments.get(i);
            int first = (i % ENCHANT_ROWS) * 9 + (i < ENCHANT_ROWS ? 0 : SECOND_COLUMN);
            int levels = Math.min(enchantment.getMaxLevel(), i < ENCHANT_ROWS ? 9 : 9 - SECOND_COLUMN);
            for (int level = 1; level <= levels; level++) {
                page.set(first + level - 1, book(effects, section, session, slot, item, enchantment, level));
            }
        }
        List<Integer> curseSlots = section.getIntegerList("curse-slots");
        List<Enchantment> extras = KitPalette.extras(item);
        for (int i = 0; i < Math.min(curseSlots.size(), extras.size()); i++) {
            page.set(curseSlots.get(i), book(effects, section, session, slot, item, extras.get(i), 1));
        }
        boolean unbreakable = item.getItemMeta().isUnbreakable();
        MenuLayout.put(page, section, "unbreakable", (clicker, click) -> {
            effects.play(clicker, "menu-click");
            ItemStack changed = session.item(slot);
            changed.editMeta(meta -> meta.setUnbreakable(!unbreakable));
            session.set(slot, changed);
            later(clicker, () -> openEnchants(clicker, session, slot));
        }, Placeholder.component("value", value(section, unbreakable ? "on" : "off")));
        int max = KitPalette.maxDurability(item);
        if (max > 0) {
            int damage = item.getItemMeta() instanceof Damageable damageable ? damageable.getDamage() : 0;
            MenuLayout.put(page, section, "durability", MenuLayout.choose(plugin, effects, clicker -> askDurability(clicker, session, slot, max)),
                    Placeholder.unparsed("durability", String.valueOf(max - damage)), Placeholder.unparsed("max", String.valueOf(max)));
        }
        MenuLayout.put(page, section, "clear", (clicker, click) -> {
            effects.play(clicker, "menu-click");
            ItemStack changed = session.item(slot);
            changed.removeEnchantments();
            session.set(slot, changed);
            later(clicker, () -> openEnchants(clicker, session, slot));
        });
        MenuLayout.put(page, section, "back", MenuLayout.choose(plugin, effects, clicker -> open(clicker, session)));
        page.open(player);
    }

    /** A book adding {@code enchantment} at {@code level}, dropping the ones it conflicts with; a click on the applied one removes it. */
    private Button book(Effects effects, ConfigurationSection section, Session session, int slot, ItemStack item,
                        Enchantment enchantment, int level) {
        ConfigurationSection template = section.getConfigurationSection("book");
        boolean applied = item.getEnchantmentLevel(enchantment) == level;
        ItemStack icon = MenuLayout.icon(MenuLayout.material(template, "material"), template, applied ? "active-lore" : "lore", applied,
                Placeholder.component("enchant", enchantment.displayName(level)));
        return Button.of(icon, (player, click) -> {
            effects.play(player, "menu-click");
            ItemStack changed = session.item(slot);
            if (applied) {
                changed.removeEnchantment(enchantment);
            } else {
                changed.getEnchantments().keySet().stream().filter(other -> other.equals(enchantment) || other.conflictsWith(enchantment))
                        .toList().forEach(changed::removeEnchantment);
                changed.addEnchantment(enchantment, level);
            }
            session.set(slot, changed);
            later(player, () -> openEnchants(player, session, slot));
        });
    }

    private void askDurability(Player player, Session session, int slot, int max) {
        TagResolver[] tags = {Placeholder.unparsed("min", "1"), Placeholder.unparsed("max", String.valueOf(max))};
        ask(player, session, "editor.anvil-durability", tags, text -> number(player, text, 1, max, tags).ifPresent(durability -> {
            ItemStack changed = session.item(slot);
            changed.editMeta(Damageable.class, meta -> {
                if (durability == max) {
                    meta.resetDamage();
                } else {
                    meta.setDamage(max - durability);
                }
            });
            session.set(slot, changed);
        }), () -> openEnchants(player, session, slot));
    }

    private void openCount(Player player, Session session, int slot) {
        ConfigurationSection section = section(player, "kit-editor-count");
        Effects effects = settings.get().effects();
        ItemStack item = session.item(slot);
        int max = item.getMaxStackSize();
        Page page = new Page(plugin, Text.mm(section.getString("title", "")));
        int next = section.getInt("first", 19);
        for (int amount : section.getIntegerList("presets")) {
            if (amount >= 1 && amount <= max && next < PAGE_SIZE) {
                page.set(next++, Button.of(item.asQuantity(amount), MenuLayout.choose(plugin, effects, clicker -> {
                    session.set(slot, session.item(slot).asQuantity(amount));
                    open(clicker, session);
                })));
            }
        }
        TagResolver[] tags = {Placeholder.unparsed("min", "1"), Placeholder.unparsed("max", String.valueOf(max))};
        MenuLayout.put(page, section, "custom", MenuLayout.choose(plugin, effects, clicker -> ask(clicker, session, "editor.anvil-count", tags,
                text -> number(clicker, text, 1, max, tags).ifPresent(amount -> session.set(slot, session.item(slot).asQuantity(amount))),
                () -> open(clicker, session))), tags);
        MenuLayout.put(page, section, "back", MenuLayout.choose(plugin, effects, clicker -> open(clicker, session)));
        page.open(player);
    }

    // ---- Map and rules of a custom kit.

    private void openArenas(Player player, Session session) {
        ConfigurationSection section = section(player, "kit-editor-arenas");
        Effects effects = settings.get().effects();
        try {
            ListPage menu = MenuLayout.frame(section, (rows, title) -> new ListPage(plugin, rows, title));
            ConfigurationSection random = section.getConfigurationSection("random");
            ConfigurationSection template = section.getConfigurationSection("arena");
            List<Button> buttons = new ArrayList<>();
            buttons.add(Button.of(MenuLayout.icon(MenuLayout.material(random, "material"), random,
                    session.arena() == null ? "selected-lore" : "lore", session.arena() == null), chooseArena(effects, session, null)));
            for (Arena arena : arenas.all()) {
                if (arena.isReady() && session.kit().accepts(arena)) {
                    boolean picked = arena.name().equals(session.arena());
                    buttons.add(Button.of(MenuLayout.icon(arena.icon(), template, picked ? "selected-lore" : "lore", picked,
                            Placeholder.component("arena", Text.mm(arena.displayName()))), chooseArena(effects, session, arena.name())));
                }
            }
            menu.items(buttons);
            MenuLayout.place(menu, section, "back", MenuLayout.choose(plugin, effects, clicker -> open(clicker, session)));
            menu.open(player);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, player, "kit-editor-arenas", e);
        }
    }

    private BiConsumer<Player, ClickType> chooseArena(Effects effects, Session session, String arena) {
        return MenuLayout.choose(plugin, effects, player -> {
            session.arena(arena);
            open(player, session);
        });
    }

    /** A custom kit's game rules, drawn like the admin rules menu (menus.yml {@code kit-rules}) but changing the session. */
    private void openRules(Player player, Session session) {
        ConfigurationSection section = section(player, "kit-rules");
        Effects effects = settings.get().effects();
        try {
            ListPage menu = MenuLayout.frame(section, (rows, title) -> new ListPage(plugin, rows, title),
                    nameTag("kit", section(player, "kit-editor"), session));
            drawRules(menu, section, effects, player, session);
            MenuLayout.place(menu, section, "back", MenuLayout.choose(plugin, effects, clicker -> open(clicker, session)));
            menu.open(player);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, player, "kit-rules", e);
        }
    }

    private void drawRules(ListPage menu, ConfigurationSection section, Effects effects, Player viewer, Session session) {
        Kit kit = session.kit();
        for (var rule : session.rules().entrySet()) {
            kit = kit.withRule(rule.getKey(), rule.getValue());
        }
        Kit shown = kit;
        ConfigurationSection template = section.getConfigurationSection("rule");
        menu.items(Arrays.stream(KitRule.values()).map(rule -> {
            boolean flagOn = rule.isFlag() && shown.flag(rule, settings.get());
            OptionalInt number = shown.number(rule, settings.get());
            Component current = rule.isFlag() ? value(section, flagOn ? "on" : "off")
                    : number.isPresent() ? Component.text(rule.format(number.getAsInt())) : value(section, "vanilla");
            boolean set = session.rules().containsKey(rule);
            String kind = rule.isFlag() ? (flagOn ? "flag-on" : "flag-off") : rule.isSeconds() ? "seconds" : "number";
            ItemStack icon = MenuLayout.icon(MenuLayout.material(template, kind), template, rule.isFlag() ? "flag-lore" : kind + "-lore", set,
                    Placeholder.unparsed("rule", rule.key()), Placeholder.component("value", current),
                    Placeholder.component("state", value(section, set ? "set" : "default")));
            return Button.of(icon, (player, click) -> {
                effects.play(player, "menu-click");
                if (click.isRightClick()) {
                    session.rule(rule, null);
                } else if (rule.isFlag()) {
                    session.rule(rule, !flagOn);
                } else {
                    later(player, () -> askRule(player, session, rule));
                    return;
                }
                drawRules(menu, section, effects, viewer, session);
            });
        }).toList());
    }

    private void askRule(Player player, Session session, KitRule rule) {
        TagResolver[] tags = {Placeholder.unparsed("rule", rule.key()), Placeholder.unparsed("min", "0"),
                Placeholder.unparsed("max", String.valueOf(rule.max()))};
        ask(player, session, "editor.anvil-rule", tags, text -> number(player, text, 0, rule.max(), tags)
                .ifPresent(value -> session.rule(rule, value)), () -> openRules(player, session));
    }

    // ---- Helpers.

    /**
     * Asks for text in an anvil titled {@code titleKey}, its text starting as {@link #ANVIL_TEXT}: the answer, without that
     * mark, goes to {@code onAnswer} a tick later; then {@code reopen} runs, also when the anvil is closed. The session
     * lives on while the anvil is open.
     */
    private void ask(Player player, Session session, String titleKey, TagResolver[] tags, Consumer<String> onAnswer, Runnable reopen) {
        session.asking(true);
        KitAdminMenu.anvil.ask(plugin, player, messages.get(player, titleKey, tags), ANVIL_TEXT).thenAccept(answer -> {
            if (plugin.isEnabled()) {
                Tasks.sync(plugin, () -> {
                    session.asking(false);
                    if (!player.isOnline() || editor.session(player).orElse(null) != session) {
                        return;
                    }
                    answer.map(text -> text.replace(ANVIL_TEXT, "").strip()).ifPresent(onAnswer);
                    reopen.run();
                });
            }
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Kit editor anvil answer failed", error);
            return null;
        });
    }

    /** {@code text} as a whole number from {@code min} to {@code max}; tells {@code player} otherwise. */
    private Optional<Integer> number(Player player, String text, int min, int max, TagResolver[] tags) {
        OptionalInt number = Args.integer(text, min, max);
        if (number.isEmpty()) {
            messages.send(player, "editor.bad-number", tags);
            return Optional.empty();
        }
        return Optional.of(number.getAsInt());
    }

    /** Runs {@code action} a tick later, outside the click event, as opening an inventory needs. */
    private void later(Player player, Runnable action) {
        Tasks.sync(plugin, () -> {
            if (player.isOnline()) {
                action.run();
            }
        });
    }

    /** {@code category}'s section in {@code categories} (menus.yml {@code kit-editor-categories} in the viewer's language). */
    private static ConfigurationSection categorySection(ConfigurationSection categories, KitPalette.Category category) {
        ConfigurationSection translated = categories.getConfigurationSection("categories." + category.key());
        return translated != null ? translated : category.section();
    }

    /** The menus.yml section {@code key} in {@code viewer}'s language. */
    private ConfigurationSection section(Player viewer, String key) {
        ConfigurationSection section = menus.get(viewer).getConfigurationSection(key);
        if (section == null) {
            throw new IllegalArgumentException("Missing menu section " + key + " in menus.yml");
        }
        return section;
    }

    /** The tag {@code tag} as the name the editor shows for {@code session}'s kit; {@code section} is menus.yml {@code kit-editor}. */
    private TagResolver nameTag(String tag, ConfigurationSection section, Session session) {
        if (session.kind() == KitEditor.Kind.ADMIN) {
            return MenuLayout.plain(tag, kits.get(session.kit().name()).orElse(session.kit()).displayName());
        }
        if (session.name() != null) {
            return Placeholder.unparsed(tag, session.name());
        }
        return session.kind() == KitEditor.Kind.CUSTOM
                ? Placeholder.unparsed(tag, section.getString("values.custom-name", "Custom kit <slot>")
                        .replace("<slot>", String.valueOf(session.customSlot())))
                : MenuLayout.plain(tag, session.kit().displayName());
    }

    private static String paneKind(int slot) {
        return slot >= 36 ? "armor" : slot < 9 ? "hotbar" : "storage";
    }

    /** The armor slot inventory slot {@code slot} is (36 boots to 39 helmet), or null. */
    private static EquipmentSlot armorSlot(int slot) {
        return switch (slot) {
            case 39 -> EquipmentSlot.HEAD;
            case 38 -> EquipmentSlot.CHEST;
            case 37 -> EquipmentSlot.LEGS;
            case 36 -> EquipmentSlot.FEET;
            default -> null;
        };
    }
}
