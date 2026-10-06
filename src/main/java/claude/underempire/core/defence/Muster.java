package claude.underempire.core.defence;

import claude.underempire.core.unit.Footing;
import claude.underempire.core.unit.SettlerMob;
import claude.underempire.core.settlement.Settlement;
import claude.underempire.core.Core;
import claude.underempire.core.CoreConfig;
import claude.underempire.core.settlement.SettlementSpawns;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.FlyingMob;
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
 * Only where the warren is loaded. Nothing is saved: an alarm ends with the world.
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
    public static final ResourceLocation BUFF = ResourceLocation.fromNamespaceAndPath(Core.NAMESPACE, "defending_the_settlement");

    private final Set<UUID> defenders = new LinkedHashSet<>();
    private final Set<UUID> foes = new HashSet<>();
    private final Set<UUID> buffed = new HashSet<>();
    private boolean alarmed;
    private int quiet;
    /** Tests: the foes it saw and the rats it buffed, last second. */
    public int lastSeen, lastBuffed;
    private int slain, lost, risen;
    private long toldAt = Long.MIN_VALUE / 2;

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
        return defenders.contains(id);
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
            return;
        }
        Map<Integer, Footing.Known> known = new HashMap<>();   // what the floods found about the foes, a body height to a Known
        List<Mob> in = intruders(level, h);
        in.removeIf(m -> shutIn(level, m, known));
        lastSeen = in.size();
        Vec3 heart = Vec3.atCenterOf(h.getBlockPos());
        for (Iterator<UUID> it = foes.iterator(); it.hasNext(); ) {   // the ones a Watch-Post saw, still about the warren
            if (!(level.getEntity(it.next()) instanceof LivingEntity e) || !e.isAlive()) {
                it.remove();
            } else if (e instanceof Mob m && !in.contains(m) && m.distanceToSqr(heart) < 96 * 96) {
                if (shutIn(level, m, known)) {
                    it.remove();                                // shut in since it was seen: no foe, nor one slain when it dies (Jade's count, the tally)
                } else {
                    in.add(m);
                }
            }
        }
        if (!in.isEmpty()) {
            if (!alarmed) {
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
        if (!alarmed) {
            return;
        }
        defenders.removeIf(id -> !(level.getEntity(id) instanceof SettlerMob r) || !r.isAlive());
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
        if (m.isPassenger() || flies(m) || m.isInWaterOrBubble()) {
            return false;
        }
        int height = Math.max(1, (int) Math.ceil(m.getBbHeight() - 0.05));
        BlockPos at = m.blockPosition();
        return Footing.fits(level, at, height)
                && !Footing.escapes(level, at, Footing.LIMIT, known.computeIfAbsent(height, k -> new Footing.Known()), height);
    }

    /** A foe that does not walk: one that flies (a ghast, a phantom, a vex, a bee) or has no gravity. It is in no hollow of the ground's. */
    private static boolean flies(Mob m) {
        return m.isNoGravity() || m instanceof FlyingMob || m.getMoveControl() instanceof FlyingMoveControl
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

    /** The foes in the warren now: in (or just round) its rooms and tunnels, or near the Heart. */
    static List<Mob> intruders(ServerLevel level, Settlement h) {
        Set<Mob> out = new LinkedHashSet<>();
        BoundingBox ring = ring(h);
        List<BoundingBox> boxes = new ArrayList<>(h.settlementBoxes());
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
    static BoundingBox ring(Settlement h) {
        BlockPos c = h.getBlockPos();
        int across = Math.max(0, h.reach());
        int high = Math.min(across, Settlement.HEART_REACH);
        return new BoundingBox(c.getX() - across, c.getY() - high, c.getZ() - across, c.getX() + across, c.getY() + high, c.getZ() + across);
    }

    /**
     * Whether the muster defends {@code p}: it is in the settlement, or within the settlement's {@link Settlement#reach} of its heart ({@code ring}, from
     * {@link #ring}). The one rule for what counts as a foe, where a defender comes up and who takes heart from the fight.
     */
    static boolean holds(Settlement h, BoundingBox ring, BlockPos p) {
        return h.inSettlement(p) || ring.isInside(p);
    }

    // ------------------------------------------------------------------ the horde rises

    private void rise(ServerLevel level, Settlement h, List<Mob> in) {
        int want = Math.min(max(), perFoe() * in.size());
        int alive = defenders.size();
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
            double d = (clanrat ? 6 : 3) + level.random.nextDouble() * 4 + t / 12.0;
            Vec3 p = from.add(dir.scale(d)).add((level.random.nextDouble() - 0.5) * 6, 0, (level.random.nextDouble() - 0.5) * 6);
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
        h.tellOwner(attackedLine(h.musterWords(), count(in), where, in.size() == 1), ChatFormatting.RED);
    }

    /**
     * The alarm's line to the owner, in the settlement's words ({@link Settlement#musterWords}): {@code foes} are what it is in ("5 Zombies and a Pillager"),
     * {@code where} the room they are in (none: in no room), {@code one} whether there is the one of them. Public for the engine's tests.
     */
    public static String attackedLine(Settlement.MusterWords w, String foes, String where, boolean one) {
        return "The " + w.place() + " is attacked! " + claude.underempire.core.Words.capital(foes)
                + (where.isEmpty() ? " in " + w.ground() : " in the " + where)
                + ": " + w.rises() + " to meet " + (one ? "it." : "them.");
    }

    /**
     * The all-clear's line to the owner, in the settlement's words: {@code slain} foes slain, {@code lost} of its own lost, and {@code home} of its
     * defenders stood down (none: no more said). Public for the engine's tests.
     */
    public static String clearLine(Settlement.MusterWords w, int slain, int lost, int home) {
        return "The " + w.place() + " is clear: " + slain + (slain == 1 ? " foe" : " foes") + " slain, " + lost + " of ours lost."
                + (home > 0 ? " " + claude.underempire.core.Words.capital(claude.underempire.core.Words.count(home, "defender"))
                + " " + (home == 1 ? w.verb() + "s" : w.verb()) + " " + w.back() + "." : "");
    }

    private void standDown(ServerLevel level, Settlement h) {
        int home = 0;
        for (UUID id : defenders) {
            if (level.getEntity(id) instanceof SettlerMob r && r.isAlive()) {
                clear(r);
                r.goHome();                                      // back down into the horde
                home++;
            }
        }
        for (UUID id : buffed) {
            if (level.getEntity(id) instanceof SettlerMob r) {
                clear(r);
            }
        }
        h.tellOwner(clearLine(h.musterWords(), slain, lost, home), ChatFormatting.GREEN);
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
        return claude.underempire.core.Words.list(n);
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

    /** Tests: the defenders' ids. */
    public Set<UUID> defenderIds() {
        return Set.copyOf(defenders);
    }
}
