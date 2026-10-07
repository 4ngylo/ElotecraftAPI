package me.angylo.elotecraftDuels.command;

import me.angylo.elotecraftAPI.command.Args;
import me.angylo.elotecraftAPI.command.CommandBuilder;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Duels;
import me.angylo.elotecraftDuels.event.EventManager;
import me.angylo.elotecraftDuels.event.HostedEvent;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.menu.EventMenu;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code /event}: join, watch and host player events. Without arguments it opens the host's settings,
 * or the list of events; {@code /event <host>} joins that host's event.
 */
public final class EventCommand {

    private final Duels duels;
    private final Messages messages;
    private final EventManager events;
    private final EventMenu menu;

    public EventCommand(Duels duels, EventMenu menu) {
        this.duels = duels;
        this.messages = duels.messages();
        this.events = duels.events();
        this.menu = menu;
    }

    public void register() {
        CommandBuilder.create("event")
                .description(Text.plain(messages.get("command.event-description")))
                .permission(EventManager.JOIN)
                .messages(sender -> messages.get(sender, "command.no-permission"),
                        sender -> messages.get(sender, "command.player-only"))
                .executes(this::openOrJoin, (sender, args) -> args.length == 1 ? suggestHosts(args) : List.of())
                .playerSub("help", null, (player, args) -> messages.send(player, "command.event-help"))
                .playerSub("list", null, (player, args) -> menu.openList(player))
                .playerSub("host", EventManager.HOST, this::host, this::suggestKits)
                .playerSub("join", null, (player, args) -> events.join(player, Args.get(args, 0)), (sender, args) -> suggestHosts(args))
                .playerSub("leave", null, (player, args) -> {
                    if (!events.leave(player)) {
                        messages.send(player, "event.not-in-event");
                    }
                })
                .playerSub("settings", EventManager.HOST, (player, args) -> menu.openSettings(player))
                .playerSub("start", EventManager.HOST, (player, args) -> menu.start(player))
                .playerSub("cancel", EventManager.HOST, (player, args) -> events.cancel(player))
                .playerSub("invite", EventManager.HOST, this::invite, (sender, args) -> Args.players(args))
                .register(duels.plugin());
    }

    private void openOrJoin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.player-only");
        } else if (args.length > 0) {
            events.join(player, args[0]);
        } else if (events.hostedBy(player).isPresent()) {
            menu.openSettings(player);
        } else {
            menu.openList(player);
        }
    }

    /** {@code host [kit]}: the kit menu opens without a kit. */
    private void host(Player player, String[] args) {
        if (args.length == 0) {
            menu.host(player, null);
            return;
        }
        Optional<Kit> kit = duels.kits().get(args[0]).filter(found -> !found.isEmpty());
        if (kit.isEmpty()) {
            messages.send(player, "general.kit-not-found", Placeholder.unparsed("kit", args[0]));
            return;
        }
        menu.host(player, kit.get());
    }

    private void invite(Player player, String[] args) {
        Optional<Player> target = Args.player(Args.get(args, 0));
        if (target.isEmpty()) {
            messages.send(player, "general.player-not-found", Placeholder.unparsed("player", Args.get(args, 0)));
            return;
        }
        events.invite(player, target.get());
    }

    private List<String> suggestHosts(String[] args) {
        return Args.filter(events.openEvents().stream().map(HostedEvent::host).map(Bukkit::getPlayer)
                .filter(Objects::nonNull).map(Player::getName).toList(), args);
    }

    private List<String> suggestKits(CommandSender sender, String[] args) {
        return args.length == 1 ? Args.filter(duels.kits().all().stream().filter(kit -> kit.canUse(sender) && !kit.isEmpty())
                .map(Kit::name).toList(), args) : List.of();
    }
}
