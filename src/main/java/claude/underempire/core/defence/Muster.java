package claude.underempire.core.defence;

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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
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
import java.util.HashSet;
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

    /** A Watch-Post's gong: foes it saw, in the warren or about it. */
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
        List<Mob> in = intruders(level, h);
        lastSeen = in.size();
        for (UUID id : foes) {                                  // the ones a Watch-Post saw, still about the warren
            if (level.getEntity(id) instanceof Mob m && m.isAlive() && !in.contains(m)
                    && m.distanceToSqr(Vec3.atCenterOf(h.getBlockPos())) < 96 * 96) {
                in.add(m);
            }
        }
        foes.removeIf(id -> !(level.getEntity(id) instanceof LivingEntity e) || !e.isAlive());
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
            out.addAll(level.getEntitiesOfClass(Mob.class, area, m -> foe(m) && defends(h, ring, m.blockPosition())));
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
    static boolean defends(Settlement h, BoundingBox ring, BlockPos p) {
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
     * ground under cover (unless the settlement {@link Settlement#mustersUnderSky musters under the sky}: a surface race's defenders
     * stand in the open, at the first cell found, there being no tunnel to look on for), a tunnel's narrow floor before a room's.
     * Null if there is nowhere. Public for the engine's tests.
     */
    @Nullable
    public static BlockPos cell(ServerLevel level, Settlement h, Mob head, boolean clanrat) {
        Vec3 from = head.position();
        Vec3 dir = Vec3.atCenterOf(h.getBlockPos()).subtract(from);
        dir = dir.lengthSqr() < 1 ? new Vec3(1, 0, 0) : dir.normalize();
        BoundingBox ring = ring(h);
        boolean sky = h.mustersUnderSky();
        BlockPos open = null;
        for (int t = 0; t < 48; t++) {
            double d = (clanrat ? 6 : 3) + level.random.nextDouble() * 4 + t / 12.0;
            Vec3 p = from.add(dir.scale(d)).add((level.random.nextDouble() - 0.5) * 6, 0, (level.random.nextDouble() - 0.5) * 6);
            BlockPos base = BlockPos.containing(p);
            for (int dy = 0; dy <= 6; dy++) {
                for (int s : new int[]{dy, -dy}) {
                    BlockPos q = base.above(s);
                    if (!level.isLoaded(q) || !defends(h, ring, q) || !SettlerMob.standable(level, q)
                            || (!sky && SettlerMob.underOpenSky(level, q))) {
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

    /** A tunnel no wider than {@link #TIGHT} across one way or the other, at {@code p}. */
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
                if (!defends(h, ring, r.blockPosition())) {
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
        h.tellOwner("The warren is attacked! " + claude.underempire.core.Words.capital(count(in))
                + (where.isEmpty() ? " in the tunnels" : " in the " + where)
                + ": the horde rises to meet " + (in.size() == 1 ? "it." : "them."), ChatFormatting.RED);
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
        h.tellOwner("The warren is clear: " + slain + (slain == 1 ? " foe" : " foes") + " slain, " + lost + " of ours lost."
                + (home > 0 ? " " + claude.underempire.core.Words.capital(claude.underempire.core.Words.count(home, "defender"))
                + (home == 1 ? " sinks" : " sink") + " back into the tunnels." : ""), ChatFormatting.GREEN);
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
