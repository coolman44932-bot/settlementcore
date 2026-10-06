package claude.underempire.core.food;

import claude.underempire.core.settlement.Building;
import claude.underempire.core.settlement.Settlement;
import claude.underempire.core.work.Bins;
import claude.underempire.core.work.CookWork;
import claude.underempire.core.work.HoardWork;
import claude.underempire.core.work.Works;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The clan's larder is real food in real containers (user, 2026-09-27: "shouldnt the larder be barrels, and the barrels
 * are larder? this fixes my problem when i said i couldnt get back food i put in the heart so most of the time im
 * starving"). It is every container of the settlement's larder rooms (a Skaven Heart Chamber, whose Larder barrels come
 * with it), every container of its Clan Hoard, and any container named {@value #NAME} in its rooms. Food fed to the Heart
 * and food the rooms make go in there, and the Warlord can take it back out.
 *
 * <p>The clan eats item by item, the worst food first (the slop goes to the slaves), into its ration (the settlement's
 * points taken out and not yet eaten). With a Cook-Pits, the raw makings the cooks use are held back, up to what their
 * Pantry is kept stocked with of each ({@link Works#PANTRY_KEEP}), and eaten only when there is nothing else: so the rats
 * don't eat the cooks' potatoes and the fare can climb (the user's pick, 2026-09-27).
 *
 * <p>What the containers hold is counted at most every {@link #RECOUNT_TICKS} ticks (or at once after the larder itself
 * put or took something), and only in loaded chunks: a container out of reach keeps its last count, so the clan never
 * starves over food it merely can't reach, but it eats only what it can.
 */
public final class Larder {

    /** A container named this in any room of the settlement is part of the larder. */
    public static final String NAME = "Larder";
    public static final int RECOUNT_TICKS = 40;

    /** One item taken out to be eaten: its food points (the Fleshpits' double counted) and its grade. */
    public record Meal(Item item, int points, int grade) {
    }

    /** What one container held when last counted: food points by grade, and the cooks' makings by kind. */
    private record Count(int[] points, Map<Item, int[]> makings) {
    }

    private final Settlement home;
    private final Map<Long, Count> known = new HashMap<>();
    private long countedAt = Long.MIN_VALUE;
    private boolean dirty = true;
    /** Points by grade the clan may eat now, and those held back for the cooks (as last counted). */
    private final int[] edible = new int[Fare.GRADES.length];
    private final int[] held = new int[Fare.GRADES.length];

    public Larder(Settlement home) {
        this.home = home;
    }

    // ------------------------------------------------------------------ where it is

    /**
     * The larder's own containers, loaded ones only: every container of a larder room, and those named Larder in the other
     * rooms. Food fed to the settlement goes into these first, then into the Hoard.
     */
    public List<BlockPos> own(ServerLevel level) {
        Set<BlockPos> out = new LinkedHashSet<>();
        for (Building b : home.buildings()) {
            if (home.larderRoom(b)) {
                out.addAll(Bins.containers(level, b.box()));
            } else {
                out.addAll(Bins.named(level, b.box(), NAME));
            }
        }
        return new ArrayList<>(out);
    }

    /** Every container of the larder in loaded chunks: its own, then the Hoard's. */
    public List<BlockPos> containers(ServerLevel level) {
        Set<BlockPos> out = new LinkedHashSet<>(own(level));
        for (HoardWork h : home.works().all(HoardWork.class)) {
            out.addAll(h.barrels(level));
        }
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------ what it holds

    /** Food points in the larder's containers, the held-back makings too. */
    public int food(ServerLevel level) {
        recount(level);
        int n = 0;
        for (int g = 0; g < edible.length; g++) {
            n += edible[g] + held[g];
        }
        return n;
    }

    /** Points by grade the clan may eat now (a copy). */
    public int[] edible(ServerLevel level) {
        recount(level);
        return edible.clone();
    }

    /** Points by grade held back for the cooks (a copy). */
    public int[] held(ServerLevel level) {
        recount(level);
        return held.clone();
    }

    /** The larder was changed from outside (a test, the Warlord's hand): count it again on the next ask. */
    public void changed() {
        dirty = true;
    }

    private void recount(ServerLevel level) {
        long now = level.getGameTime();
        if (!dirty && now - countedAt < RECOUNT_TICKS && now >= countedAt) {
            return;
        }
        dirty = false;
        countedAt = now;
        CookWork cook = cook();
        Map<Item, Boolean> makings = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        for (BlockPos p : containers(level)) {
            Container c = Bins.at(level, p);
            if (c == null) {
                continue;
            }
            seen.add(p.asLong());
            int[] pts = new int[edible.length];
            Map<Item, int[]> mk = new HashMap<>();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                int each = points(s);
                if (each <= 0) {
                    continue;
                }
                int g = Fare.grade(s);
                if (making(level, cook, makings, s)) {
                    mk.computeIfAbsent(s.getItem(), k -> new int[]{0, each, g})[0] += s.getCount();
                } else {
                    pts[g] += each * s.getCount();
                }
            }
            known.put(p.asLong(), new Count(pts, mk));
        }
        known.keySet().removeIf(k -> !seen.contains(k) && level.isLoaded(BlockPos.of(k)));   // gone, or no longer the larder
        java.util.Arrays.fill(edible, 0);
        java.util.Arrays.fill(held, 0);
        Map<Item, int[]> kinds = new HashMap<>();
        for (Count c : known.values()) {
            for (int g = 0; g < edible.length; g++) {
                edible[g] += c.points()[g];
            }
            for (Map.Entry<Item, int[]> e : c.makings().entrySet()) {
                int[] k = kinds.computeIfAbsent(e.getKey(), x -> new int[]{0, e.getValue()[1], e.getValue()[2]});
                k[0] += e.getValue()[0];
            }
        }
        for (int[] k : kinds.values()) {
            int keep = Math.min(k[0], Works.PANTRY_KEEP);
            held[k[2]] += keep * k[1];
            edible[k[2]] += (k[0] - keep) * k[1];
        }
    }

    // ------------------------------------------------------------------ in and out

    /**
     * Food into the larder: its own containers first, then the Hoard's (sorted into its sections). Returns what did not
     * fit anywhere. Only food goes in; anything else comes straight back.
     */
    public ItemStack put(ServerLevel level, ItemStack s) {
        return put(level, s, null);
    }

    /** As {@link #put(ServerLevel, ItemStack)}, telling {@code where} how many went into which room (for the haul log). */
    public ItemStack put(ServerLevel level, ItemStack s, @Nullable java.util.function.ObjIntConsumer<String> where) {
        if (points(s) <= 0) {
            return s;
        }
        List<BlockPos> own = own(level);
        ItemStack rest = s;
        for (BlockPos p : own) {
            Container c = Bins.at(level, p);
            if (rest.isEmpty() || c == null) {
                continue;
            }
            int was = rest.getCount();
            rest = Bins.insert(c, rest);
            if (where != null && rest.getCount() < was) {
                where.accept(home.placeOf(p), was - rest.getCount());
            }
        }
        for (HoardWork h : home.works().all(HoardWork.class)) {
            if (rest.isEmpty()) {
                break;
            }
            int was = rest.getCount();
            rest = h.put(level, rest);
            if (where != null && rest.getCount() < was) {
                where.accept(home.hoardName(), was - rest.getCount());
            }
        }
        if (rest.getCount() != s.getCount()) {
            dirty = true;
            Bins.changed(level, own);
        }
        return rest;
    }

    /**
     * Take one item out to be eaten: the worst food first, the cooks' held-back makings only when there is nothing else.
     * A stew's bowl goes back where the stew was. Returns what was taken, or null when the larder (in reach) is empty.
     */
    @Nullable
    public Meal draw(ServerLevel level) {
        recount(level);
        CookWork cook = cook();
        Map<Item, Boolean> makings = new HashMap<>();
        Map<Item, Integer> kinds = new HashMap<>();
        List<BlockPos> all = containers(level);
        for (BlockPos p : all) {
            Container c = Bins.at(level, p);
            for (int i = 0; c != null && i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (points(s) > 0 && making(level, cook, makings, s)) {
                    kinds.merge(s.getItem(), s.getCount(), Integer::sum);
                }
            }
        }
        Container best = null;
        BlockPos bestAt = null;
        int bestSlot = -1, bestGrade = Integer.MAX_VALUE;
        boolean bestHeld = true;
        for (BlockPos p : all) {
            Container c = Bins.at(level, p);
            for (int i = 0; c != null && i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (points(s) <= 0) {
                    continue;
                }
                int g = Fare.grade(s);
                boolean h = making(level, cook, makings, s) && kinds.getOrDefault(s.getItem(), 0) <= Works.PANTRY_KEEP;
                if (best == null || (bestHeld && !h) || (bestHeld == h && g < bestGrade)) {
                    best = c;
                    bestAt = p;
                    bestSlot = i;
                    bestGrade = g;
                    bestHeld = h;
                }
            }
        }
        if (best == null) {
            return null;
        }
        ItemStack s = best.getItem(bestSlot);
        Meal m = new Meal(s.getItem(), points(s), bestGrade);
        ItemStack left = Fare.leftover(s);
        s.shrink(1);
        best.setChanged();
        if (!left.isEmpty()) {
            ItemStack rest = Bins.insert(best, left);
            if (!rest.isEmpty()) {
                home.addSpoil(rest);
            }
        }
        dirty = true;
        Bins.changed(level, List.of(bestAt));
        return m;
    }

    /**
     * Take food for one mouth that is not the clan's (the Skaven's god's, when the Warlord goes hungry): the kind
     * {@code rank} scores highest (0 or less: never this), the most filling of those, as many of it as make
     * {@code want} nutrition (at least one, at most a stack), and never so many that the larder falls below {@code keep}
     * points. The cooks' held-back makings stay. The clan still eats its worst first; this takes the best. Returns what
     * was taken, or empty when nothing fits.
     */
    public ItemStack takeBest(ServerLevel level, java.util.function.ToIntFunction<ItemStack> rank, int want, int keep) {
        int spare = food(level) - keep;
        if (spare <= 0) {
            return ItemStack.EMPTY;
        }
        CookWork cook = cook();
        Map<Item, Boolean> makings = new HashMap<>();
        Map<Item, Integer> kinds = new HashMap<>();
        List<BlockPos> all = containers(level);
        for (BlockPos p : all) {
            Container c = Bins.at(level, p);
            for (int i = 0; c != null && i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (points(s) > 0 && making(level, cook, makings, s)) {
                    kinds.merge(s.getItem(), s.getCount(), Integer::sum);
                }
            }
        }
        ItemStack best = ItemStack.EMPTY;
        int bestRank = 0;
        for (BlockPos p : all) {
            Container c = Bins.at(level, p);
            for (int i = 0; c != null && i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                int each = points(s);
                if (each <= 0 || each > spare
                        || making(level, cook, makings, s) && kinds.getOrDefault(s.getItem(), 0) <= Works.PANTRY_KEEP) {
                    continue;
                }
                int r = rank.applyAsInt(s);
                if (r > 0 && (best.isEmpty() || r > bestRank || r == bestRank && Fare.nutrition(s) > Fare.nutrition(best))) {
                    best = s.copyWithCount(1);
                    bestRank = r;
                }
            }
        }
        if (best.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int nutrition = Math.max(1, Fare.nutrition(best));
        int n = Math.min(Math.max(1, (want + nutrition - 1) / nutrition), best.getMaxStackSize());
        n = Math.min(n, spare / points(best));
        if (making(level, cook, makings, best)) {
            n = Math.min(n, kinds.getOrDefault(best.getItem(), 0) - Works.PANTRY_KEEP);
        }
        int taken = 0;
        List<BlockPos> touched = new ArrayList<>();
        for (BlockPos p : all) {
            Container c = Bins.at(level, p);
            for (int i = 0; c != null && i < c.getContainerSize() && taken < n; i++) {
                ItemStack s = c.getItem(i);
                if (ItemStack.isSameItemSameComponents(s, best)) {
                    int k = Math.min(s.getCount(), n - taken);
                    s.shrink(k);
                    c.setChanged();
                    taken += k;
                    if (!touched.contains(p)) {
                        touched.add(p);
                    }
                }
            }
        }
        if (taken == 0) {
            return ItemStack.EMPTY;
        }
        dirty = true;
        Bins.changed(level, touched);
        return best.copyWithCount(taken);
    }

    // ------------------------------------------------------------------ helpers

    /** Food points one of these is worth to the settlement (0: not food). */
    private int points(ItemStack s) {
        if (s.isEmpty() || Fare.grade(s) < 0) {
            return 0;
        }
        int n = Fare.nutrition(s);
        return n <= 0 ? 0 : home.foodValue(n);
    }

    @Nullable
    private CookWork cook() {
        List<CookWork> cooks = home.works().all(CookWork.class);
        return cooks.isEmpty() ? null : cooks.get(0);
    }

    /**
     * A raw making the cooks use, held back for them: not cooked food, which the clan eats as it is, and not slop, which
     * always goes first (with Pam's, some recipe takes rotten flesh: held back, it left the clan eating its bread first).
     */
    private static boolean making(ServerLevel level, @Nullable CookWork cook, Map<Item, Boolean> memo, ItemStack s) {
        if (cook == null || Fare.grade(s) != Fare.RAW) {
            return false;
        }
        return memo.computeIfAbsent(s.getItem(), k -> cook.wants(level, s));
    }
}
