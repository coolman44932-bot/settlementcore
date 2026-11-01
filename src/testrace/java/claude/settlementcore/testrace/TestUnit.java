package claude.settlementcore.testrace;

import claude.settlementcore.defence.Muster;
import claude.settlementcore.room.Clearance;
import claude.settlementcore.settlement.Settlement;
import claude.settlementcore.unit.CrewGoal;
import claude.settlementcore.unit.SettlerMob;
import com.geckolib.animatable.GeoEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.util.GeckoLibUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.stream.Collectors;

/**
 * What both the test race's units are (the template a race's own unit base copies; the Great Plan's LizardmanUnit is the full-sized
 * one): bound to a {@link HeartBlockEntity}, crewing its rooms with the engine's {@link CrewGoal}, fighting what the engine calls a foe,
 * and going into and out of the world by sinking into the ground ({@value #SINK_TICKS} ticks, unhurt meanwhile). Its owner's right-click
 * cycles its standing order (work, follow, hold). It is never broken and never turns rogue: courage is the race's to add.
 */
public abstract class TestUnit extends SettlerMob implements GeoEntity {

    private static final EntityDataAccessor<Integer> DATA_MODE = SynchedEntityData.defineId(TestUnit.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_DIGGING = SynchedEntityData.defineId(TestUnit.class, EntityDataSerializers.BOOLEAN);
    /** >0 rising (ticks left), <0 sinking, 0 neither. */
    private static final EntityDataAccessor<Integer> DATA_SINK = SynchedEntityData.defineId(TestUnit.class, EntityDataSerializers.INT);

    public static final int SINK_TICKS = 16;
    /** Ticks a unit sent home has to walk to its Heart before it sinks where it stands. */
    public static final int HOMEWARD_TICKS = 200;
    /** How far from its Heart it walks home; further, it sinks at once. */
    public static final double WALK_HOME = 32;

    static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.testrace.idle");
    static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.testrace.walk");
    static final RawAnimation WORK = RawAnimation.begin().thenLoop("animation.testrace.work");
    /** A blow (a melee swing): once, rest to rest, the blow landing at 0.25 s. */
    static final RawAnimation ATTACK = RawAnimation.begin().thenPlay("animation.testrace.attack");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    @Nullable private BlockPos heartPos;
    @Nullable private BlockPos crewRoom;
    @Nullable private BlockPos guardPos;
    @Nullable private Vec3 riseAt;
    private boolean homeward;
    private long homeBy;

    protected TestUnit(EntityType<? extends TestUnit> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    protected static AttributeSupplier.Builder baseAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 16.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.FOLLOW_RANGE, 20.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(DATA_MODE, Mode.WORK.ordinal());
        b.define(DATA_DIGGING, false);
        b.define(DATA_SINK, 0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new TRGoals.Homeward(this));
        goalSelector.addGoal(2, new CrewGoal(this));
        goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.15, false));
        goalSelector.addGoal(4, new TRGoals.FollowOwner(this));
        goalSelector.addGoal(4, new TRGoals.Hold(this));
        goalSelector.addGoal(5, new CrewGoal.Idle(this));
        goalSelector.addGoal(6, new TRGoals.StayNearHeart(this));
        goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8f));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this, TestUnit.class).setAlertOthers(TestUnit.class));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Mob.class, 10, true, false, (m, level) -> m instanceof Mob mob && Muster.foe(mob)));
    }

    // ------------------------------------------------------------------ its Heart

    @Override
    public @Nullable HeartBlockEntity settlement() {
        return heartPos != null && level() instanceof ServerLevel sl && sl.isLoaded(heartPos)
                && sl.getBlockEntity(heartPos) instanceof HeartBlockEntity h ? h : null;
    }

    @Override
    public void bindTo(Settlement settlement) {
        heartPos = settlement.getBlockPos();
    }

    public @Nullable BlockPos heartPos() {
        return heartPos;
    }

    // ------------------------------------------------------------------ orders

    @Override
    public Mode getMode() {
        return Mode.values()[Math.floorMod(entityData.get(DATA_MODE), Mode.values().length)];
    }

    @Override
    public void setMode(Mode m) {
        entityData.set(DATA_MODE, m.ordinal());
        guardPos = m == Mode.GUARD ? blockPosition() : null;
    }

    public @Nullable BlockPos guardPos() {
        return guardPos;
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        HeartBlockEntity h = settlement();
        if (hand != InteractionHand.MAIN_HAND || h == null || !player.getUUID().equals(h.owner()) || isCrew()) {
            return super.mobInteract(player, hand);
        }
        if (!level().isClientSide()) {
            setMode(getMode().next());
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(getName().getString() + ": " + getMode().order()));
        }
        return InteractionResult.SUCCESS;
    }

    // ------------------------------------------------------------------ what the engine asks of it

    @Override
    public float tempo() {
        return 0.9f + Math.floorMod(getUUID().hashCode(), 31) / 100f;
    }

    @Override
    public int animLag() {
        return 2 + Math.floorMod(getUUID().hashCode() >> 5, 7);
    }

    @Override
    public boolean isCrew() {
        return crewRoom != null;
    }

    @Override
    public void makeCrew(BlockPos room) {
        crewRoom = room;
        setMode(Mode.WORK);
    }

    @Override
    public @Nullable BlockPos crewRoom() {
        return crewRoom;
    }

    @Override
    public ItemStack carried() {
        return isCrew() ? getMainHandItem() : ItemStack.EMPTY;
    }

    @Override
    public void carry(ItemStack load) {
        setItemSlot(EquipmentSlot.MAINHAND, load.isEmpty() ? ItemStack.EMPTY : load);
        setDropChance(EquipmentSlot.MAINHAND, load.isEmpty() ? 0f : 2f);
    }

    @Override
    public boolean isDigging() {
        return entityData.get(DATA_DIGGING);
    }

    @Override
    public void setDigging(boolean d) {
        entityData.set(DATA_DIGGING, d);
    }

    @Override
    public boolean isBroken() {
        return false;
    }

    @Override
    public boolean isRogue() {
        return false;
    }

    @Override
    public void hearten(float amount) {
    }

    @Override
    public String runningGoals() {
        return goalSelector.getAvailableGoals().stream().filter(g -> g.isRunning())
                .map(g -> g.getGoal().getClass().getSimpleName()).collect(Collectors.joining(", "));
    }

    /** A crew settler is never saved: its Heart counts it and puts it back when it is gone from sight. */
    @Override
    public boolean shouldBeSaved() {
        return !isCrew() && super.shouldBeSaved();
    }

    // ------------------------------------------------------------------ into and out of the ground

    @Override
    public boolean isBurrowing() {
        return entityData.get(DATA_SINK) != 0;
    }

    /** Ticks of the sink left: >0 rising, <0 sinking (synced: the model sinks and rises with it). */
    public int sinkPhase() {
        return entityData.get(DATA_SINK);
    }

    @Override
    public void emergeAt(double x, double y, double z) {
        riseAt = null;
        teleportTo(x, y, z);
        getNavigation().stop();
        entityData.set(DATA_SINK, SINK_TICKS);
    }

    @Override
    public void burrowTo(double x, double y, double z) {
        riseAt = new Vec3(x, y, z);
        sink();
    }

    private void sink() {
        setTarget(null);
        getNavigation().stop();
        entityData.set(DATA_SINK, -SINK_TICKS);
    }

    /** Home: it walks to its Heart first ({@link TRGoals.Homeward}), then sinks into the ground beside it. Far off, it sinks at once. */
    @Override
    public void goHome() {
        if (homeward) {
            return;
        }
        homeward = true;
        homeBy = level().getGameTime() + HOMEWARD_TICKS;
        setTarget(null);
        setDigging(false);
        HeartBlockEntity h = level().isClientSide() ? null : settlement();
        if (h == null || distanceToSqr(Vec3.atCenterOf(h.getBlockPos())) > WALK_HOME * WALK_HOME) {
            sink();
        }
    }

    @Override
    public boolean isHomeward() {
        return homeward;
    }

    long homeBy() {
        return homeBy;
    }

    /** At its Heart (or out of time): down it goes. */
    void sinkHome() {
        if (entityData.get(DATA_SINK) >= 0) {
            sink();
        }
    }

    @Override
    public void aiStep() {
        if (!level().isClientSide() && isBurrowing()) {
            tickSink();
            return;
        }
        super.aiStep();
    }

    private void tickSink() {
        setDeltaMovement(Vec3.ZERO);
        int s = entityData.get(DATA_SINK);
        boolean rising = s > 0;
        if (level() instanceof ServerLevel sl && (tickCount & 3) == 0) {
            sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, getX(), getY() + 0.2, getZ(), 2, 0.3, 0.1, 0.3, 0.01);
        }
        s += rising ? -1 : 1;
        entityData.set(DATA_SINK, s);
        if (s != 0) {
            return;
        }
        if (rising) {
            if (level() instanceof ServerLevel sl) {
                Vec3 clear = Clearance.clearOf(sl, this);         // built in while it rose: beside it, not in it
                if (clear != null) {
                    setPos(clear.x, clear.y, clear.z);
                }
            }
        } else if (homeward) {
            HeartBlockEntity h = settlement();
            if (h != null) {
                h.absorb(this);
            }
            discard();
        } else if (riseAt != null) {
            emergeAt(riseAt.x, riseAt.y, riseAt.z);
        }
    }

    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
        return isBurrowing() && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY) || super.isInvulnerableTo(level, source);
    }

    @Override
    public void die(DamageSource source) {
        HeartBlockEntity h = settlement();
        if (h != null) {
            h.removePerson(getUUID());
            h.works().forget(getUUID());
        }
        super.die(source);
    }

    // ------------------------------------------------------------------ saves

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.storeNullable("heart", BlockPos.CODEC, heartPos);
        out.storeNullable("guard_pos", BlockPos.CODEC, guardPos);
        out.putInt("mode", getMode().ordinal());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        heartPos = in.read("heart", BlockPos.CODEC).orElse(null);
        entityData.set(DATA_MODE, in.getIntOr("mode", Mode.WORK.ordinal()));
        guardPos = in.read("guard_pos", BlockPos.CODEC).orElse(null);
    }

    @Override
    public boolean removeWhenFarAway(double dist) {
        return false;
    }

    // ------------------------------------------------------------------ its look

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<TestUnit>("main", 4, test -> {
            TestUnit u = test.animatable();
            if (u.swinging) {
                return test.setAndContinue(ATTACK);              // the swing is synced to clients by vanilla's animate packet
            }
            if (u.isDigging()) {
                return test.setAndContinue(WORK);
            }
            return test.setAndContinue(test.isMoving() ? WALK : IDLE);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
