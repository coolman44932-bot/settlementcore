package claude.underempire.core.room;

import java.util.Set;

/**
 * Benchmark switches (./gradlew runGameTestServer -Pperf -Pperfflags=a,b). Lets one build be
 * measured with and without each optimisation. Unused in normal play: every flag reads false.
 */
public final class PerfFlags {
    private static final Set<String> ON = Set.of(System.getProperty("underempire.perfflags", "").split(","));

    public static boolean on(String flag) {
        return ON.contains(flag);
    }

    private PerfFlags() {
    }
}
