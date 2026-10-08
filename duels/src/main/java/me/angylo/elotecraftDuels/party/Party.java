package me.angylo.elotecraftDuels.party;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A group of online players with a leader, who start party fights together. Members are kept in the
 * order they joined; the leader is one of them. Changed only by {@link PartyManager} on the main thread;
 * the member list and leader may be read from any thread.
 */
public final class Party {

    private final List<UUID> members = new CopyOnWriteArrayList<>();
    /** Invited player to the server tick their invite expires on. */
    private final Map<UUID, Long> invites = new HashMap<>();
    private volatile UUID leader;
    /** Public: anyone may join without an invite. */
    private volatile boolean open;

    Party(UUID leader) {
        this.leader = leader;
        members.add(leader);
    }

    public UUID leader() {
        return leader;
    }

    public boolean isLeader(UUID player) {
        return leader.equals(player);
    }

    /** Members in the order they joined, the leader included. */
    public List<UUID> members() {
        return List.copyOf(members);
    }

    public int size() {
        return members.size();
    }

    public boolean contains(UUID player) {
        return members.contains(player);
    }

    void add(UUID player) {
        members.add(player);
        invites.remove(player);
    }

    /** Removes {@code player}; if they led the party, the member who joined earliest after them leads now. */
    void remove(UUID player) {
        members.remove(player);
        if (leader.equals(player) && !members.isEmpty()) {
            leader = members.getFirst();
        }
    }

    public boolean isOpen() {
        return open;
    }

    void open(boolean value) {
        open = value;
    }

    void promote(UUID player) {
        leader = player;
    }

    void invite(UUID player, long expiresAtTick) {
        invites.put(player, expiresAtTick);
    }

    boolean hasInvite(UUID player, long nowTick) {
        Long expiresAt = invites.get(player);
        return expiresAt != null && nowTick < expiresAt;
    }

    void removeInvite(UUID player) {
        invites.remove(player);
    }
}
