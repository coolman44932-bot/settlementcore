package claude.settlementcore.client;

import claude.settlementcore.room.Siting;

/**
 * How deep a plinth column is, as a colour (the plan outline, {@link PlanOutline}): none where there is no plinth (the ground reaches
 * the floor, or is cut down to it), blue for a thin one, shading to yellow at the deepest the plan allows
 * ({@link Siting#SKIRT_MAX}), and red past it, where the site is refused. No client class is named here, so a test on a server can ask it.
 */
public final class OutlineShade {

    private OutlineShade() {
    }

    /** {red, green, blue, alpha} of a column whose plinth is {@code depth} blocks deep, or null where there is none. */
    public static float[] of(int depth) {
        if (depth <= 0) {
            return null;
        }
        if (depth > Siting.SKIRT_MAX) {
            return new float[]{1f, 0.15f, 0.15f, 1f};
        }
        float t = Siting.SKIRT_MAX <= 1 ? 1f : (depth - 1) / (float) (Siting.SKIRT_MAX - 1);
        return new float[]{0.3f + 0.7f * t, 0.8f + 0.05f * t, 1f - 0.8f * t, 1f};
    }
}
