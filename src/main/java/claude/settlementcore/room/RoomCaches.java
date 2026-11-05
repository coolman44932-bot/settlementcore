package claude.settlementcore.room;

import claude.settlementcore.Core;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * The rooms' caches of what the server's data holds (the templates, RoomTemplates; the relight plans, RoomRelight) dropped when they go stale:
 * at a server stop (in singleplayer the next world is another server, with its own datapacks), and once a /reload has put the server's new data
 * in place. Before, only the tests cleared them, so a datapack's override of a room's template stayed the old one after a /reload.
 * <p>The reload is caught at OnDatapackSyncEvent with no player (PlayerList#reloadResources, after MinecraftServer#reloadResources has put the
 * new resources in), not at a reload listener's apply: that runs before the new resources are the server's, and a tick in between would read
 * the old ones back into the cache.
 */
@EventBusSubscriber(modid = Core.MODID)
public final class RoomCaches {

    private RoomCaches() {
    }

    /** The server's data was reloaded: what was read from it is read again when next asked. Public for the tests. */
    public static void reloaded() {
        RoomTemplates.reloaded();
        RoomRelight.clear();
    }

    @SubscribeEvent
    static void datapacks(OnDatapackSyncEvent e) {
        if (e.getPlayer() == null) {
            reloaded();                                          // every player: a /reload (one player is one joining)
        }
    }

    @SubscribeEvent
    static void stopped(ServerStoppedEvent e) {
        RoomTemplates.clear();
        RoomRelight.clear();
    }
}
