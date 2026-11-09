package claude.settlementcore.defence;

import claude.settlementcore.unit.Footing;
import claude.settlementcore.unit.SettlerMob;
import claude.settlementcore.settlement.Settlement;
import claude.settlementcore.Core;
import claude.settlementcore.CoreConfig;
import claude.settlementcore.Nbt;
import claude.settlementcore.settlement.SettlementSpawns;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The nest defends itself (user, 2026-09-26, picked from the ideas after the siege test: a warband of slaves on work orders,
 * its Warlord away, lost 21 of 42 to 17 of the undead and killed none). The Heart keeps one of these and looks about its
 * warren every second, and a Watch-Post's gong calls it too. When foes are in the warren the whole nest turns on them, the
 * Skaven way, and wins by numbers and by its own tunnels:
 * <ul>
 *   <li><b>The horde rises.</b> Defenders come up out of the horde, {@link #perFoe} to a foe, up to {@link #max}, a few a
 *   second: slaves in front as the plug, clanrats behind (two slaves to a clanrat), up between the foe and the Heart and
 *   in a tunnel where there is one, so the tunnels are the killing ground. They hold where they came up and fall on
 *   anything that comes, whether or not the Warlord is about (Horde#tend leaves them be).</li>
 *   <li><b>The whole nest turns.</b> While the alarm is up every rat in the warren hunts intruders, the slaves at work too
 *   (SkavenUnit#huntsEnemies).</li>
 *   <li><b>Tunnel fighting.</b> A clan rat fighting in its own warren hits {@link #DAMAGE} harder, and {@link #TUNNEL_DAMAGE}
 *   harder in a tunnel no wider than {@link #TIGHT}, and takes heart from the fight about it.</li>
 *   <li><b>The all-clear.</b> {@link #ALL_CLEAR} seconds with no foe in the warren and the defenders sink back into the
 *   horde; the Warlord hears how it went: foes slain, rats lost.</li>
 * </ul>
 * Only where the warren is loaded. What it needs to stand down is saved with the settlement ({@link #save}, {@link #load}): its defenders,
 * those walking home, whether the alarm is up and its tally. A race whose settlers are saved as mobs (Khazuk!'s Dawi) had its defenders
 * come back after a reload on no list at all: uncounted (the brood bred past the cap), never stood down, and refused by the race's absorb
 * ({@link #defends}), so lost (the bug audit of 2026-11-04). A defender that is never saved as a mob (the Waaagh!'s, {@code !defending})
 * is never written, nor kept once it leaves the world ({@link #left}).
 */
@EventBusSubscriber(modid = Core.MODID)
public final class Muster {

    /** Defenders up out of the horde a second. */
    public static final int STEP = 4;
    /** Quiet seconds before the all-clear. */
    public static final int ALL_CLEAR = 10;
    /** How much harder a clan rat hits defending its warren, and in a tunnel no wider than {@link #TIGHT}. */
    public static final double DAMAGE = 0.25, TUNNEL_DAMAGE = 0.5;
    public static final int TIGHT = 3;
    /** How far from the fight rats take its buff. */
    private static final double FIGHT = 32;
    private static final long TELL_EVERY = 20 * 30;
    /** How many of the muster's seconds a defender or homeward settler may be out of the world before it is given up: 10 minutes. */
    public static final int MISSING_SECONDS = 10 * 60;
    public static final Identifier BUFF = Identifier.fromNamespaceAndPath(Core.NAMESPACE, "defending_the_settlement");

    private final Set<UUID> defenders = new LinkedHashSet<>();
    /**
     * The defenders stood down and not yet home: still the muster's to the race's absorb ({@link #defends}) until they sink back into the
     * reserve or are gone. The all-clear cleared them at once, so a defender who walked home (not sinking where it stood) was taken back by
     * nobody, and a digger the muster drafted (Works#draft, on no roll) was lost for good (the review of ffb5306).
     */
    private final Set<UUID> homeward = new LinkedHashSet<>();
    /**
     * The homeward not yet told to go home: stood down while their chunk was unloaded, or loaded back from a save (a settler's walk home is
     * its own, not saved). Each is told ({@link SettlerMob#goHome}) the first second it is loaded again.
     */
    private final Set<UUID> untold = new LinkedHashSet<>();
    /**
     * Seconds each defender or homeward settler has been out of the world (its chunk unloaded: it chased a foe off, say). Kept that long, as it
     * comes back with its chunk; dropped after {@link #MISSING_SECONDS} (dead or absorbed where the settlement could not hear of it), so
     * neither list grows for ever. A death or an absorb the settlement hears of drops it at once ({@link #left}).
     */
    private final Map<UUID, Integer> missing = new HashMap<>();
    private final Set<UUID> foes = new HashSet<>();
    private final Set<UUID> buffed = new HashSet<>();
    private boolean alarmed;
    /** Whether a foe of this alarm got inside (the alarm proper was told, not only the foes at the walls). */
    private boolean broke;
    private int quiet;
    /** Tests: the foes it saw and the rats it buffed, last second. */
    public int lastSeen, lastBuffed;
    private int slain, lost, risen;
    private long toldAt = Long.MIN_VALUE / 2;
    private long besiegedAt = Long.MIN_VALUE / 2;

    public static int perFoe() {
        return CoreConfig.musterPerFoe.getAsInt();
    }

    public static int max() {
        return CoreConfig.musterMax.getAsInt();
    }

    public boolean alarmed() {
        return alarmed;
    }

    /** One of the defenders the alarm raised (the horde's own: Horde#tend leaves it be). */
    public boolean defends(UUID id) {
        return defenders.contains(id) || homeward.contains(id);
    }

    /** Defenders up and alive. */
    public int defenders(ServerLevel level) {
        int n = 0;
        for (UUID id : defenders) {
            if (level.getEntity(id) instanceof SettlerMob r && r.isAlive()) {
                n++;
            }
        }
        return n;
    }

    /**
     * Its defenders up now and those stood down still walking home (#homeward): the settlement's people out of the reserve for now. One alive
     * counts, and so does one out of the world (its chunk unloaded: it comes back with it, and was bred past the cap when it was not counted);
     * one dead and not yet gone does not.
     */
    public int out(ServerLevel level) {
        return outOf(level, defenders) + outOf(level, homeward);
    }

    private static int outOf(ServerLevel level, Set<UUID> ids) {
        int n = 0;
        for (UUID id : ids) {
            Entity e = level.getEntity(id);
            if (e == null || (e instanceof SettlerMob r && r.isAlive())) {
                n++;
            }
        }
        return n;
    }

    /** The foes the alarm is out for (Jade's count): never one shut in ({@link #shutIn}), though a Watch-Post saw it, once a second has judged it. */
    public int foes() {
        return foes.size();
    }

    public int slain() {
        return slain;
    }

    public int lost() {
        return lost;
    }

    public int risen() {
        return risen;
    }

    /** A Watch-Post's gong: foes it saw, in the warren or about it. The next {@link #second} drops any that is shut in ({@link #shutIn}); a post leaves those out itself. */
    public void sound(List<? extends LivingEntity> seen) {
        for (LivingEntity e : seen) {
            foes.add(e.getUUID());
        }
    }

    /** Every second, from the Heart. */
    public void second(ServerLevel level, Settlement h) {
        if (!CoreConfig.muster.getAsBoolean() || h.owner() == null) {
            // no muster to run (turned off, or no owner): an alarm loaded from a save, or left on when it was turned off, stands down, and
            // those out are pruned, so the gates open and none is counted out for good (the review of ebfe243)
            if (alarmed) {
                standDown(level, h);
            }
            prune(level, homeward);
            prune(level, defenders);
            tellUntold(level);                                   // one out of the world at the stand-down, back now: sent home, its buff off
            return;
        }
        Map<Integer, Footing.Known> known = new HashMap<>();   // what the floods found about the foes, a body height to a Known
        List<Mob> in = intruders(level, h);
        in.removeIf(m -> shutIn(level, m, known));
        List<Mob> at = atTheWalls(level, h);                     // walled in: foes at the walls, outside (docs/plans/walls.md, E3)
        at.removeIf(m -> in.contains(m) || shutIn(level, m, known));
        in.addAll(at);
        boolean walled = h.walled();
        Vec3 heart = Vec3.atCenterOf(h.getBlockPos());
        BoundingBox ringNow = ring(h);
        for (Iterator<UUID> it = foes.iterator(); it.hasNext(); ) {   // the ones a Watch-Post saw, still about the warren
            if (!(level.getEntity(it.next()) instanceof LivingEntity e) || !e.isAlive()) {
                it.remove();
            } else if (e instanceof Mob m && !in.contains(m) && m.distanceToSqr(heart) < 96 * 96) {
                if (walled && !h.insideWalls(m.blockPosition())) {
                    it.remove();                                // walled in: a foe gone from the walls is outside, and no foe of this alarm
                } else if (!walled && !ringNow.isInside(m.blockPosition()) && !h.musterFollows(m.blockPosition())) {
                    it.remove();                                // gone from the ground the race defends (Settlement#musterFollows)
                } else if (shutIn(level, m, known)) {
                    it.remove();                                // shut in since it was seen: no foe, nor one slain when it dies (Jade's count, the tally)
                } else {
                    in.add(m);
                }
            }
        }
        lastSeen = in.size();
        boolean inside = false;                                  // a foe that is not only at the walls: in, or one a Watch-Post saw (unwalled)
        for (Mob m : in) {
            if (!at.contains(m)) {
                inside = true;
                break;
            }
        }
        if (!in.isEmpty()) {
            if (!alarmed) {
                h.shutGates(level, true);
                if (inside) {
                    raise(level, h, in);
                } else {
                    besieged(level, h, in);
                }
                broke = inside;
            } else if (inside && !broke) {
                broke = true;                                    // at the walls, and now in: the alarm proper
                raise(level, h, in);
            }
            alarmed = true;
            quiet = 0;
            for (Mob m : in) {
                foes.add(m.getUUID());
            }
        } else if (alarmed && ++quiet >= ALL_CLEAR) {
            standDown(level, h);
            return;
        }
        prune(level, homeward);                                  // home (sunk) or dead: gone; out of the world: kept a while
        prune(level, defenders);                                 // the same: one that chased a foe off past the loaded ground comes back
        tellUntold(level);
        if (!alarmed) {
            return;
        }
        if (!in.isEmpty()) {
            rise(level, h, in);
        }
        buff(level, h, in);
    }

    // ------------------------------------------------------------------ what counts as a foe in the warren

    /**
     * Whether {@code m} is a foe shut in: a walker in a hollow too small for a way out ({@link Footing#escapes}, asked for a body of its own
     * height), which no rat can come at and none ever sees, so it is not a foe the muster counts. A husk sealed in stone kept the alarm up for as
     * long as it lived, {@link #perFoe} defenders up for it and no all-clear (aRatGivesUpAFoeItCannotReach). Only a foe that is not riding, not
     * in the water and not a flier ({@link #flies}) is asked, and only if a body of its height fits where its feet are (not on a stair, say): any
     * other is counted, as it was. Whether it is on the ground is not asked: a slime sealed in a pocket hops, and a spider climbs, and both are in
     * the air some of the seconds the muster looks, which counted them and put the all-clear back (the review of 18f90e3); the flood's answer
     * is the one for every cell of the hollow, so it does not change as the foe moves in it. (A hollow of {@link Footing#LIMIT} cells to stand
     * in or more is room enough that its foe is counted: the flood asks whether the foe is in a pocket, not whether the rats can come at it.)
     */
    public static boolean shutIn(ServerLevel level, Mob m, Map<Integer, Footing.Known> known) {
        if (m.isPassenger() || flies(m) || m.isInWater()) {
            return false;
        }
        int height = Math.max(1, (int) Math.ceil(m.getBbHeight() - 0.05));
        BlockPos at = m.blockPosition();
        return Footing.fits(level, at, height)
                && !Footing.escapes(level, at, Footing.LIMIT, known.computeIfAbsent(height, k -> new Footing.Known()), height);
    }

    /** A foe that does not walk: one that flies (a ghast, a phantom, a vex, a bee) or has no gravity. It is in no hollow of the ground's. */
    private static boolean flies(Mob m) {
        return m.isNoGravity() || m instanceof net.minecraft.world.entity.monster.Ghast || m instanceof net.minecraft.world.entity.monster.Phantom || m.getMoveControl() instanceof FlyingMoveControl
                || m.getNavigation() instanceof FlyingPathNavigation;
    }

    /** A foe: a monster (not a clan rat) or a raider; not a neutral one that has not been angered. */
    public static boolean foe(Mob m) {
        if (m instanceof SettlerMob || !m.isAlive()) {
            return false;
        }
        if (m instanceof NeutralMob n && !n.isAngry()) {
            return false;
        }
        return m instanceof Enemy || m instanceof Raider;
    }

    /**
     * The foes at its walls: a walled settlement's ({@link Settlement#wallBounds}), outside the walls and no more than Walls#AT_THE_WALLS from them
     * ({@link Settlement#atWalls}). Its gates shut, and its defenders come up inside the wall nearest them ({@link #cell}: on the ground the muster
     * holds, which is inside the walls), and man it. None for a settlement that isn't walled.
     */
    static List<Mob> atTheWalls(ServerLevel level, Settlement h) {
        BoundingBox b = h.wallBounds();
        if (b == null || !h.walled()) {
            return new ArrayList<>();
        }
        AABB area = AABB.of(b).inflate(claude.settlementcore.settlement.Walls.AT_THE_WALLS + 1);
        if (!level.hasChunksAt((int) area.minX, (int) area.minZ, (int) area.maxX, (int) area.maxZ)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(level.getEntitiesOfClass(Mob.class, area, m -> foe(m) && h.atWalls(m.blockPosition())));
    }

    /** The foes in the warren now: in (or just round) its rooms and tunnels, or near the Heart. */
    static List<Mob> intruders(ServerLevel level, Settlement h) {
        Set<Mob> out = new LinkedHashSet<>();
        BoundingBox ring = ring(h);
        List<BoundingBox> boxes = new ArrayList<>(h.musterBoxes());
        boxes.add(ring);
        for (BoundingBox b : boxes) {
            AABB area = AABB.of(b).inflate(3);
            if (!level.hasChunksAt((int) area.minX, (int) area.minZ, (int) area.maxX, (int) area.maxZ)) {
                continue;
            }
            out.addAll(level.getEntitiesOfClass(Mob.class, area, m -> foe(m) && holds(h, ring, m.blockPosition())));
        }
        return new ArrayList<>(out);
    }

    /**
     * The ground about the heart the muster looks in and defends, whether or not the settlement calls it its own: {@link Settlement#reach} each way
     * across, and no higher or lower than {@link Settlement#HEART_REACH} (a race that holds a wide plain does not hold a wider sky and bedrock). The
     * Warren Heart's reach is its own {@code inSettlement}'s cube about the heart, so this adds nothing to it.
     */
    public static BoundingBox ring(Settlement h) {
        BlockPos c = h.getBlockPos();
        int across = Math.max(0, h.reach());
        int high = Math.min(across, Settlement.HEART_REACH);
        return new BoundingBox(c.getX() - across, c.getY() - high, c.getZ() - across, c.getX() + across, c.getY() + high, c.getZ() + across);
    }

    /**
     * Whether the muster defends {@code p}: it is in the settlement, or within the settlement's {@link Settlement#reach} of its heart ({@code ring}, from
     * {@link #ring}). The one rule for what counts as a foe, where a defender comes up and who takes heart from the fight.
     */
    public static boolean holds(Settlement h, BoundingBox ring, BlockPos p) {
        if (h.walled()) {
            return h.insideWalls(p);                             // walled in: outside the walls is outside (docs/plans/walls.md)
        }
        return h.musterHolds(p) || ring.isInside(p);
    }

    // ------------------------------------------------------------------ the horde rises

    private void rise(ServerLevel level, Settlement h, List<Mob> in) {
        int want = Math.min(max(), perFoe() * in.size());
        int alive = defenders(level);                            // up here: one kept while out of the world is not at this fight
        Vec3 heart = Vec3.atCenterOf(h.getBlockPos());
        Mob head = in.stream().min(Comparator.comparingDouble(m -> m.distanceToSqr(heart))).orElseThrow();
        for (int i = 0; i < STEP && alive < want && h.reserve() > 0; i++) {
            // two slaves in front to a clanrat behind them, while the horde has both
            boolean clanrat = h.reserveWorkers() <= 0 || (h.reserveSoldiers() > 0 && (risen % 3 == 2));
            BlockPos at = cell(level, h, head, clanrat);
            if (at == null) {
                break;
            }
            SettlerMob r = h.riseFromReserve(level, at, clanrat, true);
            if (r == null) {
                continue;
            }
            defenders.add(r.getUUID());
            risen++;
            alive++;
            r.hearten(20f);
        }
        if (alive < want && h.reserve() <= 0) {
            // the reserve is empty (a big dig took it, since 0.5.6): the diggers nearest the foe drop their work and turn out
            for (SettlerMob r : h.works().draft(level, Math.min(STEP, want - alive), head.blockPosition())) {
                defenders.add(r.getUUID());
                risen++;
                alive++;
                r.hearten(20f);
                r.setTarget(head);
            }
        }
    }

    /**
     * Where a defender comes up: between the foe nearest the Heart and the Heart, a few blocks towards the Heart (a clanrat
     * a few more, behind the slaves), in the warren (or within the settlement's {@link Settlement#reach} of its heart), on standable
     * ground with a way out ({@link Footing#escapes}: never a hollow shut in on every side, of one cell or of many), under cover (unless
     * the settlement {@link Settlement#mustersUnderSky musters under the sky}: a surface race's defenders stand in the open, at the first
     * cell found, there being no tunnel to look on for), a tunnel's narrow floor before a room's. The candidates of one ask share what the
     * ways out found ({@link Footing.Known}). Null if there is nowhere. Public for the engine's tests.
     */
    @Nullable
    public static BlockPos cell(ServerLevel level, Settlement h, Mob head, boolean clanrat) {
        Vec3 from = head.position();
        Vec3 dir = Vec3.atCenterOf(h.getBlockPos()).subtract(from);
        dir = dir.lengthSqr() < 1 ? new Vec3(1, 0, 0) : dir.normalize();
        BoundingBox ring = ring(h);
        boolean sky = h.mustersUnderSky();
        Footing.Known known = new Footing.Known();
        BlockPos open = null;
        for (int t = 0; t < 48; t++) {
            double d = (clanrat ? 6 : 3) + level.getRandom().nextDouble() * 4 + t / 12.0;
            Vec3 p = from.add(dir.scale(d)).add((level.getRandom().nextDouble() - 0.5) * 6, 0, (level.getRandom().nextDouble() - 0.5) * 6);
            BlockPos base = BlockPos.containing(p);
            for (int dy = 0; dy <= 6; dy++) {
                for (int s : new int[]{dy, -dy}) {
                    BlockPos q = base.above(s);
                    if (!level.isLoaded(q) || !holds(h, ring, q) || !SettlerMob.standable(level, q)
                            || (!sky && SettlerMob.underOpenSky(level, q)) || !Footing.escapes(level, q, Footing.LIMIT, known)) {
                        continue;
                    }
                    if (sky || tight(level, q)) {
                        return q;                           // a tunnel: the killing ground; in the open there is no tunnel to look on for, the first cell will do
                    }
                    if (open == null) {
                        open = q;
                    }
                }
            }
        }
        return open;
    }

    /**
     * A tunnel no wider than {@link #TIGHT} across one way or the other, at {@code p}. A cell shut in on every side is the tightest there is,
     * and why {@link #cell} asks {@link Footing#escapes} of every cell before it asks this.
     */
    public static boolean tight(Level level, BlockPos p) {
        return run(level, p, Direction.EAST) + run(level, p, Direction.WEST) + 1 <= TIGHT
                || run(level, p, Direction.NORTH) + run(level, p, Direction.SOUTH) + 1 <= TIGHT;
    }

    private static int run(Level level, BlockPos p, Direction dir) {
        int n = 0;
        BlockPos q = p;
        for (int i = 0; i < TIGHT; i++) {
            q = q.relative(dir);
            if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty()) {
                break;
            }
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ tunnel fighting

    /** The clan's rats about the fight hit harder in their own warren (hardest in a tunnel), and take heart. */
    private void buff(ServerLevel level, Settlement h, List<Mob> in) {
        Set<UUID> now = new HashSet<>();
        BoundingBox ring = ring(h);
        for (Mob foe : in) {
            for (SettlerMob r : level.getEntitiesOfClass(SettlerMob.class, foe.getBoundingBox().inflate(FIGHT),
                    r -> r.isAlive() && r.settlement() == h && !r.isRogue())) {
                if (!now.add(r.getUUID())) {
                    continue;
                }
                if (!holds(h, ring, r.blockPosition())) {
                    continue;
                }
                boolean tunnel = tight(level, r.blockPosition());
                apply(r, tunnel ? TUNNEL_DAMAGE : DAMAGE);
                r.hearten(tunnel ? 15f : 8f);
            }
        }
        for (UUID id : buffed) {
            if (!now.contains(id) && level.getEntity(id) instanceof SettlerMob r) {
                clear(r);
            }
        }
        buffed.clear();
        buffed.addAll(now);
        lastBuffed = now.size();
    }

    private static void apply(SettlerMob r, double amount) {
        AttributeInstance a = r.getAttribute(Attributes.ATTACK_DAMAGE);
        if (a != null) {
            a.addOrUpdateTransientModifier(new AttributeModifier(BUFF, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private static void clear(SettlerMob r) {
        AttributeInstance a = r.getAttribute(Attributes.ATTACK_DAMAGE);
        if (a != null) {
            a.removeModifier(BUFF);
        }
    }

    /** How much harder {@code r} hits for the muster right now (0: not at all). */
    public static double buffOf(SettlerMob r) {
        AttributeInstance a = r.getAttribute(Attributes.ATTACK_DAMAGE);
        AttributeModifier m = a == null ? null : a.getModifier(BUFF);
        return m == null ? 0 : m.amount();
    }

    // ------------------------------------------------------------------ the alarm and the all-clear

    /** Foes at the walls, none inside: the gates are shut (by {@link #second}), its owner told; no bell: nothing is in yet. */
    private void besieged(ServerLevel level, Settlement h, List<Mob> at) {
        slain = 0;
        lost = 0;
        risen = 0;
        long now = level.getGameTime();
        if (now - besiegedAt < TELL_EVERY) {
            return;                                              // its own clock: a foe that then gets in is told at once (raise)
        }
        besiegedAt = now;
        h.tellOwner(h.besiegedLine(claude.settlementcore.Words.capital(count(at)), at.size() == 1), ChatFormatting.GOLD);
    }

    private void raise(ServerLevel level, Settlement h, List<Mob> in) {
        slain = 0;
        lost = 0;
        risen = 0;
        level.playSound(null, h.getBlockPos(), SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 3f, 0.5f);
        long now = level.getGameTime();
        if (now - toldAt < TELL_EVERY) {
            return;
        }
        toldAt = now;
        Vec3 heart = Vec3.atCenterOf(h.getBlockPos());
        Mob head = in.stream().min(Comparator.comparingDouble(m -> m.distanceToSqr(heart))).orElseThrow();
        String where = h.placeOf(head.blockPosition());
        h.tellOwner(alarmText(h, count(in), where, in.size() == 1), ChatFormatting.RED);
    }

    /** The alarm as {@code h} says it: its own whole line ({@link Settlement#alarmLine}) if it writes one, else the sentence in its words. */
    public static String alarmText(Settlement h, String foes, String where, boolean one) {
        String own = h.alarmLine(claude.settlementcore.Words.capital(foes), where, one);   // "a Zombie" comes lower case: a race's line may open with it
        return own != null ? own : attackedLine(h.musterWords(), foes, where, one);
    }

    /** The all-clear as {@code h} says it: its own whole line ({@link Settlement#allClearLine}) if it writes one, else the sentence in its words. */
    public static String clearText(Settlement h, int slain, int lost, int home) {
        String own = h.allClearLine(slain, lost, home);
        return own != null ? own : clearLine(h.musterWords(), slain, lost, home);
    }

    /**
     * The alarm's line to the owner, in the settlement's words ({@link Settlement#musterWords}): {@code foes} are what it is in ("5 Zombies and a Pillager"),
     * {@code where} the room they are in (none: in no room), {@code one} whether there is the one of them. Public for the engine's tests.
     */
    public static String attackedLine(Settlement.MusterWords w, String foes, String where, boolean one) {
        return "The " + w.place() + " is attacked! " + claude.settlementcore.Words.capital(foes)
                + (where.isEmpty() ? " in " + w.ground() : " in the " + where)
                + ": " + w.rises() + " to meet " + (one ? "it." : "them.");
    }

    /**
     * The all-clear's line to the owner, in the settlement's words: {@code slain} foes slain, {@code lost} of its own lost, and {@code home} of its
     * defenders stood down (none: no more said). Public for the engine's tests.
     */
    public static String clearLine(Settlement.MusterWords w, int slain, int lost, int home) {
        return "The " + w.place() + " is clear: " + slain + (slain == 1 ? " foe" : " foes") + " slain, " + lost + " of ours lost."
                + (home > 0 ? " " + claude.settlementcore.Words.capital(claude.settlementcore.Words.count(home, "defender"))
                + " " + (home == 1 ? singular(w.verb()) : w.verb()) + " " + w.back() + "." : "");
    }

    /** A plural verb made singular, for one defender: "sink" to "sinks", "march" to "marches". */
    static String singular(String verb) {
        return verb.matches(".*(s|x|z|ch|sh)") ? verb + "es" : verb + "s";
    }

    private void standDown(ServerLevel level, Settlement h) {
        h.shutGates(level, false);
        boolean siege = !broke && slain == 0 && lost == 0;      // foes at the walls only, and gone: the gates open, nothing to tell
        broke = false;
        int home = 0;
        for (UUID id : defenders) {
            Entity e = level.getEntity(id);
            if (e instanceof SettlerMob r && r.isAlive()) {
                clear(r);
                homeward.add(id);                                // the muster's until it is home: the race's absorb takes it back
                r.goHome();                                      // back down into the horde
                home++;
            } else if (e == null) {
                homeward.add(id);                                // out of the world (or loaded back from a save, not in yet): stood down all
                untold.add(id);                                  // the same, and told to go home the first second it is back (tellUntold)
                home++;
            }
        }
        for (UUID id : buffed) {
            if (level.getEntity(id) instanceof SettlerMob r) {
                clear(r);
            }
        }
        if (!siege) {
            h.tellOwner(clearText(h, slain, lost, home), ChatFormatting.GREEN);
        }
        defenders.clear();
        buffed.clear();
        foes.clear();
        alarmed = false;
        quiet = 0;
    }

    /** "5 Zombies and a Pillager". */
    private static String count(List<Mob> in) {
        Map<String, Integer> n = new TreeMap<>();
        for (Mob m : in) {
            n.merge(m.getType().getDescription().getString(), 1, Integer::sum);
        }
        return claude.settlementcore.Words.list(n);
    }

    /** The tally: a foe the alarm was out for killed, or one of the clan's rats killed while it was up. */
    @SubscribeEvent
    public static void died(LivingDeathEvent e) {
        if (!(e.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        if (e.getEntity() instanceof SettlerMob r) {
            Settlement h = r.settlement();
            if (h != null && h.muster().alarmed) {
                h.muster().lost++;
            }
            return;
        }
        UUID id = e.getEntity().getUUID();
        for (Settlement h : SettlementSpawns.loaded(level)) {
            if (h.muster().foes.remove(id)) {
                h.muster().slain++;
                return;
            }
        }
    }

    /** How many of its defenders up now or walking home {@code which} holds for (a race counting those it didn't raise itself: Works#draft's diggers). */
    public int countDefenders(java.util.function.Predicate<UUID> which) {
        int n = 0;
        for (UUID id : defenders) {
            if (which.test(id)) {
                n++;
            }
        }
        for (UUID id : homeward) {                               // walking home after the all-clear: still out of the reserve
            if (which.test(id)) {
                n++;
            }
        }
        return n;
    }

    /** Tests: the defenders' ids. */
    public Set<UUID> defenderIds() {
        return Set.copyOf(defenders);
    }

    /** Tests: the ids of those stood down and still walking home. */
    public Set<UUID> homewardIds() {
        return Set.copyOf(homeward);
    }

    // ------------------------------------------------------------------ out of the world, and back

    /**
     * Each second: off {@code ids} each one in the world that is no settler or is dead (home and sunk, or killed), and each out of the world
     * for more than {@link #MISSING_SECONDS} of the muster's seconds; one out of the world for less is kept, as it comes back with its chunk.
     * A defender that merely went out of the loaded ground mid-alarm (it chased a foe off) was dropped at once, and came back an orphan. Telling
     * an unload from a death or an absorb is {@link #left}'s: it drops those at once, where it can.
     */
    private void prune(ServerLevel level, Set<UUID> ids) {
        for (Iterator<UUID> it = ids.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            Entity e = level.getEntity(id);
            boolean gone;
            if (e != null) {
                missing.remove(id);
                gone = !(e instanceof SettlerMob r) || !r.isAlive();
            } else {
                gone = missing.merge(id, 1, Integer::sum) > MISSING_SECONDS;
            }
            if (gone) {
                it.remove();
                missing.remove(id);
                untold.remove(id);
            }
        }
    }

    /** The homeward not yet told, that are back in the world: told to go home now. */
    private void tellUntold(ServerLevel level) {
        for (Iterator<UUID> it = untold.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (!homeward.contains(id)) {
                it.remove();
            } else if (level.getEntity(id) instanceof SettlerMob r && r.isAlive()) {
                clear(r);
                r.goHome();
                it.remove();
            }
        }
    }

    /** {@code id} is off every list of the muster's: it is gone for good. */
    public void forget(UUID id) {
        defenders.remove(id);
        homeward.remove(id);
        untold.remove(id);
        missing.remove(id);
        buffed.remove(id);
    }

    /**
     * Whether {@code r}, leaving the world now (or in it, asked at a save), comes back: only if it leaves with its chunk (no removal yet, or
     * unloaded to the chunk), and is saved there. A settler killed or discarded (sunk home into the reserve: the race's absorb, then a
     * discard) does not, nor one that changed worlds, nor one its race never saves as a mob (the Waaagh!'s defenders: Greenskin's
     * {@code shouldBeSaved}). A passenger is saved with its vehicle, so its vehicle is asked.
     */
    static boolean comesBack(Entity r) {
        Entity.RemovalReason why = r.getRemovalReason();
        if (why != null && why != Entity.RemovalReason.UNLOADED_TO_CHUNK) {
            return false;
        }
        Entity root = r.getRootVehicle();
        return root.shouldBeSaved();
    }

    /**
     * A settler leaving the world: if it is gone for good ({@link #comesBack}: dead, sunk home, never saved) it is off every loaded settlement's
     * muster now, so the muster neither counts it ({@link #out}) nor keeps it the {@link #MISSING_SECONDS}. A settler out with its chunk is kept.
     * Every loaded one is asked, the settler's own settlement being unloaded first, perhaps.
     */
    @SubscribeEvent
    public static void left(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent e) {
        if (!(e.getEntity() instanceof SettlerMob r) || !(e.getLevel() instanceof ServerLevel level) || comesBack(r)) {
            return;
        }
        UUID id = r.getUUID();
        for (Settlement h : SettlementSpawns.loaded(level)) {
            h.muster().forget(id);
        }
    }

    // ------------------------------------------------------------------ save and load

    /**
     * What the muster needs to resume or stand down after a reload, or null when it has nothing (no alarm, no one out): its defenders and
     * those walking home, by id, whether the alarm is up and broke in, its quiet seconds and its tally. A settler in the world that will not
     * be saved as a mob ({@link #comesBack}) is left out, as it will not be there after the reload; one out of the world is written (it left
     * with its chunk, saved: one that was not was forgotten as it left). {@code level} null writes every id.
     */
    @Nullable
    public CompoundTag save(@Nullable ServerLevel level) {
        ListTag up = ids(level, defenders), home = ids(level, homeward);
        if (up.isEmpty() && home.isEmpty() && !alarmed) {
            return null;
        }
        CompoundTag t = new CompoundTag();
        t.put("defenders", up);
        t.put("homeward", home);
        t.putBoolean("alarmed", alarmed);
        t.putBoolean("broke", broke);
        t.putInt("quiet", quiet);
        t.putInt("slain", slain);
        t.putInt("lost", lost);
        t.putInt("risen", risen);
        return t;
    }

    private static ListTag ids(@Nullable ServerLevel level, Set<UUID> ids) {
        ListTag list = new ListTag();
        for (UUID id : ids) {
            Entity e = level == null ? null : level.getEntity(id);
            if (e == null || (e instanceof SettlerMob r && r.isAlive() && comesBack(r))) {
                list.add(Nbt.uuid(id));
            }
        }
        return list;
    }

    /**
     * The muster as {@link #save} wrote it; an empty tag (an old save, or one with nothing out) is a muster at peace. Defenders loaded back
     * hold the alarm up, so its all-clear comes as it would have ({@link #ALL_CLEAR} quiet seconds, if no foe is left) and stands them down;
     * the homeward are told to go home again once they are back in the world (their walk home is theirs, and not saved).
     */
    public void load(CompoundTag t) {
        defenders.clear();
        homeward.clear();
        untold.clear();
        missing.clear();
        foes.clear();
        buffed.clear();
        for (Tag id : t.getListOrEmpty("defenders")) {
            defenders.add(Nbt.uuid(id));
        }
        for (Tag id : t.getListOrEmpty("homeward")) {
            homeward.add(Nbt.uuid(id));
        }
        untold.addAll(homeward);
        alarmed = t.getBooleanOr("alarmed", false) || !defenders.isEmpty();
        broke = t.getBooleanOr("broke", false);
        quiet = Math.max(0, t.getIntOr("quiet", 0));
        slain = t.getIntOr("slain", 0);
        lost = t.getIntOr("lost", 0);
        risen = t.getIntOr("risen", 0);
    }
}
