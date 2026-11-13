package claude.settlementcore.test;

import net.minecraft.world.Difficulty;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The world a batch's tests run in, set as the batch starts (before its {@link BeforeBatch} hooks) and put back as its last test
 * is done (after its {@link AfterBatch} hooks): time of day, the daylight cycle, weather, natural mob spawning, difficulty,
 * keepInventory, the random tick speed and the simulation distance. On any method or on the class of a {@link GameTestHolder}
 * (several may stand on one, one per batch); {@link GameTests#register} reads them, and {@link WorldConditions} applies them.
 * Every field left at its default leaves the world as it is.
 *
 * <pre>{@code
 * @BatchWorld(batch = "night", time = 18000, daylightCycle = BatchWorld.Rule.OFF, mobSpawning = BatchWorld.Rule.ON,
 *         difficulty = Difficulty.HARD)
 * }</pre>
 *
 * Two declarations for one batch must agree, or registering fails. Give a batch with a world of its own its own tests: every test
 * of the batch shares it (the GameTest server runs a batch's tests side by side in one level).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Repeatable(BatchWorld.All.class)
public @interface BatchWorld {

    /** The batch, as {@link GameTest#batch} names it, in the holder's namespace. */
    String batch();

    /** The overworld clock's ticks (0 sunrise, 6000 noon, 13000 dusk, 18000 midnight); -1 leaves it. */
    long time() default -1;

    /** advance_time (the daylight cycle). */
    Rule daylightCycle() default Rule.LEAVE;

    /** The weather; none leaves it. ADVANCE_WEATHER stays as the GameTest server keeps it (off). */
    Weather[] weather() default {};

    /**
     * spawn_mobs: natural spawning (the GameTest server turns it off for every batch). ON also turns spawn_monsters on, and the
     * chunk caches are told at once (MinecraftServer#updateMobSpawningFlags), as the /gamerule command alone would not for
     * spawn_mobs.
     */
    Rule mobSpawning() default Rule.LEAVE;

    /** The difficulty; none leaves it (the GameTest server's is Normal). */
    Difficulty[] difficulty() default {};

    /** keep_inventory. */
    Rule keepInventory() default Rule.LEAVE;

    /** random_tick_speed; -1 leaves it. */
    int randomTickSpeed() default -1;

    /**
     * The server's simulation distance in chunks (how far round a player chunks tick); -1 leaves it. The GameTest server never
     * sets one, so its player list says 0 and a player ticks only his own chunk's entities (blocks one chunk further); a real
     * server says 10, a single-player world 12. See {@link PlayerLoadedChunks}.
     */
    int simulationDistance() default -1;

    enum Rule {
        LEAVE, ON, OFF
    }

    enum Weather {
        CLEAR, RAIN, THUNDER
    }

    /** The container of several {@link BatchWorld}s on one element (one per batch). */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    @interface All {
        BatchWorld[] value();
    }
}
