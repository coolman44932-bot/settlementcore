package claude.settlementcore.test;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Makes a test's ground behave as in real play, where only the chunks round a player tick. The GameTest runner forces every chunk of
 * a test's structure for the whole batch (TestInstanceBlockEntity#forceLoadChunks), so in a test everything on the plot ticks
 * whether a player is near or not; in a real world a room out of the player's simulation distance stands still. {@link #release}
 * lets go of the test's forced chunks, all but those within {@code keep} chunks of the god: from then on what ticks is what his own
 * player tickets keep ticking (the server's simulation distance round him: entities in range, block entities and random ticks one
 * chunk further), and the rest unloads.
 *
 * <ul>
 * <li>The test keeps running: a GameTest checks its chunks only until it starts (GameTestInfo#tick's chunksLoaded gate), so release
 * from the test's own body or later, never from a {@code @BeforeBatch}. A block read in a released chunk loads it again for the
 * read, not ticking (26.1 loads a chunk on any read from the server thread); ask {@link #blockTicking} / {@link #entityTicking}
 * instead of looking.
 * <li>Never a chunk of another test that is not done (one that has not started would never start, and the watchdog would fail
 * it for that): those stay forced, and {@link Released#shared()} lists them. Give a test that releases its chunks its own batch.
 * <li>The watchdog is told the un-forcing is meant ({@link TestWatchdog#deliberate}), so it neither warns of it nor blames anyone.
 * <li>When the test ends, pass or fail, the chunks are forced again, as the runner left them; it un-forces every chunk as the
 * batch ends, as always. {@link Released#reforce()} does it sooner.
 * </ul>
 *
 * The GameTest server never sets a simulation distance, so its player list says 0: a player's own chunk is entity-ticking, the
 * ones next to it block-ticking, and no further (a real server says 10, single player 12). Set one for the batch with
 * {@link BatchWorld#simulationDistance()}.
 */
public final class PlayerLoadedChunks {

    private static final Logger LOG = LogUtils.getLogger();
    /** Releases whose test has not ended: a batch's end forgets them (the runner un-forces every chunk then). */
    private static final List<Released> OPEN = new ArrayList<>();

    static {
        // The batch's last test: the runner's own listener on it ends the batch (un-forcing every chunk, placing the next batch)
        // before a listener added by the test is told, so a re-force from there would hold the chunks for good. As the batch ends
        // (before the runner's un-forcing) every open release is closed without forcing anything.
        TestWatchdog.afterEachBatch(level -> {
            OPEN.forEach(r -> r.reforced = true);
            OPEN.clear();
        });
    }

    private PlayerLoadedChunks() {
    }

    /** Lets go of the test's forced chunks except those within {@code keep} chunks (square) of the god; {@code keep} -1 keeps none. */
    public static Released release(GameTestHelper h, SurvivalGod god, int keep) {
        ServerLevel level = h.getLevel();
        ChunkPos at = ChunkPos.containing(god.player().blockPosition());
        Set<ChunkPos> mine = chunks(h.testInfo);
        Set<ChunkPos> others = new LinkedHashSet<>();
        for (GameTestInfo t : TestWatchdog.liveTests()) {
            if (t != h.testInfo) {
                others.addAll(chunks(t));
            }
        }
        Released r = new Released(level);
        for (ChunkPos c : mine) {
            if (Math.max(Math.abs(c.x() - at.x()), Math.abs(c.z() - at.z())) <= keep) {
                r.kept.add(c);
            } else if (others.contains(c)) {
                r.shared.add(c);
            } else if (level.getForceLoadedChunks().contains(c.pack())) {
                TestWatchdog.deliberate(c.pack(), true);
                level.setChunkForced(c.x(), c.z(), false);
                r.released.add(c);
            }
        }
        OPEN.add(r);
        h.testInfo.addListener(new Reforce(r));
        LOG.info("PlayerLoadedChunks: {} released {} of its chunks (kept {} within {} of the god at chunk {}, {} shared with other tests); "
                + "simulation distance {}", h.testInfo.id(), r.released.size(), r.kept.size(), keep, at, r.shared.size(), simulationDistance(level));
        return r;
    }

    /** The chunks the GameTest runner forced for a test: its structure's. */
    static Set<ChunkPos> chunks(GameTestInfo t) {
        BoundingBox box = TestWatchdog.structureBox(t);
        Set<ChunkPos> out = new LinkedHashSet<>();
        if (box != null) {
            box.intersectingChunks().forEach(out::add);
        }
        return out;
    }

    /** Whether blocks at {@code pos} (test-relative) tick: block entities, scheduled and random ticks. */
    public static boolean blockTicking(GameTestHelper h, BlockPos pos) {
        return h.getLevel().shouldTickBlocksAt(h.absolutePos(pos));
    }

    /** Whether entities at {@code pos} (test-relative) tick. */
    public static boolean entityTicking(GameTestHelper h, BlockPos pos) {
        return h.getLevel().isPositionEntityTicking(h.absolutePos(pos));
    }

    /** The server's simulation distance, in chunks (the GameTest server's is 0 unless a batch sets one). */
    public static int simulationDistance(ServerLevel level) {
        return level.getServer().getPlayerList().getSimulationDistance();
    }

    /** What {@link #release} let go of. */
    public static final class Released {
        private final ServerLevel level;
        final List<ChunkPos> released = new ArrayList<>();
        final List<ChunkPos> kept = new ArrayList<>();
        final List<ChunkPos> shared = new ArrayList<>();
        private boolean reforced;

        Released(ServerLevel level) {
            this.level = level;
        }

        /** The chunks no longer forced. */
        public List<ChunkPos> released() {
            return List.copyOf(released);
        }

        /** The chunks kept forced, near the god. */
        public List<ChunkPos> kept() {
            return List.copyOf(kept);
        }

        /** The chunks kept forced because another test not done stands on them too. */
        public List<ChunkPos> shared() {
            return List.copyOf(shared);
        }

        public boolean isReleased(BlockPos abs) {
            return released.contains(ChunkPos.containing(abs));
        }

        /**
         * Forces the released chunks again. The test's end does it by itself while its batch runs on; once the batch is over the
         * runner has let go of every chunk and nothing is forced again.
         */
        public void reforce() {
            if (reforced) {
                return;
            }
            reforced = true;
            OPEN.remove(this);
            for (ChunkPos c : released) {
                level.setChunkForced(c.x(), c.z(), true);
                TestWatchdog.deliberate(c.pack(), false);
            }
        }
    }

    private record Reforce(Released r) implements GameTestListener {
        @Override
        public void testStructureLoaded(GameTestInfo info) {
        }

        @Override
        public void testPassed(GameTestInfo info, GameTestRunner runner) {
            r.reforce();
        }

        @Override
        public void testFailed(GameTestInfo info, GameTestRunner runner) {
            r.reforce();
        }

        @Override
        public void testAddedForRerun(GameTestInfo original, GameTestInfo copy, GameTestRunner runner) {
            r.reforce();
        }
    }
}
