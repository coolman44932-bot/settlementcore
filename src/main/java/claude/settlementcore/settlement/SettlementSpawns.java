package claude.settlementcore.settlement;

import claude.settlementcore.Core;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * No monsters spawn in a warren (user, 2026-09-25, picked with the survival rooms): the horde keeps its
 * own tunnels clear. Natural and world-generation spawns of anything in the MONSTER category are refused
 * anywhere a loaded Heart counts as its warren (WarrenHeartBlockEntity#inWarren: near the Heart, in and
 * just round its rooms, tunnels, deep levels and the Ore Mine's drifts). Monsters that walk in, raids,
 * spawners and spawn eggs are not stopped: those the warren has to fight.
 */
@EventBusSubscriber(modid = Core.MODID)
public final class SettlementSpawns {

    private static final Map<ResourceKey<Level>, Set<Settlement>> HEARTS = new HashMap<>();

    private SettlementSpawns() {
    }

    public static void add(ServerLevel level, Settlement heart) {
        HEARTS.computeIfAbsent(level.dimension(), k -> new LinkedHashSet<>()).add(heart);
    }

    public static void remove(ServerLevel level, Settlement heart) {
        Set<Settlement> s = HEARTS.get(level.dimension());
        if (s != null) {
            s.remove(heart);
        }
    }

    /** The Hearts loaded in this world (a copy). */
    public static List<Settlement> loaded(ServerLevel level) {
        Set<Settlement> s = HEARTS.get(level.dimension());
        return s == null ? List.of() : s.stream().filter(h -> !h.isRemoved()).toList();
    }

    /** Whether {@code p} is in any loaded warren of this world. */
    public static boolean inAnyWarren(ServerLevel level, BlockPos p) {
        Set<Settlement> s = HEARTS.get(level.dimension());
        if (s == null) {
            return false;
        }
        for (Settlement h : s) {
            if (!h.isRemoved() && h.owner() != null && (h.inSettlement(p) || h.insideWalls(p))) {
                return true;
            }
        }
        return false;
    }

    /** A block placed or broken near a settlement: its walls are scanned again soon (Walls). */
    private static void nearWalls(net.minecraft.world.level.LevelAccessor l, BlockPos p) {
        nearWalls(l, p, false);
    }

    /** {@code byPlayer}: a player broke it, and a wall's cell they took out stays out (Walls#forget). */
    private static void nearWalls(net.minecraft.world.level.LevelAccessor l, BlockPos p, boolean byPlayer) {
        if (!(l instanceof ServerLevel level)) {
            return;
        }
        Set<Settlement> s = HEARTS.get(level.dimension());
        if (s == null) {
            return;
        }
        for (Settlement h : s) {
            BlockPos c = h.getBlockPos();
            if (!h.isRemoved() && Math.abs(p.getX() - c.getX()) <= Walls.RADIUS + 2 && Math.abs(p.getZ() - c.getZ()) <= Walls.RADIUS + 2) {
                h.wallChanged(level.getGameTime());
                if (byPlayer) {
                    h.wallCellBroken(p);
                }
            }
        }
    }

    @SubscribeEvent
    public static void broken(net.neoforged.neoforge.event.level.block.BreakBlockEvent e) {
        nearWalls(e.getLevel(), e.getPos(), e.getPlayer() != null);
    }

    @SubscribeEvent
    public static void placed(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent e) {
        nearWalls(e.getLevel(), e.getPos());
    }

    @SubscribeEvent
    public static void exploded(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate e) {
        if (!e.getAffectedBlocks().isEmpty()) {
            nearWalls(e.getLevel(), e.getAffectedBlocks().get(0));
        }
    }

    @SubscribeEvent
    public static void position(MobSpawnEvent.PositionCheck e) {
        if (e.getSpawnType() != EntitySpawnReason.NATURAL && e.getSpawnType() != EntitySpawnReason.CHUNK_GENERATION) {
            return;
        }
        if (e.getEntity().getType().getCategory() != MobCategory.MONSTER) {
            return;
        }
        ServerLevel level = e.getLevel().getLevel();
        if (inAnyWarren(level, e.getEntity().blockPosition())) {
            e.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
        }
    }
}
