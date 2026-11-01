package claude.settlementcore.room;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where a raised building goes, and what is done to the land for it (docs/plans/surface-builder.md, sections 1 and 9): the
 * world read into {@link Reshape}, and a {@link Plan} or a {@link Refusal} back.
 *
 * <p>The <b>pad</b> is held at the floor (the template's ground layer): the box with its plinth under it, a berm 1 wide round
 * it, and the door's landing, 3 columns wide and 2 out. Round the pad the margin is ramped into the land (Reshape's solve),
 * widened until it fits or {@link Reshape#MARGIN_MAX}, and anything of the player's, another building, a tree or a pond is
 * kept at its own height with the ramp flowing round it. Only natural ground and its cover are ever cut or filled.
 *
 * <p>Every refusal comes before anything is changed and names its spot in the world. A plan is read once, at placement, and
 * saved ({@link Plan#save}): its {@link Plan#earthworks} are rebuilt from the saved columns and the template alone, never from
 * the world, so a reload hands out the same tasks for the saved progress. No per-tick cost; no chunk is ever loaded.
 */
public final class Siting {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The deepest a plinth under the box may go: past it the site is refused. */
    public static final int SKIRT_MAX = 8;
    /** The deepest water the pad may be laid over. */
    public static final int WATER_MAX = 2;
    /** The biggest tree on the pad that is felled: logs and leaves together. */
    public static final int TREE_MAX = 256;
    /** How many times the ramp is solved again round the columns a solve found it couldn't touch. */
    public static final int PASSES = 4;
    /** The widest area, in columns, either way. */
    public static final int AREA_MAX = 64;

    /** How far under a thing the scan looks for the ground it stands on. */
    private static final int STACK_MAX = 64;
    /** How far from the floor a worked column may be (its heights are saved as bytes). */
    private static final int OFFSET_MAX = 120;
    private static final int NO = Integer.MIN_VALUE;
    /** The most looks a plan keeps (saved as a byte per column, the plain one included). */
    private static final int LOOKS_MAX = 126;
    /**
     * A look for a column when nothing in the area has one of its own, and for a saved look whose blocks are gone (a removed
     * mod's: they read back as air, and air is never laid as ground).
     */
    private static final Look PLAIN = new Look(Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
            Blocks.STONE.defaultBlockState());

    private Siting() {
    }

    /** What {@link #plan} answers: a {@link Plan}, or a {@link Refusal}. */
    public sealed interface Result permits Plan, Refusal {
    }

    /** Why a site can't be had, in words for the player, and where. */
    public record Refusal(String why, BlockPos spot) implements Result {
        @Override
        public String toString() {
            return why;
        }
    }

    /**
     * The order the land is worked in (section 9): the settlement clears first (the template's air in the box, the cover of
     * every column cut or filled, the pad's trees), then its settlers lay the plinth, the berm and landing, and the ramp.
     */
    public enum Phase {
        CLEARING, PLINTH, PAD, RAMP
    }

    /**
     * One block of the earthworks: what goes where, in which phase, and its lift.
     *
     * @param lift how many layers past the phase's first this block is: cuts count down from the highest cut, fills and the
     *             plinth up from their lowest block (a job adds 6 to a claim's score per lift past the lowest open one, so
     *             nobody digs a pit or builds on air); 0 in the clearing, which goes top-down in the list's own order
     */
    public record Earthwork(BlockPos pos, BlockState state, Phase phase, int lift) {
    }

    /** A column's own look: its top block caps it, its soil goes 3 deep under that, its deep block below. */
    public record Look(BlockState top, BlockState soil, BlockState deep) {
        BlockState at(int depth) {
            return depth == 0 ? top : depth <= 3 ? soil : deep;
        }
    }

    /**
     * A plan's numbers, for the bridge's printout.
     *
     * @param margin   the margin the ramp was solved with (0: the land wasn't reshaped, only the pad)
     * @param kept     columns the ramp goes round
     * @param steepest the steepest step left on the ramp itself (a free column against a free one, the berm, the landing,
     *                 and the border where the ramp meets the land level)
     * @param bank     where the land is too steep to meet level (a {@code seamed} plan), the highest bank the ramp meets it
     *                 with: the steepest step from the ramp to the border; 0 when it meets the land level all round
     * @param seamed   the ramp meets the land with a bank somewhere (Reshape's third pass, at the widest margin): cut into the
     *                 hill or built out over the slope, with a walk from the landing out to the land all the same
     */
    public record Numbers(int margin, int cutColumns, int fillColumns, int cutBlocks, int fillBlocks, int kept, int steepest, int bank,
                          boolean seamed) {
    }

    /**
     * Sites a raised building at {@code pl} (its door on the ground layer's course above the aimed ground:
     * {@link PlanPlacement#onGround}). Reads the world only, never writes it or loads a chunk. The level may be a client's
     * ({@link #preview}): only a server's has a settlement's other buildings to keep (the {@code warren}'s room cells are asked of a server only).
     *
     * @param warren  the settlement: its other buildings are kept, each 1 wider, and a pad that would cut into one is refused
     * @param reshape false for the plinth, berm and landing only (the land round them is left as it is)
     */
    public static Result plan(Level level, PlanPlacement pl, RoomShoring.Warren warren, boolean reshape) {
        try {
            return new Planner(level, pl, warren).plan(reshape);
        } catch (Refused r) {
            return r.refusal;
        }
    }

    /**
     * A column under the building's box that its template's ground layer holds, as the plinth would be laid under it.
     *
     * @param ground the height of the ground there, as the plan reads it (the top of the natural ground)
     * @param depth  how many blocks of plinth go under the floor's course there ({@code floor - 1 - ground}): 0 or less where the
     *               ground reaches the floor or is cut down to it; more than {@link #SKIRT_MAX} refuses the site
     */
    public record Column(int x, int z, int ground, int depth) {
        /** Whether the plinth here is deeper than {@link #SKIRT_MAX} allows. */
        public boolean tooDeep() {
            return depth > SKIRT_MAX;
        }
    }

    /**
     * What a held plan shows the player (core/client/PlanOutline): where the building would stand, its door, the plinth under it by
     * column, and where and why the site would be refused.
     *
     * @param placement the building's placement: its box and its door
     * @param refusal   why {@link #plan} would refuse the site, and where (null: it would be sited)
     * @param columns   the plinth's columns, in z then x order (none for a site whose template is missing or whose land is not loaded)
     */
    public record Preview(PlanPlacement placement, @Nullable Refusal refusal, List<Column> columns) {
        public Preview {
            columns = List.copyOf(columns);
        }

        /** The building's box. */
        public BoundingBox box() {
            return placement.box();
        }

        /** The door's cell, on the floor's course. */
        public BlockPos door() {
            return placement.door();
        }

        /** The height of the floor's course: the template's ground layer, a block under the door. */
        public int floor() {
            return placement.door().getY() - 1;
        }

        /** The door's landing, part of the pad: 3 columns wide and 2 out from the door, level with the floor. */
        public Reshape.Rect landing() {
            return landingRect(placement);
        }
    }

    /**
     * What to show the player of a site before it is asked for ({@link #plan}, with a settlement of no other buildings and the land
     * reshaped as a raise would reshape it): the refusal, if there is one, and the plinth's columns. It is the plan's own reading
     * of the land, the same refusal and the same ground for the same world, and may be asked of a client's level: it reads the world
     * only and loads no chunk. It does not know a settlement's other buildings (a server's), so it cannot show that refusal. The template
     * comes from {@link RoomTemplates#lookup}.
     */
    public static Preview preview(Level level, PlanPlacement pl) {
        return preview(level, pl, true);
    }

    /** {@link #preview(Level, PlanPlacement)}, with the land round the pad reshaped as the plan is asked to, or not. */
    public static Preview preview(Level level, PlanPlacement pl, boolean reshape) {
        return new Planner(level, pl, RoomShoring.Warren.alone()).preview(reshape);
    }

    // --- what the land is made of --------------------------------------------------------------------------------------

    /** The ground that may be cut or filled: DigJob's natural blocks with a shape, less its cover; and snow blocks. */
    public static boolean ground(BlockState s) {
        if (s.is(Blocks.SNOW_BLOCK)) {
            return true;
        }
        return DigJob.natural(s) && !s.isAir() && s.getFluidState().isEmpty() && !cover(s)
                && !s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
    }

    /** What grows or lies on the ground, cleared off any column cut or filled and never kept: DigJob's plants, and snow layers. */
    public static boolean cover(BlockState s) {
        return DigJob.plant(s) || s.is(Blocks.SNOW);
    }

    private static boolean log(BlockState s) {
        return s.is(BlockTags.LOGS);
    }

    /** A tree's own leaves: not a player's (persistent). */
    private static boolean wildLeaf(BlockState s) {
        return s.getBlock() instanceof LeavesBlock && s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT);
    }

    private static boolean wet(BlockState s) {
        return !s.getFluidState().isEmpty() || s.is(BlockTags.ICE);
    }

    /** What a tree on the pad may touch and still be felled: the land, its cover and water, and trees. */
    private static boolean wild(BlockState s) {
        return DigJob.natural(s) || cover(s) || log(s) || wildLeaf(s) || s.is(BlockTags.ICE) || s.is(Blocks.SNOW_BLOCK);
    }

    /**
     * What an earthwork may take out when it is done: the ground, its cover, water and ice. A felled tree's logs and leaves
     * only at the plan's own tree cells ({@link Plan#mayReplace}): a log anywhere else is a player's.
     */
    public static boolean mayReplace(BlockState s) {
        return DigJob.natural(s) || cover(s) || s.is(Blocks.SNOW_BLOCK) || s.is(BlockTags.ICE);
    }

    /** A block a column's look may be made of: plain ground, no ore, nothing that bites or hatches. */
    private static boolean lookable(BlockState s) {
        return ground(s) && (s.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) || s.is(Blocks.MUD))
                && !s.is(net.neoforged.neoforge.common.Tags.Blocks.ORES) && !s.hasBlockEntity()
                && !(s.getBlock() instanceof InfestedBlock) && !s.is(Blocks.MAGMA_BLOCK) && !s.is(Blocks.BUDDING_AMETHYST);
    }

    private static BlockState unsnowed(BlockState s) {
        return s.hasProperty(BlockStateProperties.SNOWY) ? s.setValue(BlockStateProperties.SNOWY, false) : s;
    }

    /** What a block is under the top: grass, podzol and mycelium are dirt there. */
    private static BlockState soilOf(BlockState s) {
        if (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM)) {
            return Blocks.DIRT.defaultBlockState();
        }
        return unsnowed(s);
    }

    // --- where things are, from the placement alone ----------------------------------------------------------------------

    /** The way out of the door: the unit step from the door cell to the cell outside it (the template's x=0 face). */
    private static int[] outward(PlanPlacement pl) {
        Rooms.Room r = pl.room();
        BlockPos out = pl.world(new BlockPos(r.doorX() - 1, r.doorY(), r.doorZ()));
        return new int[]{out.getX() - pl.door().getX(), out.getZ() - pl.door().getZ()};
    }

    /** The pad without the landing: the box and a berm 1 wide. */
    private static Reshape.Rect padRect(PlanPlacement pl) {
        BoundingBox b = pl.box();
        return new Reshape.Rect(b.minX() - 1, b.minZ() - 1, b.maxX() + 1, b.maxZ() + 1);
    }

    /**
     * The room's open court in world columns ({@link Rooms#court}: the pool a ring is raised round), or null for none. Its columns are no
     * part of the pad: the raise never builds on them, clears, fills, cuts, levels or tears down there, and the ramp goes round them as
     * round what it keeps.
     */
    @Nullable
    static Reshape.Rect courtRect(PlanPlacement pl) {
        BoundingBox c = Rooms.court(pl.room().id());
        if (c == null) {
            return null;
        }
        BlockPos a = pl.world(new BlockPos(c.minX(), 0, c.minZ()));
        BlockPos b = pl.world(new BlockPos(c.maxX(), 0, c.maxZ()));
        return new Reshape.Rect(Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), Math.max(a.getZ(), b.getZ()));
    }

    /** The door's landing: 3 columns wide and 2 out from the door, level with the floor. */
    private static Reshape.Rect landingRect(PlanPlacement pl) {
        int[] o = outward(pl);
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (int k = 1; k <= 2; k++) {
            for (int j = -1; j <= 1; j++) {
                int x = pl.door().getX() + o[0] * k + o[1] * j;
                int z = pl.door().getZ() + o[1] * k + o[0] * j;
                x0 = Math.min(x0, x);
                z0 = Math.min(z0, z);
                x1 = Math.max(x1, x);
                z1 = Math.max(z1, z);
            }
        }
        return new Reshape.Rect(x0, z0, x1, z1);
    }

    private static boolean in(Reshape.Rect r, int x, int z) {
        return x >= r.x0() && x <= r.x1() && z >= r.z0() && z <= r.z1();
    }

    private static int distance(Reshape.Rect r, int x, int z) {
        return Math.max(Math.max(0, Math.max(r.x0() - x, x - r.x1())), Math.max(0, Math.max(r.z0() - z, z - r.z1())));
    }

    /** The template's ground layer, by world column: the block each box column's course holds, where it is a whole block. */
    private static Map<Long, BlockState> groundLayer(PlanPlacement pl, List<RoomTemplates.Entry> template) {
        Map<Long, BlockState> out = new HashMap<>();
        int y = pl.room().doorY() - 1;
        for (RoomTemplates.Entry e : template) {
            if (e.local().getY() == y && !RoomShoring.open(e.state())) {
                BlockPos w = pl.world(e.local());
                out.put(column(w.getX(), w.getZ()), e.state().rotate(pl.rotation()));
            }
        }
        return out;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private static long column(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static String possessive(PlanPlacement pl) {
        String n = pl.room().displayName();
        return "the " + n + (n.endsWith("s") ? "'" : "'s");
    }

    // --- the plan ------------------------------------------------------------------------------------------------------

    /**
     * A site that can be had: the area and what becomes of each column, the looks, the pad's trees, and the numbers. The
     * earthworks come from these and the template alone.
     */
    public static final class Plan implements Result {
        private final PlanPlacement pl;
        private final List<RoomTemplates.Entry> template;
        private final int x0;
        private final int z0;
        private final int w;
        private final int h;
        private final int floor;
        private final boolean reshaped;
        private final byte[] kinds;
        private final int[] now;
        private final int[] ground;
        private final int[] top;
        /** Per column: its look's index + 1 when the look is its own, -(index + 1) when borrowed, 0 for none. */
        private final byte[] look;
        private final List<Look> looks;
        private final long[] trees;
        /** Water beside a cut of the pad, walled before the cut opens it (packed BlockPos), and each one's look + 1 (its soil). */
        private final long[] seals;
        private final byte[] sealLooks;
        private final Numbers numbers;
        private final String goesRound;
        @Nullable
        private List<Earthwork> earthworks;

        private Plan(PlanPlacement pl, List<RoomTemplates.Entry> template, int x0, int z0, int w, int h, int floor, boolean reshaped,
                     byte[] kinds, int[] now, int[] ground, int[] top, byte[] look, List<Look> looks, long[] trees, long[] seals,
                     byte[] sealLooks, Numbers numbers, String goesRound) {
            this.pl = pl;
            this.template = template;
            this.x0 = x0;
            this.z0 = z0;
            this.w = w;
            this.h = h;
            this.floor = floor;
            this.reshaped = reshaped;
            this.kinds = kinds;
            this.now = now;
            this.ground = ground;
            this.top = top;
            this.look = look;
            this.looks = looks;
            this.trees = trees;
            this.seals = seals;
            this.sealLooks = sealLooks;
            this.numbers = numbers;
            this.goesRound = goesRound;
        }

        public PlanPlacement placement() {
            return pl;
        }

        /** The area's west edge (its lowest x). */
        public int x0() {
            return x0;
        }

        /** The area's north edge (its lowest z). */
        public int z0() {
            return z0;
        }

        public int width() {
            return w;
        }

        public int depth() {
            return h;
        }

        /** The height the pad is held at: the template's ground layer. */
        public int floor() {
            return floor;
        }

        /** Whether the land round the pad was reshaped (false: the plinth, berm and landing only). */
        public boolean reshaped() {
            return reshaped;
        }

        public Numbers numbers() {
            return numbers;
        }

        /** What the ramp goes round, in words ("3 of your blocks and a pond"); empty for nothing. */
        public String goesRound() {
            return goesRound;
        }

        /** The pad's trees, felled in the clearing (packed BlockPos). */
        public long[] trees() {
            return trees.clone();
        }

        /** The water beside a cut of the pad that the plinth phase walls up first, with the soil beside it (packed BlockPos). */
        public long[] seals() {
            return seals.clone();
        }

        /**
         * Whether the earthwork at {@code pos} may take out {@code s} now: the ground, its cover, water or ice; a log or a
         * tree's own leaf only where the plan fells a tree ({@link #trees}). Anything else was put there since (a player's
         * log in the box's air is left standing, as a settler would leave it).
         */
        public boolean mayReplace(BlockPos pos, BlockState s) {
            return Siting.mayReplace(s) || (log(s) || wildLeaf(s)) && Arrays.binarySearch(trees, pos.asLong()) >= 0;
        }

        /** The door's landing (part of the pad). */
        public Reshape.Rect landing() {
            return landingRect(pl);
        }

        /** Where a settler for the site comes up: the landing's outer cell before the door, at the floor's standing height. */
        public BlockPos comeUpAt() {
            int[] o = outward(pl);
            return new BlockPos(pl.door().getX() + 2 * o[0], floor + 1, pl.door().getZ() + 2 * o[1]);
        }

        public boolean inArea(int x, int z) {
            return x >= x0 && z >= z0 && x < x0 + w && z < z0 + h;
        }

        private int index(int x, int z) {
            if (!inArea(x, z)) {
                throw new IllegalArgumentException("the column " + x + " " + z + " is outside the area");
            }
            return (z - z0) * w + (x - x0);
        }

        /** What the column (x, z) is to the ramp. */
        public Reshape.Kind kind(int x, int z) {
            return Reshape.Kind.values()[kinds[index(x, z)]];
        }

        /** The column's new surface height (the pad's is the floor; a kept column's is as it was). */
        public int newHeight(int x, int z) {
            return now[index(x, z)];
        }

        /** The column's surface height as it was read: a pond's its water's, a kept column's the height it is held at. */
        public int oldGround(int x, int z) {
            return ground[index(x, z)];
        }

        /** Whether (x, z) is under the building's box. */
        public boolean inBox(int x, int z) {
            BoundingBox b = pl.box();
            return x >= b.minX() && x <= b.maxX() && z >= b.minZ() && z <= b.maxZ();
        }

        private Look lookAt(int i) {
            int v = look[i];
            return v == 0 ? PLAIN : looks.get(Math.abs(v) - 1);
        }

        /** The column's look (top, soil, deep): its own, or its nearest neighbour's. */
        public Look look(int x, int z) {
            return lookAt(index(x, z));
        }

        /** In words, for the bridge's printout. */
        public String describe() {
            Numbers n = numbers;
            String land = reshaped ? "a margin of " + n.margin() + "; " : "the plinth, berm and landing only; ";
            return land + "cut " + n.cutColumns() + " columns (" + n.cutBlocks() + " blocks), filled " + n.fillColumns() + " ("
                    + n.fillBlocks() + "), " + n.kept() + " kept, steepest step " + n.steepest() + ", " + trees.length
                    + " blocks of trees felled" + (n.bank() > Reshape.MAX_STEP ? "; banked up to " + n.bank() + " where the land is too steep" : "")
                    + (goesRound.isEmpty() ? "" : "; the ramp goes round " + goesRound);
        }

        @Override
        public String toString() {
            return "Plan at " + x0 + " " + z0 + " (" + w + "x" + h + ", floor " + floor + "): " + describe();
        }

        // --- the earthworks ------------------------------------------------------------------------------------------

        /**
         * Every block the earthworks set, in the order they are worked (section 9): the clearing top-down; the plinth bottom-up;
         * the berm and landing, then the ramp, each by lift (cuts from the top, fills from the bottom), then nearest the pad.
         * Kept, pond and border columns are never in it. The same list for the same saved plan, whatever the world is now.
         */
        public List<Earthwork> earthworks() {
            if (earthworks == null) {
                earthworks = List.copyOf(build());
            }
            return earthworks;
        }

        private List<Earthwork> build() {
            BoundingBox box = pl.box();
            BlockState air = Blocks.AIR.defaultBlockState();
            List<Earthwork> out = new ArrayList<>();
            // the clearing: the pad's trees, the template's air in the box, what stands over the box, the cover
            LongOpenHashSet seen = new LongOpenHashSet();
            List<BlockPos> clear = new ArrayList<>();
            for (long t : trees) {
                if (seen.add(t)) {
                    clear.add(BlockPos.of(t));
                }
            }
            for (RoomTemplates.Entry e : template) {
                if (e.state().isAir()) {
                    BlockPos p = pl.world(e.local());
                    if (seen.add(p.asLong())) {
                        clear.add(p);
                    }
                }
            }
            for (int i = 0; i < kinds.length; i++) {
                int x = x0 + i % w;
                int z = z0 + i / w;
                Reshape.Kind k = Reshape.Kind.values()[kinds[i]];
                if (k == Reshape.Kind.PAD && inBox(x, z)) {
                    for (int y = Math.max(ground[i], top[i]); y > box.maxY(); y--) {
                        if (seen.add(BlockPos.asLong(x, y, z))) {
                            clear.add(new BlockPos(x, y, z));
                        }
                    }
                } else if ((k == Reshape.Kind.PAD || k == Reshape.Kind.FREE) && now[i] != ground[i]) {
                    for (int y = top[i]; y > ground[i]; y--) {
                        if (seen.add(BlockPos.asLong(x, y, z))) {
                            clear.add(new BlockPos(x, y, z));
                        }
                    }
                }
            }
            clear.sort(Comparator.<BlockPos>comparingInt(p -> -p.getY()).thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getX));
            for (BlockPos p : clear) {
                out.add(new Earthwork(p, air, Phase.CLEARING, 0));
            }
            // the plinth, under each whole block of the ground layer; and the water beside the pad's cuts, walled
            Map<Long, BlockState> layer = groundLayer(pl, template);
            List<Earthwork> plinth = new ArrayList<>();
            for (int i = 0; i < kinds.length; i++) {
                int x = x0 + i % w;
                int z = z0 + i / w;
                BlockState s = layer.get(column(x, z));
                if (s == null || kinds[i] != Reshape.Kind.PAD.ordinal() || !inBox(x, z)) {
                    continue;
                }
                for (int y = ground[i] + 1; y < floor; y++) {
                    plinth.add(new Earthwork(new BlockPos(x, y, z), s, Phase.PLINTH, 0));
                }
            }
            for (int k = 0; k < seals.length; k++) {
                int v = sealLooks[k];
                Look l = v == 0 ? PLAIN : looks.get(Math.abs(v) - 1);
                plinth.add(new Earthwork(BlockPos.of(seals[k]), l.soil(), Phase.PLINTH, 0));   // walled before the cut opens it
            }
            int lowest = plinth.stream().mapToInt(e -> e.pos().getY()).min().orElse(0);
            plinth.sort(Comparator.<Earthwork>comparingInt(e -> e.pos().getY()).thenComparingInt(e -> e.pos().getZ()).thenComparingInt(e -> e.pos().getX()));
            for (Earthwork e : plinth) {
                out.add(new Earthwork(e.pos(), e.state(), Phase.PLINTH, e.pos().getY() - lowest));
            }
            // the berm and landing, then the ramp
            out.addAll(works(Phase.PAD, true));
            out.addAll(works(Phase.RAMP, false));
            return out;
        }

        /** A block of a cut or a fill before its lift is known. */
        private record Draft(BlockPos pos, BlockState state, boolean fill, int distance) {
        }

        /** The cuts and fills of the pad outside the box ({@code pad}) or of the ramp, by lift, then nearest the pad. */
        private List<Earthwork> works(Phase phase, boolean pad) {
            Reshape.Rect padR = padRect(pl);
            Reshape.Rect landR = landingRect(pl);
            BlockState air = Blocks.AIR.defaultBlockState();
            List<Draft> drafts = new ArrayList<>();
            for (int i = 0; i < kinds.length; i++) {
                int x = x0 + i % w;
                int z = z0 + i / w;
                boolean mine = pad ? kinds[i] == Reshape.Kind.PAD.ordinal() && !inBox(x, z) : kinds[i] == Reshape.Kind.FREE.ordinal();
                if (!mine || now[i] == ground[i]) {
                    continue;
                }
                Look l = lookAt(i);
                int d = Math.min(distance(padR, x, z), distance(landR, x, z));
                if (now[i] < ground[i]) {
                    for (int y = ground[i]; y > now[i]; y--) {
                        drafts.add(new Draft(new BlockPos(x, y, z), air, false, d));
                    }
                    drafts.add(new Draft(new BlockPos(x, now[i], z), l.top(), false, d));      // the new surface, capped
                } else {
                    if (look[i] > 0 && l.top() != l.soil()) {
                        drafts.add(new Draft(new BlockPos(x, ground[i], z), l.soil(), true, d)); // the old top, buried
                    }
                    for (int y = ground[i] + 1; y <= now[i]; y++) {
                        drafts.add(new Draft(new BlockPos(x, y, z), l.at(now[i] - y), true, d));
                    }
                }
            }
            int topCut = Integer.MIN_VALUE;
            int bottomFill = Integer.MAX_VALUE;
            for (Draft d : drafts) {
                if (d.fill()) {
                    bottomFill = Math.min(bottomFill, d.pos().getY());
                } else {
                    topCut = Math.max(topCut, d.pos().getY());
                }
            }
            record Lifted(Earthwork e, boolean fill, int distance) {
            }
            List<Lifted> lifted = new ArrayList<>(drafts.size());
            for (Draft d : drafts) {
                int lift = d.fill() ? d.pos().getY() - bottomFill : topCut - d.pos().getY();
                lifted.add(new Lifted(new Earthwork(d.pos(), d.state(), phase, lift), d.fill(), d.distance()));
            }
            lifted.sort(Comparator.<Lifted>comparingInt(l -> l.e().lift())
                    .thenComparing(Lifted::fill)
                    .thenComparingInt(Lifted::distance)
                    .thenComparingInt(l -> l.e().pos().getZ())
                    .thenComparingInt(l -> l.e().pos().getX())
                    .thenComparingInt(l -> l.e().pos().getY()));
            List<Earthwork> out = new ArrayList<>(lifted.size());
            for (Lifted l : lifted) {
                out.add(l.e());
            }
            return out;
        }

        /**
         * Every earthwork set at once, with the dig's flags (no neighbour or shape updates): for the tests, and a creative
         * raise. A cell that is no longer the ground, its cover, air or water (someone has put a block there since the plan)
         * is left as it is, as a settler would leave it. Returns how many blocks were set.
         */
        public int applyNow(ServerLevel level) {
            LongOpenHashSet ours = new LongOpenHashSet();
            int n = 0;
            for (Earthwork e : earthworks()) {
                BlockPos p = e.pos();
                if (!level.isLoaded(p)) {
                    continue;
                }
                BlockState cur = level.getBlockState(p);
                if (cur == e.state()) {
                    ours.add(p.asLong());
                    continue;
                }
                if (!ours.contains(p.asLong()) && (!mayReplace(p, cur) || level.getBlockEntity(p) != null)) {
                    continue;
                }
                DigJob.place(level, new DigJob.Task(p, e.state(), null));
                ours.add(p.asLong());
                n++;
            }
            return n;
        }

        // --- saved -----------------------------------------------------------------------------------------------------

        /**
         * The plan as section 9 saves it (a raised job keeps it under {@code reshape}): the area (x0, z0, w, h, floor); per
         * column byte offsets from the floor for the new height, the old ground and the old top (its cover's), its kind and
         * its look; the looks as a list of block states, three to a look; the pad's felled trees; and the numbers.
         */
        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("x0", x0);
            t.putInt("z0", z0);
            t.putInt("w", w);
            t.putInt("h", h);
            t.putInt("floor", floor);
            t.putBoolean("reshaped", reshaped);
            t.putByteArray("kind", kinds.clone());
            t.putByteArray("new", offsets(now));
            t.putByteArray("ground", offsets(ground));
            t.putByteArray("top", offsets(top));
            t.putByteArray("look", look.clone());
            ListTag states = new ListTag();
            for (Look l : looks) {
                states.add(NbtUtils.writeBlockState(l.top()));
                states.add(NbtUtils.writeBlockState(l.soil()));
                states.add(NbtUtils.writeBlockState(l.deep()));
            }
            t.put("states", states);
            t.putLongArray("trees", trees);
            if (seals.length > 0) {
                t.putLongArray("seals", seals);
                t.putByteArray("seal_looks", sealLooks.clone());
            }
            Numbers n = numbers;
            t.putIntArray("numbers", new int[]{n.margin(), n.cutColumns(), n.fillColumns(), n.cutBlocks(), n.fillBlocks(), n.kept(), n.steepest(),
                    n.bank(), n.seamed() ? 1 : 0});
            t.putString("goes_round", goesRound);
            return t;
        }

        private byte[] offsets(int[] a) {
            byte[] out = new byte[a.length];
            for (int i = 0; i < a.length; i++) {
                out[i] = (byte) Math.max(Byte.MIN_VALUE, Math.min(Byte.MAX_VALUE, a[i] - floor));   // held columns only are clamped
            }
            return out;
        }

        /**
         * A saved plan back ({@link #save}), for the placement and the template it was made for: nothing is read from the
         * world, so its earthworks are the saved plan's. Null if the tag isn't one.
         */
        @Nullable
        public static Plan load(CompoundTag t, PlanPlacement pl, List<RoomTemplates.Entry> template) {
            int w = t.getInt("w");
            int h = t.getInt("h");
            int n = w * h;
            byte[] kinds = t.getByteArray("kind");
            byte[] now = t.getByteArray("new");
            byte[] ground = t.getByteArray("ground");
            byte[] top = t.getByteArray("top");
            byte[] look = t.getByteArray("look");
            ListTag states = t.getListOrEmpty("states");
            int[] nums = t.getIntArray("numbers");
            if (w < 1 || h < 1 || w > AREA_MAX || h > AREA_MAX || kinds.length != n || now.length != n || ground.length != n
                    || top.length != n || look.length != n || states.size() % 3 != 0 || (nums.length != 7 && nums.length != 9)) {
                LOGGER.warn("Settlement Core: a saved site plan is broken ({}x{}, {} kinds): dropped", w, h, kinds.length);
                return null;
            }
            List<Look> looks = new ArrayList<>();
            var blocks = BuiltInRegistries.BLOCK.asLookup();
            for (int k = 0; k < states.size(); k += 3) {
                Look l = new Look(NbtUtils.readBlockState(blocks, states.getCompound(k)), NbtUtils.readBlockState(blocks, states.getCompound(k + 1)),
                        NbtUtils.readBlockState(blocks, states.getCompound(k + 2)));
                // a block that is gone (its mod removed) reads back as air: never lay air as ground
                looks.add(l.top().isAir() || l.soil().isAir() || l.deep().isAir() ? PLAIN : l);
            }
            long[] seals = t.getLongArray("seals");
            byte[] sealLooks = t.getByteArray("seal_looks");
            if (sealLooks.length != seals.length) {
                seals = new long[0];
                sealLooks = new byte[0];
            }
            for (byte v : sealLooks) {
                if (Math.abs(v) > looks.size()) {
                    LOGGER.warn("Settlement Core: a saved site plan has a broken seal: dropped");
                    return null;
                }
            }
            long[] trees = t.getLongArray("trees");
            Arrays.sort(trees);
            int floor = t.getInt("floor");
            for (int i = 0; i < n; i++) {
                if (kinds[i] < 0 || kinds[i] >= Reshape.Kind.values().length || Math.abs(look[i]) > looks.size()) {
                    LOGGER.warn("Settlement Core: a saved site plan has a broken column {}: dropped", i);
                    return null;
                }
            }
            return new Plan(pl, template, t.getInt("x0"), t.getInt("z0"), w, h, floor, t.getBoolean("reshaped"), kinds.clone(),
                    heights(now, floor), heights(ground, floor), heights(top, floor), look.clone(), looks, trees, seals, sealLooks.clone(),
                    new Numbers(nums[0], nums[1], nums[2], nums[3], nums[4], nums[5], nums[6], nums.length > 7 ? nums[7] : 0,
                            nums.length > 8 && nums[8] != 0), t.getString("goes_round"));
        }

        private static int[] heights(byte[] offsets, int floor) {
            int[] out = new int[offsets.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = floor + offsets[i];
            }
            return out;
        }
    }

    // --- reading the world -------------------------------------------------------------------------------------------

    private static final class Refused extends RuntimeException {
        final Refusal refusal;

        Refused(Refusal refusal) {
            super(refusal.why(), null, false, false);
            this.refusal = refusal;
        }
    }

    /** Why the ramp goes round a column. */
    private enum Why {
        BLOCK, TREE, BUILDING, CAVE, WATER
    }

    /** What a cell over a column's ground is, when it isn't air or cover. */
    private static final byte LOG = 0, LEAF = 1, WATER = 2, OTHER = 3;

    /** One column as it was read. */
    private static final class Col {
        final int x;
        final int z;
        /** The first cell from the top with a shape that isn't cover, leaves or water. */
        int solidTop;
        /** The ground: the highest natural block at or under solidTop (under a player's post, the grass it stands in). */
        int foot;
        /** The top of the water, lava or ice lying on the ground; NO if none. */
        int fluidTop = NO;
        boolean lava;
        /** The top of the cover lying on the ground (foot if none). */
        int coverTop;
        /** Everything else over the ground, top-down: its heights and what each is (LOG, LEAF, WATER, OTHER). */
        final IntArrayList thingY = new IntArrayList();
        final ByteArrayList thingType = new ByteArrayList();
        /** The height the ramp holds it at, if it is kept for what stands on it. */
        int held;
        boolean judged;
        @Nullable
        Why why;

        Col(int x, int z) {
            this.x = x;
            this.z = z;
        }

        boolean fluid() {
            return fluidTop != NO;
        }

        /** Whether something stands on the ground (a post, a chest, a fence, a tree's trunk), not only over it. */
        boolean occupied() {
            return !thingY.isEmpty() && thingY.getInt(thingY.size() - 1) <= coverTop + 1;
        }
    }

    private static final class Planner {
        final Level level;
        final PlanPlacement pl;
        final RoomShoring.Warren warren;
        /** "the Test Hall", and "the Test Hall's". */
        final String the;
        final String whose;
        final BoundingBox box;
        final int floor;
        final Reshape.Rect pad;
        final Reshape.Rect landing;
        @Nullable final Reshape.Rect court;
        final Reshape.Rect all;
        final Reshape.Side side;
        final List<RoomTemplates.Entry> template;
        final Map<Long, BlockState> layer;
        final Long2ObjectOpenHashMap<Col> cols = new Long2ObjectOpenHashMap<>();
        /** Water beside the pad's cuts, to wall up with the soil beside it ({@link #sealPockets}), in the order found. */
        final it.unimi.dsi.fastutil.longs.LongArrayList seals = new it.unimi.dsi.fastutil.longs.LongArrayList();
        final List<Look> sealLooks = new ArrayList<>();
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        LongSet buildings = new LongOpenHashSet();

        Planner(Level level, PlanPlacement pl, RoomShoring.Warren warren) {
            this.level = level;
            this.pl = pl;
            this.warren = warren;
            this.the = "the " + pl.room().displayName();
            this.whose = possessive(pl);
            this.box = pl.box();
            this.floor = pl.door().getY() - 1;
            this.pad = padRect(pl);
            this.landing = landingRect(pl);
            this.court = courtRect(pl);
            this.all = new Reshape.Rect(Math.min(pad.x0(), landing.x0()), Math.min(pad.z0(), landing.z0()),
                    Math.max(pad.x1(), landing.x1()), Math.max(pad.z1(), landing.z1()));
            int[] o = outward(pl);
            this.side = o[0] < 0 ? Reshape.Side.WEST : o[0] > 0 ? Reshape.Side.EAST : o[1] < 0 ? Reshape.Side.NORTH : Reshape.Side.SOUTH;
            this.template = RoomTemplates.lookup(level, pl.room().id());
            this.layer = groundLayer(pl, template);
        }

        Refused refuse(String why, BlockPos spot) {
            return new Refused(new Refusal(why, spot));
        }

        BlockState at(int x, int y, int z) {
            return level.getBlockState(cursor.set(x, y, z));
        }

        boolean inPad(int x, int z) {
            return (in(pad, x, z) || in(landing, x, z)) && !inCourt(x, z);
        }

        /** In the room's open court ({@link #courtRect}): never the pad's, and held as it is. */
        boolean inCourt(int x, int z) {
            return court != null && in(court, x, z);
        }

        /** {@code f}'s court columns held as they are (KEPT; a pond in it is marked FLUID by {@link #heights}): a frame stamps them PAD. */
        Reshape.Frame courted(Reshape.Frame f) {
            if (court != null) {
                for (int i = 0; i < f.kinds().length; i++) {
                    if (inCourt(f.x0() + i % f.w(), f.z0() + i / f.w())) {
                        f.kinds()[i] = Reshape.Kind.KEPT;
                    }
                }
            }
            return f;
        }

        boolean inBox(int x, int z) {
            return x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ();
        }

        /** Another building's cell within 1 of (x, y, z). */
        boolean building(int x, int y, int z) {
            if (buildings.isEmpty()) {
                return false;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (buildings.contains(BlockPos.asLong(x + dx, y + dy, z + dz))) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        void loaded(BlockPos p) {
            if (!level.isLoaded(p)) {
                throw refuse("The land at " + p.getX() + " " + p.getZ() + " is not loaded: the whole site must be", p);
            }
        }

        Col col(int x, int z) {
            long k = column(x, z);
            Col c = cols.get(k);
            if (c == null) {
                c = scan(x, z);
                cols.put(k, c);
            }
            return c;
        }

        /** Reads a column from the heightmap down, never loading its chunk (an unloaded one refuses the site). */
        Col scan(int x, int z) {
            if (!level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))) {
                throw refuse("The land at " + x + " " + z + " is not loaded: the whole site must be", new BlockPos(x, floor, z));
            }
            Col c = new Col(x, z);
            int min = level.getMinY();
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            int y = top;
            for (; y > min; y--) {
                BlockState s = at(x, y, z);
                if (!(s.isAir() || wet(s) || cover(s) || s.getBlock() instanceof LeavesBlock || s.getCollisionShape(level, cursor).isEmpty())) {
                    break;
                }
            }
            c.solidTop = y;
            int foot = y;
            while (foot > min && y - foot < STACK_MAX && !ground(at(x, foot, z))) {
                foot--;
            }
            if (!ground(at(x, foot, z))) {
                foot = y;                                        // nothing natural under it near enough: it is the ground
            }
            c.foot = foot;
            for (int yy = foot + 1; yy <= top; yy++) {           // water, lava or ice lying on the ground
                BlockState s = at(x, yy, z);
                if (!wet(s)) {
                    break;
                }
                c.fluidTop = yy;
                c.lava |= s.getFluidState().is(FluidTags.LAVA);
            }
            c.coverTop = foot;
            if (!c.fluid()) {
                for (int yy = foot + 1; yy <= top && cover(at(x, yy, z)); yy++) {
                    c.coverTop = yy;
                }
            }
            for (int yy = top; yy > foot; yy--) {
                BlockState s = at(x, yy, z);
                if (s.isAir() || cover(s) || yy <= (c.fluid() ? c.fluidTop : c.coverTop)) {
                    continue;
                }
                c.thingY.add(yy);
                c.thingType.add(s.hasBlockEntity() ? OTHER : log(s) ? LOG : wildLeaf(s) ? LEAF : wet(s) ? WATER : OTHER);
            }
            if (!ground(at(x, foot, z))) {
                c.thingY.add(foot);                              // no natural ground under it at all: it is something's
                c.thingType.add(OTHER);
            }
            // a low thing on the ground (a chest, a fence, a path) is walked against at its top; a tall one at its foot
            int low = c.thingY.isEmpty() ? NO : c.thingY.getInt(c.thingY.size() - 1);
            boolean solidLow = low == foot + 1 && !at(x, low, z).getCollisionShape(level, cursor).isEmpty()
                    && at(x, low + 1, z).getCollisionShape(level, cursor).isEmpty();
            c.held = solidLow ? foot + 1 : foot;
            return c;
        }

        /** Why the ramp must go round a column whatever it does there (what stands on it, a building by it); null if nothing. */
        @Nullable
        Why kept(Col c) {
            if (!c.judged) {
                c.judged = true;
                if (!c.fluid() && c.occupied()) {
                    byte t = c.thingType.getByte(c.thingType.size() - 1);
                    c.why = t == LEAF || t == LOG && c.thingType.contains(LEAF) ? Why.TREE : t == WATER ? Why.WATER : Why.BLOCK;
                } else {
                    int hi = c.fluid() ? c.fluidTop : c.coverTop;
                    for (int y = c.foot - 2; y <= hi + 1 && c.why == null; y++) {
                        if (building(c.x, y, c.z)) {
                            c.why = Why.BUILDING;
                        }
                    }
                }
            }
            return c.why;
        }

        /** The height the solve is given for a column outside the pad. */
        int height(Col c) {
            if (c.fluid()) {
                return c.fluidTop;
            }
            return kept(c) != null && c.occupied() ? c.held : c.foot;
        }

        // --- the preview ---------------------------------------------------------------------------------------------

        /**
         * The plan's refusal (or none), and the plinth's columns as the plan reads them: the same {@link #col} reading the pad's
         * refusals are made from, so what is drawn is what is judged. A column whose land is not loaded is left out.
         */
        Preview preview(boolean reshape) {
            Refusal refusal = null;
            try {
                plan(reshape);
            } catch (Refused r) {
                refusal = r.refusal;
            }
            List<Column> columns = new ArrayList<>();
            if (!template.isEmpty() && box.minY() == floor) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    for (int x = box.minX(); x <= box.maxX(); x++) {
                        if (!layer.containsKey(column(x, z))) {
                            continue;
                        }
                        try {
                            Col c = col(x, z);
                            columns.add(new Column(x, z, c.foot, floor - 1 - c.foot));
                        } catch (Refused unloaded) {
                            if (refusal == null) {
                                refusal = unloaded.refusal;
                            }
                        }
                    }
                }
            }
            return new Preview(pl, refusal, columns);
        }

        // --- the plan ------------------------------------------------------------------------------------------------

        Result plan(boolean reshape) {
            if (template.isEmpty()) {
                throw refuse(cap(whose) + " template is missing", pl.door());
            }
            if (box.minY() != floor) {
                throw refuse(cap(the) + " has rooms under its ground layer: a raised building can't have a cellar yet", pl.door());
            }
            Reshape.Frame widest = Reshape.frame(pad, landing, side, Reshape.MARGIN_MAX);
            if (widest.w() > AREA_MAX || widest.h() > AREA_MAX) {
                throw refuse(cap(whose) + " site is too big to level: at most "
                        + AREA_MAX + " by " + AREA_MAX + " columns with its ramp", pl.door());
            }
            int lo = Math.max(level.getMinY(), floor - 48);
            int hi = Math.min((level.getMaxY() + 1) - 1, floor + 64);
            BoundingBox near = new BoundingBox(widest.x0() - 1, lo, widest.z0() - 1, widest.x0() + widest.w(), hi, widest.z0() + widest.h());
            buildings = level instanceof ServerLevel server ? warren.roomCells(server, near, box) : new LongOpenHashSet();
            long[] trees = sitePad();
            besidePad();
            sealPockets();
            Map<Long, Why> extra = new LinkedHashMap<>();
            Reshape.Frame f;
            int[] g;
            int[] now;
            int margin;
            boolean seamed = false;
            if (!reshape) {
                f = padFrame();
                g = heights(f, extra);
                now = new int[g.length];
                for (int i = 0; i < g.length; i++) {
                    now[i] = f.kinds()[i] == Reshape.Kind.PAD ? floor : g[i];
                }
                margin = 0;
            } else {
                margin = Reshape.firstMargin(edgeGap());
                Reshape.Result r;
                for (int pass = 1; ; ) {
                    while (true) {
                        f = courted(Reshape.frame(pad, landing, side, margin));
                        g = heights(f, extra);
                        r = Reshape.plan(f.w(), f.h(), g, f.kinds(), null, floor, margin);
                        if (!r.tooSteep()) {
                            break;
                        }
                        if (margin >= Reshape.MARGIN_MAX) {
                            throw steep(f, g, r);
                        }
                        margin = Reshape.widen(margin);
                    }
                    Map<Long, Why> failing = spans(f, g, r.height());
                    Map<Long, Why> levelled = besideBuildings(f, g, r.height());
                    if (failing.isEmpty() && levelled.isEmpty()) {
                        break;
                    }
                    if (!failing.isEmpty()) {
                        if (pass >= PASSES) {
                            long k = failing.keySet().iterator().next();
                            int x = (int) (k >> 32);
                            int z = (int) k;
                            throw refuse("The ground under the ramp is hollow or built on round " + x + " " + z + ": no ramp goes round it in "
                                    + PASSES + " tries", new BlockPos(x, col(x, z).foot, z));
                        }
                        pass++;
                    }
                    // (the columns kept beside a building don't count as a pass: they only ever come from the free columns
                    // round its ring, so they run out)
                    extra.putAll(failing);
                    levelled.forEach(extra::putIfAbsent);
                }
                now = r.height();
                seamed = r.seamed();
                walk(f, now, seamed);
            }
            return finish(f, g, now, margin, trees, extra, reshape, seamed);
        }

        /** The pad and landing with a ring round them, for a plan that leaves the land alone. */
        Reshape.Frame padFrame() {
            int x0 = all.x0() - 1;
            int z0 = all.z0() - 1;
            int w = all.x1() - all.x0() + 3;
            int h = all.z1() - all.z0() + 3;
            Reshape.Kind[] kinds = new Reshape.Kind[w * h];
            for (int i = 0; i < kinds.length; i++) {
                kinds[i] = inPad(x0 + i % w, z0 + i / w) ? Reshape.Kind.PAD : Reshape.Kind.BORDER;
            }
            return courted(new Reshape.Frame(x0, z0, w, h, kinds));
        }

        /**
         * The heights a frame is solved on, marking its ponds FLUID and the columns the ramp goes round KEPT: what stands on
         * them, a building beside them, and what an earlier pass found ({@code extra}). The pad's are its ground (the solve
         * holds them at the floor all the same).
         */
        int[] heights(Reshape.Frame f, Map<Long, Why> extra) {
            int[] g = new int[f.w() * f.h()];
            for (int i = 0; i < g.length; i++) {
                int x = f.x0() + i % f.w();
                int z = f.z0() + i / f.w();
                Col c = col(x, z);
                Reshape.Kind k = f.kinds()[i];
                if (k == Reshape.Kind.PAD) {
                    g[i] = c.foot;
                    continue;
                }
                if (c.fluid()) {
                    f.kinds()[i] = Reshape.Kind.FLUID;
                } else if (k == Reshape.Kind.FREE && (kept(c) != null || extra.containsKey(column(x, z)))) {
                    f.kinds()[i] = Reshape.Kind.KEPT;
                }
                g[i] = height(c);
            }
            return g;
        }

        /** The largest height gap between the floor and the land just outside the pad and landing: where the margin starts. */
        int edgeGap() {
            int gap = 0;
            for (int z = all.z0() - 1; z <= all.z1() + 1; z++) {
                for (int x = all.x0() - 1; x <= all.x1() + 1; x++) {
                    if (inPad(x, z) || inCourt(x, z)) {
                        continue;                                // the court is held as it is: no ramp starts from its bank
                    }
                    boolean near = false;
                    for (int dz = -1; dz <= 1 && !near; dz++) {
                        for (int dx = -1; dx <= 1 && !near; dx++) {
                            near = inPad(x + dx, z + dz);
                        }
                    }
                    if (near) {
                        gap = Math.max(gap, Math.abs(height(col(x, z)) - floor));
                    }
                }
            }
            return gap;
        }

        // --- the pad -------------------------------------------------------------------------------------------------

        /**
         * The pad's own refusals, and its trees: the plinth deeper than SKIRT_MAX (checked first, the deepest named); lava,
         * water deeper than WATER_MAX or over the floor; anything but the ground, its cover and trees where the pad is cut,
         * filled or built; hollow ground under it; another building within 1. Returns the trees felled, packed and sorted.
         */
        long[] sitePad() {
            int worst = 0;
            BlockPos worstAt = null;
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    Col c = col(x, z);
                    int depth = floor - 1 - c.foot;
                    if (layer.containsKey(column(x, z)) && depth > worst) {
                        worst = depth;
                        worstAt = new BlockPos(x, c.foot, z);
                    }
                }
            }
            if (worst > SKIRT_MAX) {
                throw refuse(String.format(Locale.ROOT, "The ground falls away %d under %s floor at %d %d: its plinth would be %d deep, and %d is the most",
                        floor - worstAt.getY(), whose, worstAt.getX(), worstAt.getZ(), worst, SKIRT_MAX), worstAt);
            }
            List<BlockPos> logs = new ArrayList<>();
            List<BlockPos> leaves = new ArrayList<>();
            for (int z = all.z0(); z <= all.z1(); z++) {
                for (int x = all.x0(); x <= all.x1(); x++) {
                    if (inPad(x, z)) {
                        padColumn(x, z, logs, leaves);
                    }
                }
            }
            return fell(logs, leaves);
        }

        void padColumn(int x, int z, List<BlockPos> logs, List<BlockPos> leaves) {
            Col c = col(x, z);
            boolean boxed = inBox(x, z);
            if (c.fluid()) {
                BlockPos at = new BlockPos(x, c.fluidTop, z);
                if (c.lava) {
                    throw refuse("Lava at " + at.getX() + " " + at.getY() + " " + at.getZ() + ", on " + whose + " site", at);
                }
                if (c.fluidTop > floor) {
                    throw refuse("The water at " + x + " " + z + " stands " + (c.fluidTop - floor) + " above " + whose
                            + " floor: the site would be under it", at);
                }
                if (c.fluidTop - c.foot > WATER_MAX) {
                    throw refuse("The water at " + x + " " + z + " is " + (c.fluidTop - c.foot) + " deep: too deep to raise " + the + " over ("
                            + WATER_MAX + " at most)", at);
                }
            }
            int g = c.foot;
            int lo = Math.min(g, floor) - 2;
            int hi = boxed ? box.maxY() : Math.max(floor, c.coverTop);
            for (int y = lo; y <= hi; y++) {
                if (building(x, y, z)) {
                    BlockPos p = new BlockPos(x, y, z);
                    throw refuse("Another building is too near at " + x + " " + y + " " + z + ": " + whose + " site would cut into it", p);
                }
            }
            for (int y = lo; y <= g; y++) {
                BlockState s = at(x, y, z);
                if (!ground(s) || s.hasBlockEntity()) {
                    BlockPos p = new BlockPos(x, y, z);
                    throw refuse(s.isAir() || wet(s)
                            ? "The ground is hollow at " + x + " " + y + " " + z + ", under " + whose + " site"
                            : "Something is in the ground at " + x + " " + y + " " + z + ", under " + whose + " site: only the ground itself can be cut", p);
                }
            }
            // what stands on a column the pad cuts, fills or builds on goes with it: a torch on a cut berm would float
            boolean moved = boxed || g != floor;
            int chain = c.fluid() ? c.fluidTop : c.coverTop;
            for (int k = c.thingY.size() - 1; k >= 0; k--) {     // lowest first
                int y = c.thingY.getInt(k);
                if (y > hi && !(moved && y <= chain + 1)) {
                    break;
                }
                chain = Math.max(chain, y);
                BlockPos p = new BlockPos(x, y, z);
                switch (c.thingType.getByte(k)) {
                    case LOG -> logs.add(p);
                    case LEAF -> leaves.add(p);
                    case WATER -> throw refuse("Water at " + x + " " + y + " " + z + " would pour onto " + whose + " site", p);
                    default -> throw refuse("Something stands at " + x + " " + y + " " + z + ", on " + whose
                            + " site: only the ground, what grows on it and its trees can be cleared", p);
                }
            }
        }

        /** A pond over the floor beside the pad would lose its bank to the pad's cut. */
        void besidePad() {
            for (int z = all.z0(); z <= all.z1(); z++) {
                for (int x = all.x0(); x <= all.x1(); x++) {
                    if (!inPad(x, z)) {
                        continue;
                    }
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        int nx = x + d.getStepX();
                        int nz = z + d.getStepZ();
                        if (inPad(nx, nz)) {
                            continue;
                        }
                        Col n = col(nx, nz);
                        if (n.fluid() && n.fluidTop > floor) {
                            throw refuse("The " + (n.lava ? "lava" : "water") + " at " + nx + " " + nz + " stands " + (n.fluidTop - floor) + " above "
                                    + whose + " floor, beside its site: its bank would be cut", new BlockPos(nx, n.fluidTop, nz));
                        }
                    }
                }
            }
        }

        /**
         * Water in the wall beside a cut of the berm or landing (between the floor and the old ground, in a 4-neighbour
         * column off the pad) would pour onto the site as the cut opens it: each such cell is walled with that column's soil in
         * the plinth phase, before the cut (section 9: "walled with the column's soil first"). The cells are saved with the
         * plan. Lava there refuses the site. A player's waterlogged block there refuses it too: walling it would take it.
         */
        void sealPockets() {
            LongOpenHashSet seen = new LongOpenHashSet();
            for (int z = all.z0(); z <= all.z1(); z++) {
                for (int x = all.x0(); x <= all.x1(); x++) {
                    if (!inPad(x, z) || inBox(x, z)) {
                        continue;
                    }
                    Col c = col(x, z);
                    if (c.fluid() || c.foot <= floor) {
                        continue;                                // not cut
                    }
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        int nx = x + d.getStepX();
                        int nz = z + d.getStepZ();
                        if (inPad(nx, nz)) {
                            continue;
                        }
                        Col n = col(nx, nz);
                        for (int y = floor + 1; y <= c.foot; y++) {
                            BlockState s = at(nx, y, nz);
                            if (s.getFluidState().isEmpty()) {
                                continue;
                            }
                            BlockPos q = new BlockPos(nx, y, nz);
                            if (s.getFluidState().is(FluidTags.LAVA)) {
                                throw refuse("Lava at " + nx + " " + y + " " + nz + ", beside " + whose + " site: the cut beside it would let it out", q);
                            }
                            if (!(s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) && !cover(s)) {
                                throw refuse("Water at " + nx + " " + y + " " + nz + " would pour onto " + whose + " site through the cut beside it", q);
                            }
                            if (seen.add(q.asLong())) {
                                Look l = sample(n);
                                if (l == null) {
                                    l = sample(c);
                                }
                                seals.add(q.asLong());
                                sealLooks.add(l == null ? PLAIN : l);
                            }
                        }
                    }
                }
            }
        }

        /**
         * The pad's trees, felled in the clearing: from each log on the pad, the logs joined to it (26 round) and the leaves
         * that are theirs (reached through leaves no further than each leaf's own distance to a log, so a neighbour's canopy
         * stays); the pad's loose leaves too. Refused over TREE_MAX cells, when it grows into a tree rooted outside the pad,
         * or when it touches anything but the land, its cover, water and trees.
         */
        long[] fell(List<BlockPos> logSeeds, List<BlockPos> leafSeeds) {
            if (logSeeds.isEmpty() && leafSeeds.isEmpty()) {
                return new long[0];
            }
            BlockPos first = logSeeds.isEmpty() ? leafSeeds.get(0) : logSeeds.get(0);
            String which = "The tree at " + first.getX() + " " + first.getY() + " " + first.getZ();
            LongOpenHashSet tree = new LongOpenHashSet();
            for (BlockPos seed : logSeeds) {
                if (!tree.contains(seed.asLong())) {
                    fellOne(seed, tree);
                }
            }
            for (BlockPos l : leafSeeds) {
                tree.add(l.asLong());                            // the pad's own loose leaves
            }
            if (tree.size() > TREE_MAX) {
                throw tooBig(which, first);
            }
            long[] out = tree.toLongArray();
            Arrays.sort(out);
            for (long k : out) {
                BlockPos p = BlockPos.of(k);
                for (Direction dir : Direction.values()) {
                    BlockPos n = p.relative(dir);
                    if (tree.contains(n.asLong()) || level.isOutsideBuildHeight(n)) {
                        continue;
                    }
                    loaded(n);
                    if (!wild(level.getBlockState(n))) {
                        throw refuse(which + " touches something at " + n.getX() + " " + n.getY() + " " + n.getZ() + ": it can't be felled for "
                                + the + " without it", n);
                    }
                }
            }
            return out;
        }

        /**
         * One tree, from a log on the pad, into {@code tree}: its logs (26 round) and the leaves that are its own (reached
         * through leaves no further than each leaf's own distance to a log, so a neighbour's canopy stays). A tree with no
         * leaf of its own is no tree: a player's pile of logs, a palisade, a beam, and the site is refused.
         */
        void fellOne(BlockPos seed, LongOpenHashSet tree) {
            String which = "The tree at " + seed.getX() + " " + seed.getY() + " " + seed.getZ();
            List<BlockPos> logs = new ArrayList<>();
            ArrayDeque<BlockPos> q = new ArrayDeque<>();
            tree.add(seed.asLong());
            logs.add(seed);
            q.add(seed);
            while (!q.isEmpty()) {
                BlockPos p = q.poll();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            BlockPos n = p.offset(dx, dy, dz);
                            if (tree.contains(n.asLong()) || level.isOutsideBuildHeight(n)) {
                                continue;
                            }
                            loaded(n);
                            if (!log(level.getBlockState(n))) {
                                continue;
                            }
                            if (!inPad(n.getX(), n.getZ()) && ground(level.getBlockState(n.below()))) {
                                throw refuse(which + " grows into another, rooted outside " + whose + " site at " + n.getX() + " " + n.getY() + " "
                                        + n.getZ() + ": it can't be felled alone", n);
                            }
                            tree.add(n.asLong());
                            logs.add(n);
                            q.add(n);
                            if (tree.size() > TREE_MAX) {
                                throw tooBig(which, seed);
                            }
                        }
                    }
                }
            }
            Long2IntOpenHashMap depth = new Long2IntOpenHashMap();
            LongOpenHashSet seen = new LongOpenHashSet();
            ArrayDeque<BlockPos> lq = new ArrayDeque<>(logs);
            for (BlockPos l : logs) {
                depth.put(l.asLong(), 0);
                seen.add(l.asLong());
            }
            int leaves = 0;
            while (!lq.isEmpty()) {
                BlockPos p = lq.poll();
                int d = depth.get(p.asLong());
                if (d >= LeavesBlock.DECAY_DISTANCE) {
                    continue;
                }
                for (Direction dir : Direction.values()) {
                    BlockPos n = p.relative(dir);
                    if (!seen.add(n.asLong()) || level.isOutsideBuildHeight(n)) {
                        continue;
                    }
                    loaded(n);
                    BlockState s = level.getBlockState(n);
                    if (!wildLeaf(s)) {
                        continue;
                    }
                    if (d + 1 > s.getValue(LeavesBlock.DISTANCE) && nearerLog(n, d, tree)) {
                        continue;                                // nearer another tree's logs: that tree's leaf
                    }
                    depth.put(n.asLong(), d + 1);
                    leaves++;                                    // (a leaf another tree on the pad took first counts too)
                    tree.add(n.asLong());
                    lq.add(n);
                    if (tree.size() > TREE_MAX) {
                        throw tooBig(which, seed);
                    }
                }
            }
            if (leaves == 0) {
                throw refuse("The logs at " + seed.getX() + " " + seed.getY() + " " + seed.getZ() + " have no leaves: they are someone's, not a tree,"
                        + " and only trees are felled for " + the, seed);
            }
        }

        /**
         * Whether a log that isn't {@code tree}'s stands within {@code limit} steps of the leaf at {@code from}, through leaves (the distance a leaf's
         * property keeps). A leaf's distance says it is nearer another tree's logs than this tree's, but the world doesn't keep it right: leaves set
         * by a command, placed with a structure, or left in a world that was frozen before they ticked, hold whatever they were given (the film of
         * 2026-10-01: an oak with every leaf at distance 1 was felled with its crown left hanging in the air). So it is believed only where a log
         * really is that near.
         */
        boolean nearerLog(BlockPos from, int limit, LongOpenHashSet tree) {
            ArrayDeque<BlockPos> q = new ArrayDeque<>();
            Long2IntOpenHashMap steps = new Long2IntOpenHashMap();
            q.add(from);
            steps.put(from.asLong(), 0);
            while (!q.isEmpty()) {
                BlockPos p = q.poll();
                int k = steps.get(p.asLong());
                if (k >= limit) {
                    continue;
                }
                for (Direction dir : Direction.values()) {
                    BlockPos n = p.relative(dir);
                    if (steps.containsKey(n.asLong()) || level.isOutsideBuildHeight(n)) {
                        continue;
                    }
                    loaded(n);
                    BlockState s = level.getBlockState(n);
                    if (log(s) && !tree.contains(n.asLong())) {
                        return true;                             // k + 1 steps, within the limit
                    }
                    if (s.getBlock() instanceof LeavesBlock) {
                        steps.put(n.asLong(), k + 1);
                        q.add(n);
                    }
                }
            }
            return false;
        }

        Refused tooBig(String which, BlockPos first) {
            return refuse(which + " is too big to fell for " + the + ": more than " + TREE_MAX + " blocks", first);
        }

        // --- the ramp ------------------------------------------------------------------------------------------------

        /**
         * The columns a solve would cut or fill that it mustn't: in each one's touched span (from 2 under the lower of its old
         * and new surfaces up to the higher of its cover's top and its new surface) anything but the ground under the old
         * surface and air or cover over it; a new surface within 2 of air under it (a cave, a dug room); another building within
         * 1; water beside a cut.
         */
        Map<Long, Why> spans(Reshape.Frame f, int[] g, int[] now) {
            Map<Long, Why> out = new LinkedHashMap<>();
            for (int i = 0; i < g.length; i++) {
                if (f.kinds()[i] != Reshape.Kind.FREE || now[i] == g[i]) {
                    continue;
                }
                Col c = col(f.x0() + i % f.w(), f.z0() + i / f.w());
                Why why = span(c, now[i]);
                if (why != null) {
                    out.put(column(c.x, c.z), why);
                }
            }
            return out;
        }

        /**
         * The free columns beside another building's ring (its cells and 1 round them, kept) that a solve leaves more than 1 off
         * the ring's height: kept too, at their own, so the ramp meets the building level. Reshape's second pass sets kept
         * columns aside, so a fill 2 columns out from a wall could stand several over the building's ground and bury its door
         * (or a cut undercut it). Only columns the solve changes: the land as it was is the land's.
         */
        Map<Long, Why> besideBuildings(Reshape.Frame f, int[] g, int[] now) {
            Map<Long, Why> out = new LinkedHashMap<>();
            int w = f.w();
            for (int i = 0; i < g.length; i++) {
                if (f.kinds()[i] != Reshape.Kind.FREE || now[i] == g[i]) {
                    continue;
                }
                int x = i % w;
                int z = i / w;
                for (int j : new int[]{x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, z > 0 ? i - w : -1, z < f.h() - 1 ? i + w : -1}) {
                    if (j < 0 || Math.abs(now[i] - g[j]) <= Reshape.MAX_STEP) {
                        continue;
                    }
                    Reshape.Kind k = f.kinds()[j];
                    if ((k == Reshape.Kind.KEPT || k == Reshape.Kind.BORDER) && kept(col(f.x0() + j % w, f.z0() + j / w)) == Why.BUILDING) {
                        out.put(column(f.x0() + x, f.z0() + z), Why.BUILDING);
                        break;
                    }
                }
            }
            return out;
        }

        @Nullable
        Why span(Col c, int now) {
            int g = c.foot;
            int lo = Math.min(g, now) - 2;
            int hi = Math.max(c.coverTop, now);
            for (int y = lo; y <= hi; y++) {
                if (building(c.x, y, c.z)) {
                    return Why.BUILDING;
                }
            }
            for (int y = lo; y <= g; y++) {
                BlockState s = at(c.x, y, c.z);
                if (!ground(s) || s.hasBlockEntity()) {
                    return s.isAir() ? Why.CAVE : wet(s) ? Why.WATER : log(s) ? Why.TREE : Why.BLOCK;
                }
            }
            for (int k = c.thingY.size() - 1; k >= 0; k--) {
                if (c.thingY.getInt(k) > hi) {
                    break;
                }
                byte t = c.thingType.getByte(k);
                return t == LOG || t == LEAF ? Why.TREE : t == WATER ? Why.WATER : Why.BLOCK;
            }
            if (now < g) {
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    for (int y = now + 1; y <= g; y++) {
                        if (!at(c.x + d.getStepX(), y, c.z + d.getStepZ()).getFluidState().isEmpty()) {
                            return Why.WATER;                    // a spring or a pocket the cut would open
                        }
                    }
                }
            }
            return null;
        }

        /** Too steep to ramp at the widest margin: named at the solve's spot, with the rise it met along its row and column. */
        Refused steep(Reshape.Frame f, int[] g, Reshape.Result r) {
            int sx = Math.max(0, r.spotX());
            int sz = Math.max(0, r.spotZ());
            int rise = 0;
            for (int k = 0; k < f.w() + f.h(); k++) {
                int i = k < f.w() ? sz * f.w() + k : (k - f.w()) * f.w() + sx;
                if (f.kinds()[i].held() && f.kinds()[i] != Reshape.Kind.PAD && Math.abs(g[i] - floor) > Math.abs(rise)) {
                    rise = g[i] - floor;
                }
            }
            int x = f.x0() + sx;
            int z = f.z0() + sz;
            String land = rise >= 0 ? "rises " + rise + " above " : "falls " + (-rise) + " below ";
            return refuse("The land " + land + whose + " floor at " + x + " " + z + ": too steep to ramp within " + Reshape.MARGIN_MAX
                    + " blocks", new BlockPos(x, r.height()[sz * f.w() + sx], z));
        }

        /** Whether a column is on the ground walked: the ramp, the berm and landing, and the land at the border. */
        boolean walked(Reshape.Frame f, int i) {
            Reshape.Kind k = f.kinds()[i];
            int x = f.x0() + i % f.w();
            int z = f.z0() + i / f.w();
            return switch (k) {
                case FREE -> true;
                case PAD -> !inBox(x, z);
                case BORDER -> kept(col(x, z)) == null && !col(x, z).fluid();
                default -> false;
            };
        }

        /**
         * A walk from the landing to the border, stepping at most 1 and never onto a kept column or a pond, or the site is
         * refused. Where the ramp meets steep land with a bank ({@code seamed}), this is what decides: the ramp walks in itself,
         * and a site with no walk out over it is too steep, named at the ramp's edge with the smallest step out to the land.
         * Where the ramp meets the land level all round, only what it goes round can shut the walk in.
         */
        void walk(Reshape.Frame f, int[] a, boolean seamed) {
            int w = f.w();
            boolean[] seen = new boolean[a.length];
            ArrayDeque<Integer> q = new ArrayDeque<>();
            for (int i = 0; i < a.length; i++) {
                if (in(landing, f.x0() + i % w, f.z0() + i / w)) {
                    seen[i] = true;
                    q.add(i);
                }
            }
            while (!q.isEmpty()) {
                int i = q.poll();
                int x = i % w;
                int z = i / w;
                for (int j : new int[]{x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, z > 0 ? i - w : -1, z < f.h() - 1 ? i + w : -1}) {
                    if (j < 0 || seen[j] || !walked(f, j) || Math.abs(a[i] - a[j]) > Reshape.MAX_STEP) {
                        continue;
                    }
                    if (f.kinds()[j] == Reshape.Kind.BORDER) {
                        return;
                    }
                    seen[j] = true;
                    q.add(j);
                }
            }
            if (seamed) {
                // the walked column at the ramp's edge with the smallest step out to the land (the first found, row by row)
                int best = -1;
                int bestStep = Integer.MAX_VALUE;
                int land = floor;
                for (int i = 0; i < a.length; i++) {
                    if (!seen[i]) {
                        continue;
                    }
                    int x = i % w;
                    int z = i / w;
                    for (int j : new int[]{x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, z > 0 ? i - w : -1, z < f.h() - 1 ? i + w : -1}) {
                        if (j >= 0 && f.kinds()[j] == Reshape.Kind.BORDER && walked(f, j) && Math.abs(a[j] - a[i]) < bestStep) {
                            best = i;
                            bestStep = Math.abs(a[j] - a[i]);
                            land = a[j];
                        }
                    }
                }
                if (best >= 0) {
                    int x = f.x0() + best % w;
                    int z = f.z0() + best / w;
                    int rise = land - floor;
                    String how = rise >= 0 ? "rises " + rise + " above " : "falls " + (-rise) + " below ";
                    throw refuse("The land " + how + whose + " floor at " + x + " " + z + ": too steep to ramp out of within "
                            + Reshape.MARGIN_MAX + " blocks", new BlockPos(x, a[best], z));
                }
            }
            BlockPos out = new BlockPos(pl.door().getX() + 2 * outward(pl)[0], floor + 1, pl.door().getZ() + 2 * outward(pl)[1]);
            throw refuse("No walk leads from " + whose + " door out to the land from " + out.getX() + " " + out.getZ()
                    + ": what the ramp goes round shuts it in", out);
        }

        // --- the answer ----------------------------------------------------------------------------------------------

        Plan finish(Reshape.Frame f, int[] g, int[] now, int margin, long[] trees, Map<Long, Why> extra, boolean reshaped, boolean seamed) {
            int n = g.length;
            int w = f.w();
            byte[] kinds = new byte[n];
            int[] top = new int[n];
            Look[] own = new Look[n];
            Map<Look, Integer> index = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                int x = f.x0() + i % w;
                int z = f.z0() + i / w;
                Reshape.Kind k = f.kinds()[i];
                kinds[i] = (byte) k.ordinal();
                Col c = col(x, z);
                boolean worked = k == Reshape.Kind.PAD || k == Reshape.Kind.FREE;
                top[i] = worked ? Math.max(g[i], c.coverTop) : g[i];
                if (worked) {
                    for (int v : new int[]{now[i], g[i], top[i], Math.max(g[i], top[i])}) {
                        if (Math.abs(v - floor) > OFFSET_MAX) {
                            throw refuse("The land " + (v > floor ? "rises " : "falls ") + Math.abs(v - floor) + (v > floor ? " above " : " below ") + whose
                                    + " floor at " + x + " " + z + ": too far to level", new BlockPos(x, v, z));
                        }
                    }
                }
                if (worked && !(k == Reshape.Kind.PAD && inBox(x, z))) {
                    Look l = sample(c);
                    if (l != null && (index.containsKey(l) || index.size() < LOOKS_MAX)) {
                        index.putIfAbsent(l, index.size());
                        own[i] = l;
                    }
                }
            }
            // a column with no look of its own borrows its nearest neighbour's (breadth first, so the same one every time)
            Look[] use = own.clone();
            boolean[] borrowed = new boolean[n];
            ArrayDeque<Integer> q = new ArrayDeque<>();
            for (int i = 0; i < n; i++) {
                if (own[i] != null) {
                    q.add(i);
                }
            }
            while (!q.isEmpty()) {
                int i = q.poll();
                int x = i % w;
                int z = i / w;
                for (int j : new int[]{x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, z > 0 ? i - w : -1, z < f.h() - 1 ? i + w : -1}) {
                    if (j >= 0 && use[j] == null) {
                        use[j] = use[i];
                        borrowed[j] = true;
                        q.add(j);
                    }
                }
            }
            byte[] look = new byte[n];
            for (int i = 0; i < n; i++) {
                Reshape.Kind k = f.kinds()[i];
                if (k != Reshape.Kind.PAD && k != Reshape.Kind.FREE) {
                    continue;
                }
                Look l = use[i];
                if (l == null) {
                    l = PLAIN;                                   // nothing in the area to take a look from
                    borrowed[i] = true;
                    index.putIfAbsent(l, index.size());
                }
                int v = index.get(l) + 1;
                look[i] = (byte) (borrowed[i] ? -v : v);
            }
            byte[] sealLook = new byte[seals.size()];
            for (int k = 0; k < sealLook.length; k++) {
                Look l = sealLooks.get(k);
                if (!index.containsKey(l) && index.size() >= LOOKS_MAX) {
                    l = PLAIN;
                }
                index.putIfAbsent(l, index.size());
                sealLook[k] = (byte) (index.get(l) + 1);
            }
            List<Look> looks = new ArrayList<>(index.keySet());
            // the numbers
            int cutColumns = 0;
            int fillColumns = 0;
            int cutBlocks = 0;
            int fillBlocks = 0;
            int kept = 0;
            int steepest = 0;
            int bank = 0;
            for (int i = 0; i < n; i++) {
                Reshape.Kind k = f.kinds()[i];
                if (k == Reshape.Kind.KEPT) {
                    kept++;
                }
                if (k == Reshape.Kind.PAD || k == Reshape.Kind.FREE) {
                    int d = now[i] - g[i];
                    cutColumns += d < 0 ? 1 : 0;
                    fillColumns += d > 0 ? 1 : 0;
                    cutBlocks += Math.max(0, -d);
                    fillBlocks += Math.max(0, d);
                }
                int x = i % w;
                int z = i / w;
                for (int j : new int[]{x < w - 1 ? i + 1 : -1, z < f.h() - 1 ? i + w : -1}) {
                    if (j >= 0 && (k == Reshape.Kind.FREE || f.kinds()[j] == Reshape.Kind.FREE) && walked(f, i) && walked(f, j)) {
                        int d = Math.abs(now[i] - now[j]);
                        if (seamed && (k == Reshape.Kind.BORDER || f.kinds()[j] == Reshape.Kind.BORDER)) {
                            bank = Math.max(bank, d);            // the ramp meeting steep land: a bank, not a step on it
                        } else {
                            steepest = Math.max(steepest, d);
                        }
                    }
                }
            }
            Numbers numbers = new Numbers(margin, cutColumns, fillColumns, cutBlocks, fillBlocks, kept, steepest, bank, seamed);
            return new Plan(pl, template, f.x0(), f.z0(), w, f.h(), floor, reshaped, kinds, now.clone(), g.clone(), top, look, looks, trees,
                    seals.toLongArray(), sealLook, numbers, goesRound(f, extra));
        }

        /** A column's own look, or null: under water, or its top is no plain ground (an ore, a player's block). */
        @Nullable
        Look sample(Col c) {
            if (c.fluid()) {
                return null;
            }
            BlockState top = at(c.x, c.foot, c.z);
            if (!lookable(top)) {
                return null;
            }
            BlockState soil = null;
            for (int d = 1; d <= 3 && soil == null; d++) {
                BlockState s = at(c.x, c.foot - d, c.z);
                if (lookable(s)) {
                    soil = soilOf(s);
                }
            }
            if (soil == null) {
                soil = soilOf(top);
            }
            BlockState deep = null;
            for (int d = 4; d <= 8 && deep == null; d++) {
                BlockState s = at(c.x, c.foot - d, c.z);
                if (lookable(s)) {
                    deep = soilOf(s);
                }
            }
            return new Look(unsnowed(top), soil, deep == null ? soil : deep);
        }

        /** What the ramp goes round, in words: the player's blocks by column, trees, buildings, caves and ponds by the clump. */
        String goesRound(Reshape.Frame f, Map<Long, Why> extra) {
            int w = f.w();
            int n = w * f.h();
            Why[] why = new Why[n];
            for (int i = 0; i < n; i++) {
                int x = f.x0() + i % w;
                int z = f.z0() + i / w;
                Reshape.Kind k = f.kinds()[i];
                if (k == Reshape.Kind.FLUID && !(i % w == 0 || i / w == 0 || i % w == w - 1 || i / w == f.h() - 1)) {
                    why[i] = Why.WATER;
                } else if (k == Reshape.Kind.KEPT) {
                    Why y = extra.get(column(x, z));
                    why[i] = y != null ? y : kept(col(x, z));
                }
            }
            int blocks = 0;
            int[] clumps = new int[Why.values().length];
            boolean[] seen = new boolean[n];
            for (int i = 0; i < n; i++) {
                if (why[i] == null || seen[i]) {
                    continue;
                }
                if (why[i] == Why.BLOCK) {
                    blocks++;
                    continue;
                }
                clumps[why[i].ordinal()]++;
                ArrayDeque<Integer> q = new ArrayDeque<>();
                q.add(i);
                seen[i] = true;
                while (!q.isEmpty()) {
                    int c = q.poll();
                    int x = c % w;
                    int z = c / w;
                    for (int j : new int[]{x > 0 ? c - 1 : -1, x < w - 1 ? c + 1 : -1, z > 0 ? c - w : -1, z < f.h() - 1 ? c + w : -1}) {
                        if (j >= 0 && !seen[j] && why[j] == why[i]) {
                            seen[j] = true;
                            q.add(j);
                        }
                    }
                }
            }
            List<String> parts = new ArrayList<>();
            if (blocks > 0) {
                parts.add(blocks == 1 ? "a block of yours" : blocks + " of your blocks");
            }
            String[][] names = {{"a tree", "trees"}, {"another building", "other buildings"}, {"a cave", "caves"}, {"a pond", "ponds"}};
            Why[] order = {Why.TREE, Why.BUILDING, Why.CAVE, Why.WATER};
            for (int k = 0; k < order.length; k++) {
                int c = clumps[order[k].ordinal()];
                if (c > 0) {
                    parts.add(c == 1 ? names[k][0] : c + " " + names[k][1]);
                }
            }
            if (parts.isEmpty()) {
                return "";
            }
            if (parts.size() == 1) {
                return parts.get(0);
            }
            return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
        }
    }
}
