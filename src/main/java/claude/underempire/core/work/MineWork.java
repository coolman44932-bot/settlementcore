package claude.underempire.core.work;

import claude.underempire.core.settlement.Currencies;
import claude.underempire.core.unit.SettlerMob;
import claude.underempire.core.settlement.Settlement;
import claude.underempire.core.settlement.Building;
import claude.underempire.core.room.DigJob;
import claude.underempire.core.room.PlanPlacement;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.common.Tags;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ore Mine (user, 2026-09-25, picked "Both"): the crew strip-mines drifts out through the real rock
 * round the room, and once they are worked out the old faces still give a trickle of ore.
 *
 * <p><b>The drifts.</b> From each drift mouth (the template's "drift" stations) a tunnel 1 wide and 2
 * tall runs straight out {@link #MAIN} blocks, with a branch {@link #BRANCH} long either side every
 * {@link #EVERY} blocks: a Skaven strip-mine, a few thousand blocks of rock between the four of them.
 * A miner gnaws one column at a time. Every ore that shows in a wall, floor or roof it opened is
 * followed along its vein (up to {@link #VEIN_MAX} blocks), as a player branch-mining would. Ore goes to
 * the Ore Cart as an iron pick would get it (warpstone ore straight into the Heart's hoard); cobble and
 * the like to the Stone Bin while it has room.
 *
 * <p>It is a mine, not a quarry: only the ground itself is cut (DigJob#natural), never anything built.
 * A drift stops rather than break into a cave, another room, a fluid it cannot wall off, or a hole in
 * its floor; water and lava beside it are walled up with rubble, and so is loose sand or gravel over
 * it. It waits where the ground is not loaded. A torch goes up every {@link #TORCH_EVERY} blocks.
 *
 * <p><b>The winze</b> (the user, 2026-09-29: "yeah make the winze real"). A room with a "winze" station (its post, the
 * station's facing the side its way in opens on) goes deeper when a level's drifts are done: the crew gnaws a spiral
 * stair down round the post, {@link #LEVEL} steps to a turn, one step to a chore. Its first turn goes down through the
 * room's own pit; every turn after it opens a level with two new drifts off it, on two sides of the shaft (the next level
 * on the other two, so a level's tunnels never cross the one above's). Rats walk the stair (they climb no ladders); so does their god. Water, lava, loose ground and caves against
 * the shaft are walled up with rubble as it goes; it stops at anything it may not cut (anything built, bedrock),
 * another room, or near the bottom of the world ({@link #BOTTOM_MARGIN}). Only the bedrock is for good: any other stop is
 * a pause (asked again every {@link #RECHECK_TICKS}, and by each night), and the winze goes on when its cause has gone.
 * The cave's plants (ferns, flowers, mushrooms, azalea, dripleaf, spore blossom, moss, glow lichen, vines, roots) are ground.
 * The crew walk the stair to their work in legs of {@link #LEG} steps (a path's search reaches no further than a rat's follow
 * range), and stand on the step above the one they cut.
 *
 * <p><b>The trickle.</b> With the room's own drifts done, one of the crew works the old faces: one roll of the depth's ores
 * every {@link #TRICKLE_TICKS} (coal and copper high up, iron in the middle, gold, lapis, redstone and a few diamonds deep
 * down, as they lie in the world), at its deepest level's depth. It runs whatever the winze is at (the winze earns on top of it:
 * before the winze was real a worked-out mine trickled, and it must never earn less).
 */
public class MineWork extends RoomWork {

    public static final String ORE_CART = "Ore Cart", STONE_BIN = "Stone Bin";
    static final int MAIN = 40, EVERY = 4, BRANCH = 12, VEIN_MAX = 32, TORCH_EVERY = 10;
    static final int TRICKLE_TICKS = 1200;
    /** Steps of the winze's stair to a turn, and so blocks to a level. */
    public static final int LEVEL = 8;
    /** The winze stops this far above the bottom of the world (the bedrock). */
    static final int BOTTOM_MARGIN = 6;
    /** How often a paused winze asks again whether the way is clear (a dozen cells and their neighbours: cheap). */
    static final int RECHECK_TICKS = 600;
    /** Steps of the stair a leg of the crew's walk covers at most: a path's search reaches only the rat's follow range (20). */
    static final int LEG = 6;
    private static final ItemStack PICK = new ItemStack(Items.IRON_PICKAXE);

    /** One drift: its cells (the feet cell of each 1 x 2 column) in the order they are gnawed. */
    final class Drift {
        final BlockPos mouth;
        final Direction facing;
        final List<BlockPos> cells = new ArrayList<>();
        final List<Integer> segment = new ArrayList<>();      // 0 the main drift, then each branch
        int next;
        boolean done;
        final Deque<BlockPos> vein = new ArrayDeque<>();
        final Set<Long> queued = new HashSet<>();
        /** Ore cells gnawed out along veins (not saved: only the cave check asks, and only while a vein is followed). */
        final Set<Long> veinDug = new HashSet<>();
        final BoundingBox extent;
        /** The mine's level it runs from: 0 the room's own, then each turn of the winze. */
        final int level;

        Drift(BlockPos mouth, Direction facing) {
            this(mouth, facing, 0);
        }

        Drift(BlockPos mouth, Direction facing, int level) {
            this.mouth = mouth;
            this.facing = facing;
            this.level = level;
            Direction left = facing.getCounterClockWise(), right = facing.getClockWise();
            int seg = 0;
            for (int i = 1; i <= MAIN; i++) {
                BlockPos m = mouth.relative(facing, i);
                cells.add(m);
                segment.add(0);
                if (i % EVERY == 0) {
                    for (Direction side : new Direction[]{left, right}) {
                        seg++;
                        for (int j = 1; j <= BRANCH; j++) {
                            cells.add(m.relative(side, j));
                            segment.add(seg);
                        }
                    }
                }
            }
            int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (BlockPos c : cells) {
                x0 = Math.min(x0, c.getX());
                z0 = Math.min(z0, c.getZ());
                x1 = Math.max(x1, c.getX());
                z1 = Math.max(z1, c.getZ());
            }
            extent = new BoundingBox(x0, mouth.getY(), z0, x1, mouth.getY() + 1, z1);
        }

        /** The cells this drift has open already (both heights of every column gnawed so far, and the mouth). */
        boolean ours(BlockPos p) {
            if (p.equals(mouth) || p.equals(mouth.above()) || veinDug.contains(p.asLong())) {
                return true;
            }
            for (int i = 0; i < next && i < cells.size(); i++) {
                BlockPos c = cells.get(i);
                if (c.equals(p) || c.above().equals(p)) {
                    return true;
                }
            }
            return false;
        }

        /** Skip what is left of the current branch (or, on the main drift, the whole drift). */
        void blockHere() {
            int seg = segment.get(next);
            if (seg == 0) {
                done = true;
                return;
            }
            while (next < cells.size() && segment.get(next) == seg) {
                next++;
            }
            if (next >= cells.size()) {
                done = true;
            }
        }
    }

    final List<Drift> drifts = new ArrayList<>();
    /** How often each vein cell has been handed out: one no rat can get at is given up (not saved). */
    private final java.util.Map<Long, Integer> veinTries = new java.util.HashMap<>();
    List<BlockPos> carts = List.of();
    List<BlockPos> stone = List.of();
    List<BoundingBox> others = List.of();
    long found;
    long gnawed;
    long nextTrickle;
    /** The winze's post at the room's floor (its "winze" station), and the side its way in opens on; null: no winze. */
    @Nullable final BlockPos winze;
    final Direction gateSide;
    /** Steps of the winze's stair gnawed so far; whether it can go no deeper. */
    int steps;
    boolean winzeStopped;
    /** Whether that is for good (the bedrock): any other stop is a pause, asked again. */
    boolean winzeFinal;
    /** When a paused winze asks again (not saved: a loaded one asks at once). */
    long winzeCheckAt;
    /** Why it can go no deeper, as its god is told ("at the bedrock", "at oak planks at 12 40 -7"). */
    String winzeStop = "";
    /** The rat handed the winze's chore last, and when it was handed it or finished a step: the winze is one rat's work at a time. Not saved. */
    @Nullable private java.util.UUID sinker;
    private long sinkerAt;
    /** How long a rat that was handed the winze keeps it against the crew's other rats while it is in the mine. */
    static final int SINKER_TICKS = 600;

    public MineWork(Settlement heart, Building building, PlanPlacement placement) {
        super(heart, building, placement);
        for (Spot s : stations("drift")) {
            drifts.add(new Drift(s.pos(), s.facing()));
        }
        List<Spot> w = stations("winze");
        winze = w.isEmpty() ? null : w.get(0).pos();
        gateSide = w.isEmpty() || !w.get(0).facing().getAxis().isHorizontal() ? Direction.NORTH : w.get(0).facing();
    }

    @Override
    protected void scan(ServerLevel level) {
        carts = Bins.named(level, box(), ORE_CART);
        stone = Bins.named(level, box(), STONE_BIN);
        List<BoundingBox> o = new ArrayList<>();
        for (BoundingBox b : heart.settlementBoxes()) {
            if (!b.equals(box())) {
                o.add(b);
            }
        }
        others = o;
    }

    public List<BlockPos> carts(ServerLevel level) {
        rescan(level);
        return carts;
    }

    public List<BlockPos> stoneBins(ServerLevel level) {
        rescan(level);
        return stone;
    }

    /** Every drift's mouth: the room's own, then each level's (tests, the report). */
    public List<BlockPos> driftMouths() {
        return drifts.stream().map(d -> d.mouth).toList();
    }

    /** The drift mouths, where they are in the world and which way each drift runs. */
    public List<Spot> mouths() {
        return stations("drift");
    }

    /** The report line, as text, and each drift's state (tests). */
    public String describeLine(ServerLevel level) {
        List<Component> l = new ArrayList<>();
        describe(level, l);
        StringBuilder sb = new StringBuilder(l.isEmpty() ? "" : l.get(0).getString());
        sb.append("; gnawed ").append(gnawed);
        for (Drift d : drifts) {
            sb.append(" [").append(d.facing).append(" at ").append(d.next).append(d.done ? " done" : "")
                    .append(d.next < d.cells.size() ? " next " + d.cells.get(d.next) + " " + check(level, d) : "").append("]");
        }
        return sb.toString();
    }

    public boolean workedOut() {
        return driftsDone() && (winze == null || winzeStopped);
    }

    private boolean driftsDone() {
        return drifts.stream().allMatch(d -> d.done && d.vein.isEmpty());
    }

    /** Levels the winze has opened below the room's own (its first turn is the room's pit: no level of its own). */
    public int levels() {
        return Math.max(0, steps / LEVEL - 1);
    }

    /** Steps of the winze from the last level opened (or the room) to the next; how many of them are gnawed. */
    private int[] toNextLevel() {
        return steps < 2 * LEVEL ? new int[]{2 * LEVEL, steps} : new int[]{LEVEL, steps % LEVEL};
    }

    /** The winze's steps gnawed so far, and whether it can go no deeper (tests, the report). */
    public int winzeSteps() {
        return steps;
    }

    public boolean winzeStopped() {
        return winzeStopped;
    }

    @Nullable
    public BlockPos winzePost() {
        return winze;
    }

    /** Where step {@code s} of the winze's stair is stood on (its feet cell). */
    public BlockPos winzeStep(int s) {
        return ring(s).atY(winze.getY() - 1 - s);
    }

    /** The ground its drifts reach: the warren's too (no monsters spawn in them, rats heal in them). */
    public List<BoundingBox> reach() {
        List<BoundingBox> out = new ArrayList<>();
        for (Drift d : drifts) {
            if (d.next > 0) {
                out.add(d.extent);
            }
        }
        if (winze != null && steps > 0) {
            // the shaft too: a crew rat down the winze is at its work, not strayed (Works#second), and no monster spawns in it
            out.add(new BoundingBox(winze.getX() - 2, winze.getY() - 3 - steps, winze.getZ() - 2,
                    winze.getX() + 2, winze.getY(), winze.getZ() + 2));
        }
        return out;
    }

    @Override
    public boolean reachInside(BlockPos p) {
        for (BoundingBox b : reach()) {
            if (b.inflatedBy(2).isInside(p)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int crewWanted() {
        long open = drifts.stream().filter(d -> !d.done || !d.vein.isEmpty()).count();
        return (int) Math.max(1, Math.min(4, open));
    }

    @Override
    public void second(ServerLevel level, int crewHere) {
        rescan(level);
        resume(level, false);
    }

    @Override
    @Nullable
    public Chore nextChore(ServerLevel level, SettlerMob rat) {
        rescan(level);
        long now = level.getGameTime();
        // a vein showing in a wall first
        for (Drift d : drifts) {
            BlockPos v = d.vein.peekFirst();
            if (v == null || !free(d, now)) {
                continue;
            }
            if (veinTries.merge(v.asLong(), 1, Integer::sum) > 3) {
                d.vein.pollFirst();                              // handed out three times and never reached: left in the rock
                veinTries.remove(v.asLong());
                continue;
            }
            return routed(rat, new Chore("following a vein", v, 3.5, workTicks(rat, 16), d, (l, r) -> {
                BlockPos cell = d.vein.pollFirst();
                if (cell != null) {
                    veinTries.remove(cell.asLong());
                    followVein(l, d, cell);
                }
                return null;
            }), null);
        }
        // the old faces: the trickle keeps its clock whatever the winze is at, so the mine never earns less than it did
        // before the winze (a worked-out mine trickled); the room's own drifts are the faces the crew go to
        if (trickles() && now >= nextTrickle && free("faces", now)) {
            BlockPos end;
            if (winze != null && steps > 0 && depth(rat.getBlockY()) >= 2) {
                end = rat.blockPosition();                       // a rat down the stair works the faces where it is: not a climb to the room and back
            } else {
                List<Drift> faces = drifts.stream().filter(x -> x.level == 0).toList();
                Drift d = faces.get(level.random.nextInt(faces.size()));
                end = d.next > 0 ? d.cells.get(Math.min(d.next, d.cells.size()) - 1) : d.mouth;
            }
            return routed(rat, new Chore("working the old faces", end, 2.4, workTicks(rat, 60), "faces", (l, r) -> {
                trickle(l);
                nextTrickle = l.getGameTime() + TRICKLE_TICKS;
                return null;
            }), null);
        }
        // then the drift with the least gnawed. One decided on the spot (its end is found: blocked or skipped) does not cost the rat its ask:
        // it looks again, so the winze's chore that the drift's end has just made free is the asking rat's, and not the first rat's who asks in
        // the 30 to 60 ticks it would otherwise wait
        for (int looks = 0; looks < 64; looks++) {
            Drift best = null;
            for (Drift d : drifts) {
                if (!d.done && free(d, now) && (best == null || d.next < best.next)) {
                    best = d;
                }
            }
            if (best == null) {
                break;
            }
            Drift d = best;
            Verdict v = check(level, d);
            if (v == Verdict.WAIT) {
                return null;
            }
            if (v != Verdict.CUT) {
                continue;
            }
            BlockPos c = d.cells.get(d.next);
            return routed(rat, new Chore("gnawing a drift", c, 2.2, workTicks(rat, 24), d, (l, r) -> {
                if (check(l, d) == Verdict.CUT) {
                    digColumn(l, d);
                }
                return null;
            }), null);
        }
        // and the winze, once every drift is done: one rat's work at a time
        if (winze != null && !winzeStopped && driftsDone() && free("winze", now) && sinkerAllows(level, rat, now)) {
            if (winzeCheck(level, steps) == Verdict.CUT) {
                int s = steps;
                sinker = rat.getUUID();
                sinkerAt = now;
                // from the step above it: the stair's own cells are no sturdy floor, so CrewGoal#approach finds no cell to stand in
                // for a step in the rock, and vanilla lifts the rock cell to the first air above it, a turn up the spiral (a rat out of
                // reach of the step, moved off by a push or its own stride, stood there for 400 ticks: the crew test's traces, 2026-09-30)
                BlockPos stand = s == 0 ? null : winzeStep(s - 1);
                Chore sink = new Chore("sinking the winze", winzeStep(s), 3.0, workTicks(rat, 30), "winze", (l, r) -> {
                    if (steps == s && winzeCheck(l, s) == Verdict.CUT) {
                        sinkStep(l);
                    }
                    sinker = r.getUUID();
                    sinkerAt = l.getGameTime();
                    return null;
                }).standing(stand);
                return routed(rat, sink, stand);
            }
        }
        return null;
    }

    /**
     * Whether {@code rat} may be handed the winze's chore: not while another rat that was handed it lately is still in the mine. CrewGoal lets
     * go a chore's claim between two steps and the crew ask every 30 to 60 ticks, so a rat up in the room that asked in those few ticks took the
     * winze from the rat on the stair, walked down to it, and the rat on the stair stood idle (the crew test's traces, 2026-09-30).
     */
    private boolean sinkerAllows(ServerLevel level, SettlerMob rat, long now) {
        if (sinker == null || sinker.equals(rat.getUUID())) {
            return true;
        }
        if (now - sinkerAt <= SINKER_TICKS && level.getEntity(sinker) instanceof SettlerMob other && other.isAlive()
                && reachInside(other.blockPosition())) {
            return false;
        }
        sinker = null;
        return true;
    }

    /** Whether the old faces trickle: the room's own drifts (level 0) done, as a worked-out mine was before the winze. */
    private boolean trickles() {
        boolean any = false;
        for (Drift d : drifts) {
            if (d.level == 0) {
                any = true;
                if (!d.done || !d.vein.isEmpty()) {
                    return false;
                }
            }
        }
        return any;
    }

    // ------------------------------------------------------------------ getting to the work

    /** How many steps down the stair the height {@code y} is: -1 on the room's floor, 0 the first step's feet cell. */
    private int depth(int y) {
        return winze.getY() - 1 - y;
    }

    /** Whether the rat is where CrewGoal counts a chore in reach (so it is not sent walking first). */
    private static boolean inReach(SettlerMob rat, Chore c) {
        double dx = rat.getX() - (c.at.getX() + 0.5), dz = rat.getZ() - (c.at.getZ() + 0.5);
        int dy = c.at.getY() - rat.getBlockY();
        return dx * dx + dz * dz <= c.reach * c.reach && dy <= 3 && dy >= -3;
    }

    /**
     * {@code real}, once the rat is where it can be done from. A rat far up or down the winze from its chore walks the stair in
     * legs of {@link #LEG} steps: one path's search reaches only the rat's follow range (20), and what it finds beyond is the
     * cell nearest the target as the crow flies, which for a face far out on a deep level is the room's far wall, not the stair.
     * A leg is a chore of its own (the claim kept), so each has its own patience, and the next is made from where the rat has
     * got to. {@code stand}: the cell the work is done from, where CrewGoal#approach cannot find one (a step in the rock).
     */
    private Chore routed(SettlerMob rat, Chore real, @Nullable BlockPos stand) {
        if (winze == null || steps == 0 || inReach(rat, real)) {
            return real;
        }
        BlockPos me = rat.blockPosition();
        BlockPos goal = stand != null ? stand : real.at;
        int ri = depth(me.getY()), gi = depth(goal.getY());
        BlockPos wp;
        if (Math.abs(ri - gi) > 2) {
            // On the room's floor the way in is the pit's mouth, its gate, perhaps on the far side of the fence from the rat. A leg to a step
            // 6 below from the floor beside the fence sent a search that spent its node budget on the open floor before it found the gate
            // and came back with the best node it had reached, the rat's own cell, a path of one node: the rat stood 400 ticks (the crew
            // test's traces, 2026-09-30). So a rat on the floor goes to the mouth first, a target the search runs straight to, and from
            // the mouth down the stair as before. (To the stair's head instead, the arrival test, 1.1 across and 3 up, counted the gate
            // as the head, and the rat asked for the deep chore from there, where the search ends at the room's far wall.)
            BlockPos mouth = winze.relative(gateSide, 2);
            double mx = rat.getX() - (mouth.getX() + 0.5), mz = rat.getZ() - (mouth.getZ() + 0.5);
            if (ri < 0 && mx * mx + mz * mz > 1.5 * 1.5) {
                wp = mouth;
            } else {
                boolean near = Math.abs(me.getX() - winze.getX()) <= 3 && Math.abs(me.getZ() - winze.getZ()) <= 3;
                int t = near ? ri + Mth.clamp(gi - ri, -LEG, LEG) : ri;     // far from the shaft: to its stair at the depth it is at
                wp = winzeStep(Mth.clamp(t, 0, steps - 1));
            }
        } else if (stand != null) {
            wp = stand;
        } else {
            return real;                                         // about its depth: the room's own approach walks it (a long tunnel is walked in partial paths)
        }
        double dx = rat.getX() - (wp.getX() + 0.5), dz = rat.getZ() - (wp.getZ() + 0.5);
        if (dx * dx + dz * dz <= 1.1 * 1.1 && Math.abs(wp.getY() - me.getY()) <= 3) {
            return real;                                         // it is there already
        }
        String what = wp.getY() < me.getY() ? "going down the winze" : wp.getY() > me.getY() ? "coming up the winze" : "to the winze";
        return new Chore(what, wp, 1.1, 1, real.claim, (l, r) -> routed(r, real, stand));
    }

    // ------------------------------------------------------------------ the winze

    /** Cell {@code i} of the winze's stair round its post (at the post's height): clockwise from the way in, sides and corners. */
    private BlockPos ring(int i) {
        Direction side = gateSide;
        for (int k = 0; k < (i % 8) / 2; k++) {
            side = side.getClockWise();
        }
        BlockPos c = winze.relative(side);
        return i % 2 == 0 ? c : c.relative(side.getClockWise());
    }

    /** Whether {@code p} is in the winze's shaft (the 3 x 3 of the post and its stair). */
    private boolean inShaft(BlockPos p) {
        return Math.abs(p.getX() - winze.getX()) <= 1 && Math.abs(p.getZ() - winze.getZ()) <= 1;
    }

    /** Step {@code s}: the cells gnawed out for it (its column, from its feet up to the step over it), its floor, and the post beside it. */
    private List<BlockPos> stepCells(int s) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos feet = winzeStep(s);
        int top = Math.min(winze.getY() - 1, feet.getY() + LEVEL - 2);
        for (int y = feet.getY(); y <= top; y++) {
            out.add(feet.atY(y));
        }
        out.add(feet.below());
        out.add(winze.atY(feet.getY()));
        out.add(winze.atY(feet.getY() - 1));
        return out;
    }

    /**
     * The winze can go no deeper now: for good at the bedrock, and otherwise for a while (a player's block in the way, another
     * room beside the shaft): {@link #resume} asks again every {@link #RECHECK_TICKS}, and each night.
     */
    private Verdict stop(ServerLevel level, String why, boolean forGood) {
        boolean changed = !why.equals(winzeStop) || forGood && !winzeFinal;
        winzeStopped = true;
        winzeStop = why;
        winzeFinal |= forGood;
        winzeCheckAt = level.getGameTime() + RECHECK_TICKS;
        if (changed) {
            heart.setChanged();
        }
        return Verdict.BLOCKED;
    }

    /** A paused winze looks again whether its way is clear (at once with {@code now}, else when it is time), and goes on if so. */
    private void resume(ServerLevel level, boolean now) {
        if (winze == null || !winzeStopped || winzeFinal || !now && level.getGameTime() < winzeCheckAt) {
            return;
        }
        winzeStopped = false;
        winzeCheck(level, steps);                                // stops it again (the same cause, or another) or lets it go on
        if (!winzeStopped) {
            winzeStop = "";
            heart.setChanged();
        }
    }

    private static String at(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    /** Whether step {@code s} of the winze can be gnawed: CUT, WAIT (the ground not loaded) or BLOCKED (no deeper: for good, or for now). */
    Verdict winzeCheck(ServerLevel level, int s) {
        if (winze == null || winzeStopped) {
            return Verdict.BLOCKED;
        }
        BlockPos feet = winzeStep(s);
        if (feet.getY() - 2 <= level.getMinBuildHeight() + BOTTOM_MARGIN) {
            return stop(level, "at the bedrock", true);
        }
        for (int dx = -3; dx <= 3; dx += 6) {                    // what it walls and opens mouths in reaches 3 out from the post
            for (int dz = -3; dz <= 3; dz += 6) {
                if (!level.isLoaded(new BlockPos(winze.getX() + dx, feet.getY(), winze.getZ() + dz))) {
                    return Verdict.WAIT;
                }
            }
        }
        for (BlockPos p : stepCells(s)) {
            if (!level.isLoaded(p)) {
                return Verdict.WAIT;
            }
            if (inOtherRoom(p) && !box().isInside(p)) {
                return stop(level, "at another room of the warren, at " + at(p), false);
            }
            BlockState st = level.getBlockState(p);
            if (!box().isInside(p) && !gives(level, p, st)) {    // (in the room's own pit, its own furnishing: a lamp, the winch)
                return stop(level, "at " + st.getBlock().getName().getString().toLowerCase(java.util.Locale.ROOT) + " at " + at(p), false);   // anything built, or bedrock: never cut
            }
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(dir);
                if (!inShaft(n) && inOtherRoom(n) && !box().isInside(n)) {
                    return stop(level, "beside another room of the warren, at " + at(n), false);   // the winze keeps out of it
                }
            }
        }
        return Verdict.CUT;
    }

    /**
     * Ground the winze gnaws: natural ground, the cave's plants, fluids and air, and its own work (the post, the rubble). A
     * waterlogged block is judged as the dry block it is: a player's slab in the water is still a slab.
     */
    private boolean gives(ServerLevel level, BlockPos p, BlockState s) {
        if (s.isAir() || s.getBlock() instanceof LiquidBlock) {
            return true;
        }
        if (s.hasProperty(BlockStateProperties.WATERLOGGED) && s.getValue(BlockStateProperties.WATERLOGGED)) {
            s = s.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        return s.getDestroySpeed(level, p) >= 0 && (ground(s) || ours(p, s));
    }

    /**
     * The winze's own work where it stands: the post (a stripped spruce log in its column) and the rubble walled round the shaft
     * (cobblestone against the ring's sides and corners). Its stairs and torches are in the ring's cells, which no later step's
     * column reaches; anything else of the kinds it uses, a player's ladder, chain, cauldron, stair, torch or log, is theirs (the
     * pit's own furnishing is inside the room's box, which is not asked).
     */
    private boolean ours(BlockPos p, BlockState s) {
        int dx = Math.abs(p.getX() - winze.getX()), dz = Math.abs(p.getZ() - winze.getZ());
        if (dx == 0 && dz == 0) {
            return s.is(Blocks.STRIPPED_SPRUCE_LOG);
        }
        return Math.max(dx, dz) == 2 && Math.min(dx, dz) <= 1 && (s.is(Blocks.COBBLESTONE) || s.is(Blocks.COBBLED_DEEPSLATE));
    }

    /** The ground a mine gnaws through: the rock and what grows or lies on it (DigJob#natural), and the cave's plants. */
    private static boolean ground(BlockState s) {
        return DigJob.natural(s) || plant(s);
    }

    /**
     * The soft plants of a cave, which are no barrier and no building: the lush caves' whole garden (the user's mine, over one,
     * stopped for good at its short grass, 2026-09-29). Moss, clay and rooted dirt are ground already (DigJob#natural).
     */
    static boolean plant(BlockState s) {
        return DigJob.plant(s);     // the engine's cover (moved to DigJob for the raised buildings' earthworks)
    }

    /**
     * One step of the winze gnawed: its column cleared to the step over it, its stair laid, the post carried down beside it,
     * and whatever would run or fall into the shaft (water, lava, loose ground) or open onto it (a cave) walled up with
     * rubble. The first step fits the room's old pit out for it (its ladder, chain and bucket away, its gate opened); the
     * last step of a turn opens the new level's drifts.
     */
    void sinkStep(ServerLevel level) {
        if (steps == 0) {
            fitOutPit(level);
        }
        int s = steps;
        BlockPos feet = winzeStep(s);
        BlockState lining = (feet.getY() < 0 ? Blocks.COBBLED_DEEPSLATE : Blocks.COBBLESTONE).defaultBlockState();
        List<BlockPos> cells = stepCells(s);
        for (BlockPos p : cells.subList(0, cells.size() - 3)) {
            cut(level, p, p.equals(feet));
        }
        cut(level, feet.below(), false);                         // its floor cell: an ore there is found, not lost under the stair
        Direction up = s == 0 ? gateSide : direction(ring(s), ring(s - 1));
        level.setBlock(feet.below(), (feet.getY() < 0 ? Blocks.COBBLED_DEEPSLATE_STAIRS : Blocks.COBBLESTONE_STAIRS).defaultBlockState()
                .setValue(net.minecraft.world.level.block.StairBlock.FACING, up), Block.UPDATE_ALL);
        for (BlockPos p : new BlockPos[]{winze.atY(feet.getY()), winze.atY(feet.getY() - 1)}) {
            if (!level.getBlockState(p).is(Blocks.STRIPPED_SPRUCE_LOG)) {
                cut(level, p, false);
                level.setBlock(p, Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        for (BlockPos p : cells.subList(0, cells.size() - 2)) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(dir);
                if (inShaft(n) || isMouth(n) || !level.isLoaded(n)) {
                    continue;
                }
                BlockState ns = level.getBlockState(n);
                if (ns.isAir() || !ns.getFluidState().isEmpty() || ns.getBlock() instanceof FallingBlock || plant(ns)) {
                    claude.underempire.core.room.Clearance.before(level, n, lining);   // nobody standing there is walled in
                    level.setBlock(n, lining, Block.UPDATE_CLIENTS);
                }
            }
        }
        if (s % LEVEL == LEVEL / 2) {
            torchOnPost(level, s);
        }
        steps++;
        if (steps % LEVEL == 0 && steps / LEVEL - 1 >= 1) {
            openLevel(level, steps / LEVEL - 1, true);           // a turn gnawed below the pit: a new level
        }
        heart.setChanged();
    }

    private static Direction direction(BlockPos from, BlockPos to) {
        return Direction.getNearest(to.getX() - from.getX(), 0, to.getZ() - from.getZ());
    }

    /** The room's old pit made ready: what stood in it for show (a ladder, the winch's chain and bucket) away, its gate open. */
    private void fitOutPit(ServerLevel level) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 1; dy <= 5; dy++) {
                    BlockPos p = winze.offset(dx, -dy, dz);
                    BlockState st = level.getBlockState(p);
                    if (!st.isAir() && (st.is(Blocks.LADDER) || st.is(Blocks.CHAIN) || st.is(Blocks.CAULDRON))) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
        BlockPos gate = winze.relative(gateSide, 2);
        BlockState g = level.getBlockState(gate);
        if (g.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock) {
            level.setBlock(gate, g.setValue(net.minecraft.world.level.block.FenceGateBlock.OPEN, true), Block.UPDATE_ALL);
        }
    }

    /** A torch on the post over step {@code s}, lighting the stair. */
    private void torchOnPost(ServerLevel level, int s) {
        BlockPos at = winzeStep(s).above(2);
        Direction out = direction(winze, ring(s));
        if (!out.getAxis().isHorizontal() || s % 2 != 0) {
            return;                                              // (a corner cell: no face of the post to hang it on)
        }
        BlockState t = Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, out);
        if (level.getBlockState(at).isAir() && t.canSurvive(level, at)) {
            level.setBlock(at, t, Block.UPDATE_ALL);
        }
    }

    /** Whether {@code p} is the mouth (or the head over it) of one of the mine's drifts. */
    private boolean isMouth(BlockPos p) {
        for (Drift d : drifts) {
            if (d.mouth.equals(p) || d.mouth.above().equals(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Level {@code turn}'s drifts (turn 1 on: turn 0 is the pit), off the turn's stair: from its side cells on two sides of
     * the shaft (the way in's side and the far one on even turns, the other two on odd), each through the shaft's wall at
     * that step's height. With
     * {@code dig}, their mouths are gnawed (a mouth that would open onto what it may not, its drift is done at once and the
     * wall stands); without, only the drifts are set out again (a load).
     */
    private void openLevel(ServerLevel level, int turn, boolean dig) {
        int[] sides = turn % 2 == 0 ? new int[]{0, 4} : new int[]{2, 6};
        for (int i : sides) {
            int s = turn * LEVEL + i;
            BlockPos cell = winzeStep(s);
            Direction out = direction(winze, ring(i));
            BlockPos mouth = cell.relative(out);
            Drift d = new Drift(mouth, out, turn);
            if (dig && !openMouth(level, d)) {
                d.done = true;
            }
            drifts.add(d);
        }
    }

    /**
     * Cut a level's mouth (its two cells) through the shaft's wall, as any drift's column is cut: only into rock (the drift's first
     * column must be cuttable and not open onto a cave, water or lava: {@link #check}), and only where nothing round the mouth is
     * open ground (the lining sealed a cave cell there as the winze passed: cutting it open again joined the shaft to the cave);
     * water, lava and loose ground round it are walled up first ({@link #shoreUp}). Returns whether it was cut.
     */
    private boolean openMouth(ServerLevel level, Drift d) {
        BlockPos[] pair = {d.mouth, d.mouth.above()};
        for (BlockPos p : pair) {
            BlockState st = level.getBlockState(p);
            if (inOtherRoom(p) || !st.getFluidState().isEmpty() || !gives(level, p, st)) {
                return false;
            }
        }
        if (check(level, d) != Verdict.CUT) {
            return false;                                        // the first column is a cave, a fluid, a hole in the floor, built, or someone else's
        }
        BlockPos first = d.cells.get(0);
        for (BlockPos p : pair) {
            for (Direction dir : Direction.values()) {
                BlockPos n = p.relative(dir);
                if (inShaft(n) || d.ours(n) || n.equals(first) || n.equals(first.above())) {
                    continue;
                }
                BlockState ns = level.getBlockState(n);
                if (ns.isAir() || plant(ns)) {
                    return false;                                // a cave against the wall: never opened onto
                }
            }
        }
        for (BlockPos p : pair) {
            shoreUp(level, d, p);
        }
        cut(level, d.mouth, true);
        cut(level, d.mouth.above(), false);
        return true;
    }

    private static int workTicks(SettlerMob rat, int base) {
        return Math.max(6, Math.round(base / rat.tempo()));
    }

    enum Verdict { CUT, WAIT, SKIPPED, BLOCKED }

    /**
     * Look at the drift's next column and settle it: gnaw it (CUT), wait for the ground to load (WAIT),
     * or move past it here and now: already open like a tunnel (SKIPPED), or no way on (BLOCKED: the
     * branch, or on the main drift the whole drift, ends).
     */
    Verdict check(ServerLevel level, Drift d) {
        while (!d.done && d.next < d.cells.size()) {
            BlockPos feet = d.cells.get(d.next);
            BlockPos head = feet.above();
            if (!level.isLoaded(feet) || !level.isLoaded(feet.below()) || !level.isLoaded(head.above())) {
                return Verdict.WAIT;
            }
            if (inOtherRoom(feet) || inOtherRoom(head)) {
                d.blockHere();
                return Verdict.BLOCKED;
            }
            BlockState below = level.getBlockState(feet.below());
            if (!below.isFaceSturdy(level, feet.below(), Direction.UP) && !d.ours(feet.below())) {
                d.blockHere();                                   // a hole in the floor: no drift over a drop
                return Verdict.BLOCKED;
            }
            BlockState a = level.getBlockState(feet), b = level.getBlockState(head);
            if (a.isAir() && b.isAir()) {
                if (tunnelShaped(level, d, feet)) {
                    d.next++;                                    // someone opened it already (a player's own strip-mine)
                    continue;
                }
                d.blockHere();                                   // a cave: no breaking into it
                return Verdict.BLOCKED;
            }
            if (!cuttable(level, feet, a) || !cuttable(level, head, b)) {
                d.blockHere();
                return Verdict.BLOCKED;
            }
            if (opensOntoCave(level, d, feet) || opensOntoCave(level, d, head)) {
                d.blockHere();
                return Verdict.BLOCKED;
            }
            return Verdict.CUT;
        }
        d.done = true;
        return Verdict.BLOCKED;
    }

    private boolean inOtherRoom(BlockPos p) {
        for (BoundingBox o : others) {
            if (o.isInside(p)) {
                return true;
            }
        }
        return false;
    }

    /** Air or natural ground that gives way to teeth (no bedrock, no fluid, nothing built). */
    private static boolean cuttable(ServerLevel level, BlockPos p, BlockState s) {
        if (s.isAir()) {
            return true;
        }
        return s.getFluidState().isEmpty() && s.getDestroySpeed(level, p) >= 0 && ground(s);
    }

    /** An open column shaped like a tunnel (rock on both sides and overhead), not the edge of a cave. */
    private static boolean tunnelShaped(ServerLevel level, Drift d, BlockPos feet) {
        int seg = d.segment.get(d.next);
        Direction along = seg == 0 ? d.facing : (seg % 2 == 1 ? d.facing.getCounterClockWise() : d.facing.getClockWise());
        Direction s1 = along.getClockWise(), s2 = along.getCounterClockWise();
        for (BlockPos p : new BlockPos[]{feet.relative(s1), feet.relative(s2), feet.above().relative(s1), feet.above().relative(s2),
                feet.above(2)}) {
            if (level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Whether opening {@code p} would join the drift to open ground it did not dig (a cave, a ravine, someone's cellar). */
    private boolean opensOntoCave(ServerLevel level, Drift d, BlockPos p) {
        for (Direction dir : Direction.values()) {
            BlockPos n = p.relative(dir);
            if (d.cells.get(d.next).equals(n) || d.cells.get(d.next).above().equals(n) || d.ours(n) || box().isInside(n)) {
                continue;
            }
            BlockState ns = level.getBlockState(n);
            if (ns.isAir() || plant(ns)) {
                return true;                                     // open ground, a cave's plant in it or not
            }
        }
        return false;
    }

    /** Gnaw the drift's next column: its ore and stone to the bins, fluids and loose ground beside it walled up, veins noted. */
    void digColumn(ServerLevel level, Drift d) {
        BlockPos feet = d.cells.get(d.next);
        for (BlockPos p : new BlockPos[]{feet, feet.above()}) {
            shoreUp(level, d, p);
            cut(level, p, true);
        }
        int index = d.next;
        d.next++;
        if (d.segment.get(index) == 0 && (index + 1) % TORCH_EVERY == 0) {
            torch(level, d, feet);
        }
        for (BlockPos p : new BlockPos[]{feet, feet.above()}) {
            noteVeins(level, d, p);
        }
        if (d.next >= d.cells.size()) {
            d.done = true;
        }
    }

    private void followVein(ServerLevel level, Drift d, BlockPos cell) {
        BlockState s = level.getBlockState(cell);
        if (!s.is(Tags.Blocks.ORES) || !cuttable(level, cell, s) || !level.isLoaded(cell)) {
            return;
        }
        boolean reached = false;
        for (Direction dir : Direction.values()) {
            BlockPos n = cell.relative(dir);
            BlockState ns = level.getBlockState(n);
            if (!ns.getFluidState().isEmpty()) {
                return;                                          // an ore against water or lava is left in the rock
            }
            if (ns.isAir()) {
                if (!d.ours(n) && !box().isInside(n)) {
                    return;                                      // it shows in a cave's wall too: left for whoever finds the cave
                }
                reached = true;
            }
        }
        if (!reached) {
            return;
        }
        cut(level, cell, true);
        d.veinDug.add(cell.asLong());
        noteVeins(level, d, cell);
    }

    private void noteVeins(ServerLevel level, Drift d, BlockPos p) {
        for (Direction dir : Direction.values()) {
            BlockPos n = p.relative(dir);
            if (d.queued.size() >= VEIN_MAX * 8 || !level.isLoaded(n)) {
                continue;
            }
            BlockState s = level.getBlockState(n);
            if (s.is(Tags.Blocks.ORES) && DigJob.natural(s) && d.queued.add(n.asLong()) && d.vein.size() < VEIN_MAX) {
                d.vein.addLast(n.immutable());
            }
        }
    }

    /** Water and lava beside the drift, and loose sand or gravel over it, are walled up with rubble. */
    private void shoreUp(ServerLevel level, Drift d, BlockPos p) {
        BlockState lining = (p.getY() < 0 ? Blocks.COBBLED_DEEPSLATE : Blocks.COBBLESTONE).defaultBlockState();
        for (Direction dir : Direction.values()) {
            BlockPos n = p.relative(dir);
            if (d.ours(n) || d.cells.get(Math.min(d.next, d.cells.size() - 1)).equals(n)) {
                continue;
            }
            BlockState s = level.getBlockState(n);
            if (!s.getFluidState().isEmpty() || dir == Direction.UP && s.getBlock() instanceof FallingBlock) {
                claude.underempire.core.room.Clearance.before(level, n, lining);   // nobody swimming in it is walled in
                level.setBlock(n, lining, Block.UPDATE_CLIENTS);
            }
        }
    }

    /**
     * One block out of the rock: ore to the Ore Cart (the settlement's own currency's ore to its currency, warpstone for the
     * Heart), stone to the Stone Bin while there is room. Public for the tests.
     */
    public void cut(ServerLevel level, BlockPos p, boolean noisy) {
        BlockState s = level.getBlockState(p);
        if (s.isAir()) {
            return;
        }
        if (Currencies.ore(heart, s)) {
            heart.addCurrency(1 + level.random.nextInt(3), "Ore Mine");
            found++;
        } else {
            boolean ore = s.is(Tags.Blocks.ORES);
            for (ItemStack drop : Block.getDrops(s, level, p, null, null, PICK)) {
                if (ore) {
                    ItemStack rest = store(level, carts, drop);
                    if (!rest.isEmpty()) {
                        Block.popResource(level, box().getCenter(), rest);    // the carts are full: heaped by them
                    }
                } else if (stoneLike(drop)) {
                    store(level, stone, drop);                    // a full Stone Bin: the rubble is left in the tunnels
                }
            }
            if (ore) {
                found++;
            }
        }
        if (noisy) {
            level.levelEvent(2001, p, Block.getId(s));
        }
        level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        gnawed++;
    }

    private static boolean stoneLike(ItemStack s) {
        return s.is(Tags.Items.COBBLESTONES) || s.is(Tags.Items.STONES) || s.is(Tags.Items.GRAVELS) || s.is(Items.FLINT)
                || s.is(Items.TUFF) || s.is(Items.CALCITE) || s.is(Items.CLAY_BALL);
    }

    private static void torch(ServerLevel level, Drift d, BlockPos feet) {
        BlockPos at = feet.above();
        Direction out = d.facing.getClockWise();                 // hung on the left wall, pointing right
        BlockPos wall = at.relative(out.getOpposite());
        BlockState t = Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, out);
        if (level.getBlockState(at).isAir() && level.getBlockState(wall).isFaceSturdy(level, wall, out) && t.canSurvive(level, at)) {
            level.setBlock(at, t, Block.UPDATE_ALL);
        }
    }

    /** One roll of the old faces: what ore lies at this depth, as it lies in the world. */
    void trickle(ServerLevel level) {
        ItemStack got = roll(level.random, winze != null && steps > 0 ? winzeStep(steps - 1).getY() : box().minY() + 1);
        if (got.isEmpty()) {
            heart.addCurrency(1, "Ore Mine");
        } else {
            ItemStack rest = store(level, carts, got);
            if (!rest.isEmpty()) {
                Block.popResource(level, box().getCenter(), rest);
            }
        }
        found++;
        level.playSound(null, box().getCenter(), net.minecraft.sounds.SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 0.8f, 0.8f);
    }

    private static double tri(int y, int peak, int lo, int hi) {
        if (y <= lo || y >= hi) {
            return 0;
        }
        return y <= peak ? (y - lo) / (double) (peak - lo) : (hi - y) / (double) (hi - peak);
    }

    /** A depth's ores, weighted roughly as 1.21's ore features spread them; EMPTY means a warpstone shard for the Heart. */
    public static ItemStack roll(RandomSource r, int y) {
        double[] w = {
                y >= 0 ? Math.max(0, 1 - Math.abs(y - 96) / 160.0) : 0,              // coal
                0.9 * tri(y, 48, -16, 112),                                           // copper
                tri(y, 16, -24, 56) + (y < 72 ? 0.15 : 0),                            // iron
                0.35 * tri(y, -16, -64, 32),                                          // gold
                y <= 15 ? 0.1 + 0.5 * (15 - y) / 79.0 : 0,                            // redstone
                0.35 * tri(y, 0, -32, 32) + (y < 64 ? 0.05 : 0),                      // lapis
                y <= 16 ? 0.12 * (16 - y) / 80.0 : 0,                                  // diamond
                0.03,                                                                 // warpstone
        };
        double sum = 0;
        for (double v : w) {
            sum += v;
        }
        double pick = r.nextDouble() * sum;
        int k = 0;
        while (k < w.length - 1 && (pick -= w[k]) > 0) {
            k++;
        }
        return switch (k) {
            case 0 -> new ItemStack(Items.COAL, 1 + r.nextInt(2));
            case 1 -> new ItemStack(Items.RAW_COPPER, 2 + r.nextInt(4));
            case 2 -> new ItemStack(Items.RAW_IRON, 1 + (r.nextInt(3) == 0 ? 1 : 0));
            case 3 -> new ItemStack(Items.RAW_GOLD, 1);
            case 4 -> new ItemStack(Items.REDSTONE, 4 + r.nextInt(2));
            case 5 -> new ItemStack(Items.LAPIS_LAZULI, 4 + r.nextInt(6));
            case 6 -> new ItemStack(Items.DIAMOND, 1);
            default -> ItemStack.EMPTY;
        };
    }

    /** The night's mining: the crew gnaws on down the drifts as it would have awake, or works the old faces. */
    @Override
    public void nightsWork(ServerLevel level, int seconds, List<Component> report) {
        rescan(level);
        resume(level, true);                                     // a night asks a paused winze at once
        long f0 = found, g0 = gnawed;
        int s0 = steps, l0 = levels();
        int columns = Mth.floor(seconds * crewWanted() * 0.35f);
        Set<Drift> waiting = new HashSet<>();
        for (int i = 0; i < columns; i++) {
            Drift d = null;
            for (Drift x : drifts) {
                if (!x.vein.isEmpty()) {
                    d = x;
                    break;
                }
            }
            if (d != null) {
                followVein(level, d, d.vein.pollFirst());
                continue;
            }
            for (Drift x : drifts) {
                if (!x.done && !waiting.contains(x) && (d == null || x.next < d.next)) {
                    d = x;
                }
            }
            if (d == null && driftsDone() && winze != null && !winzeStopped && waiting.isEmpty()) {
                if (winzeCheck(level, steps) != Verdict.CUT) {
                    break;
                }
                sinkStep(level);                                 // the drifts done: the winze goes down a step
                continue;
            }
            if (d == null) {
                break;
            }
            Verdict v = check(level, d);
            if (v == Verdict.CUT) {
                digColumn(level, d);
            } else if (v == Verdict.WAIT) {
                waiting.add(d);                                  // unloaded ground: that drift waits for the day
            }
        }
        if (trickles()) {
            for (int i = 0; i < seconds * 20 / TRICKLE_TICKS; i++) {
                trickle(level);                                  // the old faces, the winze sinking or not
            }
        }
        if (gnawed > g0 || found > f0) {
            report.add(Component.literal("The miners gnawed " + (gnawed - g0) + " blocks of drift and found " + (found - f0) + " ore"
                    + (steps > s0 ? ", and sank the winze " + (steps - s0) + " steps" + (levels() > l0 ? " to level " + levels() : "") : "")
                    + ".").withStyle(ChatFormatting.DARK_GREEN));
        }
    }

    @Override
    public Gauge gauge(ServerLevel level) {
        String state = state();
        if (workedOut()) {
            return new Gauge(1f, state);
        }
        if (sinking()) {
            int[] n = toNextLevel();
            return new Gauge(n[1] / (float) n[0], state);
        }
        int[] td = levelDug();
        return td[0] == 0 ? null : new Gauge(td[1] / (float) td[0], state);
    }

    /** The winze going down: the drifts done, and deeper to go. */
    private boolean sinking() {
        return winze != null && !winzeStopped && driftsDone();
    }

    /** The current level's drifts: {total cells, cells dug}. */
    private int[] levelDug() {
        int total = 0, dug = 0;
        for (Drift d : drifts) {
            if (d.level == levels()) {
                total += d.cells.size();
                dug += d.done ? d.cells.size() : d.next;
            }
        }
        return new int[]{total, dug};
    }

    private String state() {
        String at = levels() > 0 ? "level " + levels() + " (y " + winzeStep(steps - 1).getY() + ")" : "";
        if (workedOut()) {
            return "worked out" + (at.isEmpty() ? "" : " at " + at)
                    + (winzeStop.isEmpty() ? "" : " (the winze stopped " + winzeStop + (winzeFinal ? "" : ", and goes on when that is cleared") + ")")
                    + ": the old faces give ore every " + TRICKLE_TICKS / 20 + " s";
        }
        if (sinking()) {
            int[] n = toNextLevel();
            return (at.isEmpty() ? "" : at + ": ") + "sinking the winze to level " + (levels() + 1) + ", step " + n[1] + " of " + n[0]
                    + (trickles() ? "; the old faces give ore every " + TRICKLE_TICKS / 20 + " s" : "");
        }
        int[] td = levelDug();
        return (at.isEmpty() ? "" : at + ": ") + "drifts " + (td[0] == 0 ? 0 : td[1] * 100 / td[0]) + "% gnawed";
    }

    @Override
    public void describe(ServerLevel level, List<Component> out) {
        String state = state();
        out.add(Component.literal("Ore Mine: " + state + ", " + found + " ore found").withStyle(ChatFormatting.DARK_GREEN));
    }

    @Override
    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        int[] next = new int[drifts.size()];
        byte[] done = new byte[drifts.size()];
        for (int i = 0; i < drifts.size(); i++) {
            next[i] = drifts.get(i).next;
            done[i] = (byte) (drifts.get(i).done ? 1 : 0);
        }
        t.putIntArray("next", next);
        t.putByteArray("done", done);
        t.putLong("found", found);
        t.putLong("gnawed", gnawed);
        t.putLong("trickle", nextTrickle);
        t.putInt("winze_steps", steps);
        t.putBoolean("winze_stopped", winzeStopped);
        t.putString("winze_stop", winzeStop);
        t.putBoolean("winze_final", winzeFinal);
        return t;
    }

    @Override
    public void load(CompoundTag t) {
        steps = winze == null ? 0 : t.getInt("winze_steps");
        winzeStopped = t.getBoolean("winze_stopped");
        winzeStop = t.getString("winze_stop");
        winzeFinal = winzeStopped && (t.getBoolean("winze_final") || winzeStop.equals("at the bedrock"));   // a stop saved before pauses: only the bedrock's is for good
        for (int turn = 1; turn < steps / LEVEL; turn++) {
            openLevel(null, turn, false);                        // the levels' drifts set out again, in the order they were opened
        }
        int[] next = t.getIntArray("next");
        byte[] done = t.getByteArray("done");
        for (int i = 0; i < drifts.size() && i < next.length; i++) {
            drifts.get(i).next = next[i];
            drifts.get(i).done = i < done.length && done[i] != 0;
        }
        found = t.getLong("found");
        gnawed = t.getLong("gnawed");
        nextTrickle = t.getLong("trickle");
    }
}
