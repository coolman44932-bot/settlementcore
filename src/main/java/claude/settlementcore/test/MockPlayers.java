package claude.settlementcore.test;

import claude.settlementcore.Core;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * GameTest mock players (GameTestHelper#makeMockServerPlayerInLevel) never agree channels with a client, so a mod that
 * sends every player a payload as they join without asking NeoForge's hasChannel first throws, and fails whichever test
 * made the player: Jade pings each player that joins (the -Pcompat run, 2026-09-26, once Jade was in the modpack). A
 * mock's connection is told it has those channels before the other mods' listeners run: at the datapack sync, which
 * comes first as a player joins, and at login. Only ever a mock: its connection is an in-memory EmbeddedChannel, which no
 * real player's is.
 */
@EventBusSubscriber(modid = Core.MODID)
public final class MockPlayers {

    /** Payloads other mods send each player as they join, unasked. Never the mod's own: it asks, and the tests count on the answer. */
    private static final List<Identifier> UNASKED = List.of(Identifier.parse("jade:server_ping_v1"),
            // Moonlight (Supplementaries' library) sends each player its fluids at the datapack sync (MoonlightForge.onDataSync),
            // before login: it failed 150 tests of the -Pcompat run the day the user added Supplementaries (2026-09-27)
            Identifier.parse("moonlight:s2c_finalize_fluids"));

    private MockPlayers() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void datapackSync(OnDatapackSyncEvent e) {
        if (e.getPlayer() != null) {
            allow(e.getPlayer());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void login(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) {
            allow(sp);
        }
    }

    private static void allow(ServerPlayer sp) {
        if (sp.connection != null && sp.connection.getConnection().channel() instanceof EmbeddedChannel) {
            var channels = ChannelAttributes.getOrCreateAdHocChannels(sp.connection.getConnection());
            channels.addAll(UNASKED);
            channels.addAll(othersPayloads());
        }
    }

    @Nullable
    private static java.util.Set<Identifier> others;

    /**
     * NeoForge's config-file payload is never a mock's: with it, NeoForge's ConfigSync (every server tick) looks the player's
     * connection up in its config table and throws, crashing the server tick, for a player whose class is exactly ServerPlayer.
     * The mocks made in tests are subclasses, which it skips, but PlayerList#respawn makes a plain ServerPlayer on the same
     * connection: a {@link SurvivalGod} who dies and respawns would crash the run.
     */
    static final Identifier CONFIG_FILE = net.neoforged.neoforge.network.payload.ConfigFilePayload.TYPE.id();

    /**
     * Every play payload the other mods registered: Moonlight alone sends a mock at least two as it joins
     * (s2c_finalize_fluids, then s2c_sync_world_data), so they are not listed one by one as they turn up. Never the engine's
     * own ({@link Core#MODID}) or a race mod's built on it ({@link #own()}). Read from NeoForge's registry
     * (NetworkRegistry.PAYLOAD_REGISTRATIONS) once; UNASKED alone if that cannot be read.
     */
    private static java.util.Set<Identifier> othersPayloads() {
        if (others == null) {
            java.util.Set<String> own = own();
            java.util.Set<Identifier> out = new java.util.HashSet<>();
            try {
                var f = net.neoforged.neoforge.network.registration.NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                var byProtocol = (java.util.Map<net.minecraft.network.ConnectionProtocol, java.util.Map<Identifier, ?>>) f.get(null);
                var play = byProtocol.get(net.minecraft.network.ConnectionProtocol.PLAY);
                if (play != null) {
                    for (Identifier id : play.keySet()) {
                        if (!own.contains(id.getNamespace()) && !id.equals(CONFIG_FILE)) {
                            out.add(id);
                        }
                    }
                }
            } catch (ReflectiveOperationException | ClassCastException ex) {
                com.mojang.logging.LogUtils.getLogger().warn("MockPlayers: cannot read NeoForge's payloads ({}); mocks get only {}", ex, UNASKED);
            }
            others = out;
        }
        return others;
    }

    /** The engine's namespace and every mod that declares a dependency on it (the races built on the engine). */
    public static java.util.Set<String> own() {
        java.util.Set<String> out = new java.util.HashSet<>();
        out.add(Core.MODID);
        try {
            for (var mod : net.neoforged.fml.ModList.get().getMods()) {
                for (var dep : mod.getDependencies()) {
                    if (Core.MODID.equals(dep.getModId())) {
                        out.add(mod.getModId());
                    }
                }
            }
        } catch (RuntimeException ex) {
            com.mojang.logging.LogUtils.getLogger().warn("MockPlayers: cannot read the mod list ({}); only {} counts as ours", ex, Core.MODID);
        }
        return out;
    }
}
