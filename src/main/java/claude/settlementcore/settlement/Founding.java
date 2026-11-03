package claude.settlementcore.settlement;

import claude.settlementcore.Core;
import claude.settlementcore.room.PlanPlacement;
import claude.settlementcore.room.RaiseJob;
import claude.settlementcore.room.RoomShoring;
import claude.settlementcore.room.Rooms;
import claude.settlementcore.room.Siting;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Founding halls (the user, 2026-11-02: "a heart hall, basically you can place that first, a defensive position that counts as your first
 * building then you can place the heart"). A settlement's heart had to be set down first, out in the open, and everything grew round
 * wherever it fell. Now a race's founding hall comes first: a raised building with a {@value #HEART} station (where its heart stands), set
 * out from a plan by its god before any settlement exists. Nobody is there to build it, so the god raises it, the site's own cells in order
 * ({@value #PER_SECOND} a second, the land levelled and ramped as any raised building's), dust rising as it goes. Then its heart is set down
 * on that spot ({@link #claim}), and the settlement is born owning the hall as its first building: its walls are the settlement's walls
 * if they close a yard (Walls). Or the hall is raised round a heart that already stands ({@link #beginRound}), which then has it.
 *
 * <p>Kept per level (SavedData): the halls rising, their jobs saved, and the halls risen waiting for their heart.
 */
@EventBusSubscriber(modid = Core.MODID)
public final class Founding extends SavedData {

    /** The station role of a founding hall's heart spot. */
    public static final String HEART = "heart";
    public static final int PER_SECOND = 300;

    /** A hall rising or risen: its room, door and way; its owner; its job while it rises (else empty); the heart it is raised round, if any. */
    record Hall(String room, BlockPos door, Direction facing, UUID owner, Optional<CompoundTag> job, Optional<BlockPos> heart) {
        static final Codec<Hall> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("room").forGetter(Hall::room),
                BlockPos.CODEC.fieldOf("door").forGetter(Hall::door),
                Direction.CODEC.fieldOf("facing").forGetter(Hall::facing),
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Hall::owner),
                CompoundTag.CODEC.optionalFieldOf("job").forGetter(Hall::job),
                BlockPos.CODEC.optionalFieldOf("heart").forGetter(Hall::heart)).apply(i, Hall::new));

        PlanPlacement placement() {
            return PlanPlacement.compute(Rooms.get(room), door, facing);
        }
    }

    static final Codec<Founding> CODEC = Hall.CODEC.listOf().xmap(Founding::new, f -> f.halls);
    static final SavedDataType<Founding> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(Core.MODID, "founding"), Founding::new, CODEC);

    private final List<Hall> halls;
    /** The jobs of the halls rising, loaded once (not saved here: each saves into its hall's tag). */
    private final java.util.Map<BlockPos, RaiseJob> jobs = new java.util.HashMap<>();

    Founding() {
        this(new ArrayList<>());
    }

    Founding(List<Hall> halls) {
        this.halls = new ArrayList<>(halls);
    }

    public static Founding of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    /** Whether {@code room} is a founding hall: a raised building with a heart spot. */
    public static boolean isFoundingHall(@Nullable Rooms.Room room) {
        return room != null && Rooms.isRaised(room.id()) && heartLocal(room) != null;
    }

    @Nullable
    static BlockPos heartLocal(Rooms.Room room) {
        for (Rooms.Station s : room.stations()) {
            if (HEART.equals(s.role())) {
                return new BlockPos(s.x(), s.y(), s.z());
            }
        }
        return null;
    }

    /** Where a founding hall placed at {@code pl} has its heart spot in the world. */
    public static BlockPos heartSpot(PlanPlacement pl) {
        return pl.world(heartLocal(pl.room()));
    }

    /** The placement of {@code room}, running toward {@code facing}, whose heart spot is {@code heart}. */
    public static PlanPlacement roundHeart(Rooms.Room room, BlockPos heart, Direction facing) {
        PlanPlacement at0 = PlanPlacement.compute(room, BlockPos.ZERO, facing);
        BlockPos shift = heart.subtract(at0.world(heartLocal(room)));
        return PlanPlacement.compute(room, shift, facing);
    }

    /**
     * Begins raising the founding hall at {@code pl} for {@code owner} (its god): sited as any raised building (the land levelled and
     * ramped; refused where it can't be), then raised by the god. Returns why it can't be, or null when begun.
     */
    @Nullable
    public String begin(ServerLevel level, PlanPlacement pl, UUID owner) {
        return begin(level, pl, owner, null);
    }

    /** {@link #begin}, round the heart that stands at {@code heart} (left as it is), the hall that settlement's when it stands. */
    @Nullable
    public String beginRound(ServerLevel level, Rooms.Room room, BlockPos heart, Direction facing, UUID owner) {
        return begin(level, roundHeart(room, heart, facing), owner, heart);
    }

    @Nullable
    private String begin(ServerLevel level, PlanPlacement pl, UUID owner, @Nullable BlockPos heart) {
        if (!isFoundingHall(pl.room())) {
            return Settlement.the(pl.room().displayName()) + " is no founding hall";
        }
        for (Hall h : halls) {
            if (h.placement().box().intersects(pl.box())) {
                return "Another hall is founded there already";
            }
        }
        Siting.Result sited = Siting.plan(level, pl, RoomShoring.Warren.alone(), true,
                heart == null ? it.unimi.dsi.fastutil.longs.LongSets.EMPTY_SET : it.unimi.dsi.fastutil.longs.LongSets.singleton(heart.asLong()));
        if (sited instanceof Siting.Refusal no) {
            return no.why();
        }
        RaiseJob job = new RaiseJob(pl.room().id(), pl.door(), pl.facing(), (Siting.Plan) sited, 0);
        job.ensureLoaded(level);
        jobs.put(pl.door(), job);
        halls.add(new Hall(pl.room().id(), pl.door(), pl.facing(), owner, Optional.of(job.save()), Optional.ofNullable(heart)));
        setDirty();
        return null;
    }

    /** Whether a heart set down at {@code pos} by {@code owner} stands on the heart spot of a hall of theirs that has risen and waits for one. */
    public boolean spot(BlockPos pos, UUID owner) {
        for (Hall h : halls) {
            if (h.job().isEmpty() && h.heart().isEmpty() && h.owner().equals(owner) && heartSpot(h.placement()).equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /** A heart set down at {@code pos} by {@code owner} on a risen hall's spot takes the hall: it's returned (to add) and forgotten here. */
    @Nullable
    public Building claim(BlockPos pos, UUID owner) {
        for (Iterator<Hall> it = halls.iterator(); it.hasNext(); ) {
            Hall h = it.next();
            if (h.job().isEmpty() && h.heart().isEmpty() && h.owner().equals(owner) && heartSpot(h.placement()).equals(pos)) {
                it.remove();
                setDirty();
                PlanPlacement pl = h.placement();
                return new Building(h.room(), pl.box(), h.door(), h.facing());
            }
        }
        return null;
    }

    /** The halls rising or risen and waiting (the tests, the bridge). */
    public int count() {
        return halls.size();
    }

    /** Whether the hall at {@code door} is still rising. */
    public boolean rising(BlockPos door) {
        for (Hall h : halls) {
            if (h.door().equals(door)) {
                return h.job().isPresent();
            }
        }
        return false;
    }

    /** A second's raising of every hall rising in {@code level}: {@code budget} cells each, the risen ones handed on. */
    public void second(ServerLevel level, int budget) {
        for (int i = 0; i < halls.size(); i++) {
            Hall h = halls.get(i);
            if (h.job().isEmpty() || !level.isLoaded(h.door())) {
                continue;
            }
            RaiseJob job = jobs.computeIfAbsent(h.door(), d -> RaiseJob.load(h.job().get()));
            if (job == null) {
                halls.set(i, new Hall(h.room(), h.door(), h.facing(), h.owner(), Optional.empty(), h.heart()));
                continue;
            }
            job.ensureLoaded(level);
            List<BlockPos> sample = new ArrayList<>();
            int n = job.raiseInOrder(level, budget, sample);   // the god's: in order, nothing held back for its readiness
            if (n == 0 && !job.isDone()) {
                job.heaveFront(level);                           // a cell its kind of job holds back: the god sets it in anyway
            }
            for (BlockPos p : sample) {
                level.sendParticles(ParticleTypes.CLOUD, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 4, 0.4, 0.4, 0.4, 0.02);
            }
            if (!sample.isEmpty()) {
                level.playSound(null, sample.get(0), SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1.5f, 0.7f);
            }
            if (job.isDone()) {
                job.finishAll(level);
                jobs.remove(h.door());
                risen(level, h);
                if (h.heart().isPresent()) {
                    halls.remove(i--);                           // raised round a heart: that settlement has it now
                } else {
                    halls.set(i, new Hall(h.room(), h.door(), h.facing(), h.owner(), Optional.empty(), Optional.empty()));
                }
            } else {
                halls.set(i, new Hall(h.room(), h.door(), h.facing(), h.owner(), Optional.of(job.save()), h.heart()));
            }
            setDirty();
        }
    }

    /** A hall has risen: round a heart, that settlement takes it; else its god is told to set the heart on its spot. */
    private static void risen(ServerLevel level, Hall h) {
        PlanPlacement pl = h.placement();
        String the = Settlement.the(pl.room().displayName());
        if (h.heart().isPresent() && level.getBlockEntity(h.heart().get()) instanceof Settlement s) {
            s.addBuilding(new Building(h.room(), pl.box(), h.door(), h.facing()), true);
            s.tellOwner(cap(the) + " stands round your " + s.centreName() + ".", ChatFormatting.GOLD);
            return;
        }
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(h.owner());
        if (p != null) {
            BlockPos at = heartSpot(pl);
            p.sendSystemMessage(Component.literal(cap(the) + " stands. Set your heart on its spot, at " + at.getX() + " " + at.getY()
                    + " " + at.getZ() + ".").withStyle(ChatFormatting.GOLD));
        }
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @SubscribeEvent
    static void tick(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % 20 != 0) {
            return;
        }
        for (ServerLevel level : e.getServer().getAllLevels()) {
            Founding f = level.getDataStorage().get(TYPE);
            if (f != null && !f.halls.isEmpty()) {
                f.second(level, PER_SECOND);
            }
        }
    }
}
