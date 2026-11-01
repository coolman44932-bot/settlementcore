package claude.settlementcore.testrace;

import claude.settlementcore.unit.SettlerMob;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** The test race's own goals; the engine's crew and idle goals (CrewGoal) do the rest. */
final class TRGoals {

    private TRGoals() {
    }

    /** Sent home: to its Heart, and down into the ground at it (or where it stands once its time is out). */
    static final class Homeward extends Goal {
        private final TestUnit u;

        Homeward(TestUnit u) {
            this.u = u;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
        }

        @Override
        public boolean canUse() {
            return u.isHomeward() && !u.isBurrowing();
        }

        @Override
        public void tick() {
            BlockPos h = u.heartPos();
            if (h == null || u.level().getGameTime() >= u.homeBy() || u.distanceToSqr(Vec3.atCenterOf(h)) <= 6.25) {
                u.sinkHome();
                return;
            }
            if (u.getNavigation().isDone()) {
                u.getNavigation().moveTo(h.getX() + 0.5, h.getY() + 1, h.getZ() + 0.5, 1.1);
            }
        }
    }

    /** Ordered to follow: after its owner, a few blocks behind. */
    static final class FollowOwner extends Goal {
        private final TestUnit u;
        private ServerPlayer owner;

        FollowOwner(TestUnit u) {
            this.u = u;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (u.getMode() != SettlerMob.Mode.FOLLOW || u.getTarget() != null) {
                return false;
            }
            HeartBlockEntity h = u.settlement();
            owner = h == null ? null : h.ownerPlayer();
            return owner != null && owner.level() == u.level() && u.distanceToSqr(owner) > 9;
        }

        @Override
        public boolean canContinueToUse() {
            return u.getMode() == SettlerMob.Mode.FOLLOW && owner != null && owner.isAlive() && u.distanceToSqr(owner) > 4 && u.getTarget() == null;
        }

        @Override
        public void tick() {
            u.getLookControl().setLookAt(owner);
            if (u.tickCount % 10 == 0) {
                u.getNavigation().moveTo(owner, 1.2);
            }
        }

        @Override
        public void stop() {
            u.getNavigation().stop();
        }
    }

    /** Ordered to hold: back to where it was told to stand. */
    static final class Hold extends Goal {
        private final TestUnit u;

        Hold(TestUnit u) {
            this.u = u;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            BlockPos g = u.guardPos();
            return u.getMode() == SettlerMob.Mode.GUARD && g != null && u.getTarget() == null && u.distanceToSqr(Vec3.atBottomCenterOf(g)) > 2.25;
        }

        @Override
        public void start() {
            BlockPos g = u.guardPos();
            u.getNavigation().moveTo(g.getX() + 0.5, g.getY(), g.getZ() + 0.5, 1.0);
        }

        @Override
        public boolean canContinueToUse() {
            return canUse() && !u.getNavigation().isDone();
        }
    }

    /** At work with nothing to do: about its Heart, strolling back when it has wandered more than {@value #LEASH} blocks off. */
    static final class StayNearHeart extends Goal {
        static final int LEASH = 12;
        private final TestUnit u;

        StayNearHeart(TestUnit u) {
            this.u = u;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (u.getMode() != SettlerMob.Mode.WORK || u.isCrew() || u.getTarget() != null || u.getRandom().nextInt(u.heartPos() != null
                    && u.distanceToSqr(Vec3.atCenterOf(u.heartPos())) > LEASH * LEASH ? 10 : 120) != 0) {
                return false;
            }
            return true;
        }

        @Override
        public void start() {
            BlockPos h = u.heartPos();
            if (h != null && u.distanceToSqr(Vec3.atCenterOf(h)) > LEASH * LEASH) {
                u.getNavigation().moveTo(h.getX() + 0.5, h.getY() + 1, h.getZ() + 0.5, 1.0);
                return;
            }
            Vec3 to = DefaultRandomPos.getPos(u, 8, 4);
            if (to != null) {
                u.getNavigation().moveTo(to.x, to.y, to.z, 0.9);
            }
        }

        @Override
        public boolean canContinueToUse() {
            return !u.getNavigation().isDone() && u.getTarget() == null;
        }
    }
}
