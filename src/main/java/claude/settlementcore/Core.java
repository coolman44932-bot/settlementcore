package claude.settlementcore;

/**
 * The settlement engine every race mod builds on (2026-09-27, user: "yeah do step 1"): digging rooms from plans, the
 * rooms' crews and work, the haul and the hoard, the ledger, food, defence, crowd rendering and the test harness. It
 * lives in {@code claude.settlementcore} and never refers to anything outside it: a race plugs in through
 * {@link claude.settlementcore.settlement.Settlement}, {@link claude.settlementcore.unit.SettlerMob},
 * {@link claude.settlementcore.room.Rooms}, {@link claude.settlementcore.work.RoomWorks} and {@link CoreConfig}.
 * It is its own mod, Settlement Core, in its own Gradle project (core/), which can't see a race's classes: javac refuses a
 * reference to one (docs/plans/core-step2.md, C7). Races depend on it; it depends on none of them.
 */
public final class Core {

    /**
     * The engine's mod id, which its event subscribers are registered under. FML registers an {@code @EventBusSubscriber}
     * only if its modid is the id of the jar it was found in, and a mismatch fails silently.
     */
    public static final String MODID = "settlementcore";

    /** The engine's own data ids (its item tags, its attribute modifiers, its config files): its mod id. */
    public static final String NAMESPACE = MODID;

    private Core() {
    }
}
