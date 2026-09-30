package claude.underempire.core.defence;

import claude.underempire.core.unit.SettlerMob;
import claude.underempire.core.Core;
import claude.underempire.core.CoreConfig;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * The clan's foes (user, 2026-09-26: zombies and pillagers are the opponents of a Warlord's nest; Undead Nights, Zombies
 * Break &amp; Build and Immersive Pillagers in the modpack). Raiders and the undead go after the clan's rats on sight, as
 * they go after villagers, not only after the Warlord: a pillager war-band or a horde night falls on the warren itself.
 * Their own mods' kinds count (they extend the vanilla raider and zombie, or are tagged undead). The zombified piglins,
 * being peaceable, don't. Off with the engine's setting defence.foesHuntSettlers (settlementcore-common.toml).
 *
 * <p>The rats in their way ({@link RatsInTheWay}; user, 2026-09-27: "for the horde mod it focuses on me not skaven"):
 * Undead Nights sets each horde zombie on its player as it spawns, and its own target goal (above everything but
 * retaliation) holds it on that player through walls from its whole tracking range, so it walked past the clan. Now a
 * foe falls on the nearest rat it can see within {@link RatsInTheWay#RADIUS} (any within {@link RatsInTheWay#CLOSE})
 * while no player is nearer, and goes on for the player once the rat falls or is left behind.
 */
@EventBusSubscriber(modid = Core.MODID)
public final class Foes {

    private Foes() {
    }

    /** Whether this mob is one of the clan's foes: a raider, or the undead (not a zombified piglin). */
    public static boolean foe(PathfinderMob m) {
        return m instanceof Raider || (m instanceof Zombie || m instanceof Enemy && m.getType().is(EntityTypeTags.UNDEAD))
                && !(m instanceof ZombifiedPiglin);
    }

    @SubscribeEvent
    public static void join(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof PathfinderMob m) || !foe(m)
                || !CoreConfig.foesHuntSettlers.getAsBoolean()) {
            return;
        }
        // the rats in its way, above the player (2) and the horde's lock on its player: only retaliation (1) holds it back
        m.targetSelector.addGoal(1, new RatsInTheWay(m));
        // after the player (2), beside the villagers and golems (3): a rat of any clan, rogue or not
        m.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(m, SettlerMob.class, true));
    }

    /**
     * A foe falls on the rats in its way: the nearest rat it can see within {@link #RADIUS} (any within {@link #CLOSE}), if
     * no player who could be fought is nearer. It lets go when the rat falls or goes into the earth, when the rat is left
     * {@link #LEFT_BEHIND} behind, or when a player comes within {@link #CLOSE_PLAYER} of it: then it goes on for the player.
     */
    public static final class RatsInTheWay extends TargetGoal {
        public static final double RADIUS = 16, CLOSE = 6, LEFT_BEHIND = 24, CLOSE_PLAYER = 3;

        @Nullable
        private SettlerMob rat;
        private int next;                                         // a cooldown, never tickCount % n

        public RatsInTheWay(PathfinderMob mob) {
            super(mob, false, false);
            setFlags(EnumSet.of(Goal.Flag.TARGET));
        }

        @Override
        public boolean canUse() {
            if (mob.tickCount < next) {
                return false;
            }
            next = mob.tickCount + 10 + mob.getRandom().nextInt(10);
            rat = nearestRat();
            return rat != null;
        }

        @Nullable
        private SettlerMob nearestRat() {
            SettlerMob best = null;
            double bd = RADIUS * RADIUS;
            for (SettlerMob r : mob.level().getEntitiesOfClass(SettlerMob.class, mob.getBoundingBox().inflate(RADIUS, 6, RADIUS),
                    r -> r.isAlive() && !r.isBurrowing())) {
                double d = mob.distanceToSqr(r);
                if (d < bd && (d <= CLOSE * CLOSE || mob.getSensing().hasLineOfSight(r))) {
                    best = r;
                    bd = d;
                }
            }
            if (best == null) {
                return null;
            }
            if (nearestPlayer(Math.max(CLOSE_PLAYER, Math.sqrt(bd))) != null) {
                return null;                                        // a player nearer, or right at it: that is still its fight
            }
            LivingEntity t = mob.getTarget();
            if (t != null && t.isAlive() && t != best && !(t instanceof Player) && mob.distanceToSqr(t) < bd) {
                return null;                                        // already on something nearer
            }
            return best;
        }

        /** The nearest player it could fight within {@code within} blocks, or null. */
        @Nullable
        private Player nearestPlayer(double within) {
            Player best = null;
            double bd = within * within;
            for (Player p : mob.level().players()) {
                if (!p.isAlive() || p.isCreative() || p.isSpectator()) {
                    continue;
                }
                double d = mob.distanceToSqr(p);
                if (d < bd) {
                    best = p;
                    bd = d;
                }
            }
            return best;
        }

        @Override
        public void start() {
            mob.setTarget(rat);
            targetMob = rat;
            super.start();
        }

        @Override
        public boolean canContinueToUse() {
            if (!(mob.getTarget() instanceof SettlerMob r) || !r.isAlive() || r.isBurrowing()) {
                return false;
            }
            double d = mob.distanceToSqr(r);
            if (d > LEFT_BEHIND * LEFT_BEHIND) {
                return false;                                       // left behind: on for the player again
            }
            if (nearestPlayer(CLOSE_PLAYER) != null) {
                return false;                                       // a player walked right up to it: that is its fight now
            }
            return super.canContinueToUse();
        }

        @Override
        public void stop() {
            super.stop();
            rat = null;
        }
    }
}
