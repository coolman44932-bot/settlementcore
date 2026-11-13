package claude.settlementcore.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A GameTest, as 1.21.1 wrote one: a {@code public static void} method taking a {@code GameTestHelper}, in a class marked
 * {@link GameTestHolder}. Since 1.21.5 Minecraft's tests are registry entries (a test function, an instance naming its
 * structure and limits, and an environment that is its batch) with no annotations of their own; {@link GameTests} reads these
 * and registers them, so a test is still written where it is read.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GameTest {

    /** The structure the test stands in, in its holder's namespace (settlementcore:digsite). */
    String template();

    /** Ticks before it fails for having neither passed nor failed. */
    int timeoutTicks() default 100;

    /** Its batch: the tests of one batch run side by side, and {@link BeforeBatch} and {@link AfterBatch} hooks run around them. */
    String batch() default "defaultbatch";

    /** Ticks after the structure is placed before the test starts. */
    int setupTicks() default 0;

    /** A test that is not required may fail without failing the run. */
    boolean required() default true;

    /** How many times it may run, and how many of those must pass. */
    int attempts() default 1;

    int requiredSuccesses() default 1;

    /**
     * Whether the sky is open over it. 1.21.1 put no roof over a test; 26.1 puts one of barriers over every test that does not
     * ask for the sky, and the engine's tests were written in the open (daylight, rain, what canSeeSky says).
     */
    boolean skyAccess() default true;

    /**
     * Mod ids that must all be loaded for the test to be registered at all (a cross-mod test, run only in an all-mods run). With
     * one missing the test is not registered, so it neither runs nor fails; {@link GameTests#register} logs it at debug.
     */
    String[] requires() default {};
}
