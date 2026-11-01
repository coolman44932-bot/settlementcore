package claude.settlementcore.room;

import claude.settlementcore.settlement.Building;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rooms dug at an older version of the mod get what their room has gained since (user, 2026-09-25: "you do know
 * candles dont produce light right?", then "the pens don't work": version 1 gave every room its warpstone lamps
 * and shut the Beast Pens' east paddocks). block bench's export_mod keeps a snapshot of every room as shipped and
 * writes, per version, what changed: data/underempire/room_relight/&lt;room&gt;.json, steps of cells (the cell in the
 * template, what the room had there, "" where it left the world's own rock, and what goes there now). A room gets
 * each step newer than its own version once (WarrenHeartBlockEntity keeps the version per room), and only where the
 * world still holds what the old room had: whatever the Warlord built, changed or dug out is left as it is. Then
 * its fences, walls and bars are joined up (dug before digs joined them, they stood as loose posts).
 */
public final class RoomRelight {

    private static final Logger LOG = LogUtils.getLogger();

    /** One lamp (or its chain): where in the template, what was there ({@code old} null: the world's rock), what now. */
    public record Cell(BlockPos local, @Nullable Block old, BlockState now) {
    }

    /** One version's changes. */
    public record Step(int version, List<Cell> cells) {
    }

    public record Plan(int version, List<Step> steps) {
        /** Every step's cells, in order. */
        public List<Cell> cells() {
            return steps.stream().flatMap(s -> s.cells().stream()).toList();
        }
    }

    private static final Plan NONE = new Plan(0, List.of());
    private static final Map<String, Plan> CACHE = new HashMap<>();

    private RoomRelight() {
    }

    public static synchronized Plan get(ServerLevel level, String room) {
        return CACHE.computeIfAbsent(room, k -> load(level, k));
    }

    public static synchronized void clear() {
        CACHE.clear();
    }

    private static Plan load(ServerLevel level, String room) {
        Identifier loc = null;
        Optional<Resource> res = Optional.empty();
        for (String ns : Rooms.namespacesFor(room)) {             // the race that registered the room ships its relight
            loc = Identifier.fromNamespaceAndPath(ns, "room_relight/" + room + ".json");
            res = level.getServer().getResourceManager().getResource(loc);
            if (res.isPresent()) {
                break;
            }
        }
        if (res.isEmpty()) {
            return NONE;
        }
        try (Reader r = new InputStreamReader(res.get().open(), StandardCharsets.UTF_8)) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            List<Step> steps = new ArrayList<>();
            for (JsonElement se : o.getAsJsonArray("steps")) {
                JsonObject so = se.getAsJsonObject();
                List<Cell> cells = new ArrayList<>();
                for (JsonElement e : so.getAsJsonArray("cells")) {
                    JsonArray a = e.getAsJsonArray();
                    String old = a.get(3).getAsString();
                    cells.add(new Cell(new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()),
                            old.isEmpty() ? null : block(old), state(level, a.get(4).getAsString())));
                }
                steps.add(new Step(so.get("version").getAsInt(), List.copyOf(cells)));
            }
            return new Plan(o.get("version").getAsInt(), List.copyOf(steps));
        } catch (Exception ex) {
            LOG.error("Under-Empire: failed to read {}", loc, ex);
            return NONE;
        }
    }

    private static Block block(String s) {
        String id = s.split("\\[")[0];
        return BuiltInRegistries.BLOCK.get(Identifier.parse(id.contains(":") ? id : "minecraft:" + id));
    }

    /** A block state as the template wrote it ("spruce_fence_gate[facing=south,open=false,...]"). */
    private static BlockState state(ServerLevel level, String s) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String full = s.contains(":") ? s : "minecraft:" + s;
        return net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
                level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), full, false).blockState();
    }

    /** What joins up to its neighbours: a fence, a gate (in a wall), a wall, a pane or bars, stairs (their corners). */
    public static boolean connects(BlockState s) {
        Block b = s.getBlock();
        return b instanceof net.minecraft.world.level.block.CrossCollisionBlock || b instanceof net.minecraft.world.level.block.WallBlock
                || b instanceof net.minecraft.world.level.block.FenceGateBlock || b instanceof net.minecraft.world.level.block.StairBlock;
    }

    /** Join a room's fences, walls and bars to what is round them, as a dig by hand would have (the template's cells). */
    public static void joinUp(ServerLevel level, PlanPlacement pl, List<RoomTemplates.Entry> entries) {
        for (RoomTemplates.Entry e : entries) {
            if (!connects(e.state())) {
                continue;
            }
            BlockPos w = pl.world(e.local());
            BlockState cur = level.getBlockState(w);
            if (cur.is(e.state().getBlock())) {
                BlockState shaped = Block.updateFromNeighbourShapes(cur, level, w);
                if (shaped != cur) {
                    level.setBlock(w, shaped, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /**
     * Put a dug room's missing lamps in: each where the world still holds what the old room had there (the same
     * block, or for the world's rock, natural rock and not air). Returns how many went in, or -1 if the room is
     * not all loaded yet (try again later).
     */
    public static int apply(ServerLevel level, Building b) {
        return apply(level, b, 0);
    }

    /** As {@link #apply(ServerLevel, Building)}, for a room at version {@code since}: the steps after it. */
    public static int apply(ServerLevel level, Building b, int since) {
        if (b.door() == null || b.facing() == null) {
            return 0;                                            // dug before rooms kept their door: its placing is unknown
        }
        Rooms.Room room = Rooms.get(b.room());
        Plan plan = get(level, b.room());
        if (room == null) {
            return 0;
        }
        PlanPlacement pl = PlanPlacement.compute(room, b.door(), b.facing());
        if (!level.hasChunksAt(pl.box().minX(), pl.box().minZ(), pl.box().maxX(), pl.box().maxZ())) {
            return -1;
        }
        int n = 0;
        for (Step step : plan.steps()) {
            if (step.version() <= since) {
                continue;
            }
            for (Cell c : step.cells()) {
                BlockPos w = pl.world(c.local());
                BlockState cur = level.getBlockState(w);
                boolean asItWas = c.old() == null
                        ? !cur.isAir() && cur.getFluidState().isEmpty() && DigJob.natural(cur)
                        : cur.is(c.old());
                if (asItWas) {
                    level.setBlock(w, c.now().rotate(pl.rotation()), Block.UPDATE_ALL);
                    n++;
                }
            }
        }
        joinUp(level, pl, RoomTemplates.get(level, b.room()));
        return n;
    }
}
