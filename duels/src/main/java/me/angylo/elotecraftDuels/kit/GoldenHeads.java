package me.angylo.elotecraftDuels.kit;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.match.MatchManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Golden heads, the UHC healing item: a player head that a fighter eats with a right click, for twice a golden apple's
 * healing (Regeneration II for 10 seconds) and Absorption I for 2 minutes. Only fighters eat them; anywhere else a
 * golden head is an ordinary head. Admins put them in kits with {@code /duels kit goldenhead}, players through the kit
 * editor's {@code GOLDEN_HEAD} palette entry. Main thread only.
 */
public final class GoldenHeads implements Listener {

    /** A fixed key, so heads saved in kits.yml stay golden heads whatever the plugin is called in tests. */
    private static final NamespacedKey KEY = Objects.requireNonNull(NamespacedKey.fromString("elotecraftduels:golden_head"));
    private static final int TICKS_PER_SECOND = 20;
    private static final List<PotionEffect> EFFECTS = List.of(
            new PotionEffect(PotionEffectType.REGENERATION, 10 * TICKS_PER_SECOND, 1),
            new PotionEffect(PotionEffectType.ABSORPTION, 120 * TICKS_PER_SECOND, 0));
    private static final String NAME = "<!italic><gold><bold>Golden Head";
    private static final String LORE = "<!italic><gray>Right-click to eat: heals twice a golden apple";
    /**
     * The usual golden head skin of UHC plugins (textures.minecraft.net hash 3bb612eb...791d8, used by KC-UHC,
     * UHCChampions and MCPVPRanked), as the base64 {@code textures} property. A fixed profile id keeps heads stackable.
     */
    private static final String TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvM2JiNjEyZWI0OTVlZGUyYzVjYTUxNzhkMmQxZWNmMWNhNWEyNTVkMjVkZmMzYzI1NGJjNDdmNjg0ODc5MWQ4In19fQ==";
    private static final UUID PROFILE_ID = UUID.nameUUIDFromBytes("elotecraftduels:golden_head".getBytes(StandardCharsets.UTF_8));
    private static final String PROFILE_NAME = "GoldenHead";

    private final MatchManager matches;

    public GoldenHeads(MatchManager matches) {
        this.matches = matches;
    }

    /** {@code amount} golden heads. */
    public static ItemStack create(int amount) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD, amount);
        head.editMeta(SkullMeta.class, meta -> {
            PlayerProfile profile = Bukkit.createProfile(PROFILE_ID, PROFILE_NAME);
            profile.setProperty(new ProfileProperty("textures", TEXTURE));
            meta.setPlayerProfile(profile);
            meta.displayName(Text.mm(NAME));
            meta.lore(List.of(Text.mm(LORE)));
            meta.getPersistentDataContainer().set(KEY, PersistentDataType.BOOLEAN, true);
        });
        return head;
    }

    public static boolean is(ItemStack item) {
        return item != null && item.getType() == Material.PLAYER_HEAD && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(KEY, PersistentDataType.BOOLEAN);
    }

    /**
     * A fighter's right click with a golden head eats one instead of placing it. After the countdown checks
     * ({@code CombatListener.onInteract}), which deny item use before the fight.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent event) {
        if (!is(event.getItem()) || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        Player player = event.getPlayer();
        if (!matches.matchOf(player).map(match -> match.isFighting(player)).orElse(false)) {
            return;
        }
        // Never placed in a fight, even when it cannot be eaten.
        event.setUseInteractedBlock(Event.Result.DENY);
        if (event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        event.setUseItemInHand(Event.Result.DENY);
        ItemStack item = event.getItem();
        item.setAmount(item.getAmount() - 1);
        player.getInventory().setItem(Objects.requireNonNull(event.getHand()), item.getAmount() > 0 ? item : null);
        player.addPotionEffects(EFFECTS);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_BURP, 1, 1);
    }
}
