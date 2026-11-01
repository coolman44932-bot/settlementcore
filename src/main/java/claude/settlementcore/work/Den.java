package claude.settlementcore.work;

import claude.settlementcore.settlement.Settlements;
import claude.settlementcore.settlement.Settlement;
import claude.settlementcore.Core;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Warlord's Den: a Warlord who sleeps the night away in a bed in their own Den wakes to the night's
 * work done. Vanilla skips the night without ticking anything; here every work room does what its crew
 * would have done in the time skipped (Works#nightsWork: the furnaces smelt from their bins, the fields
 * grow and are reaped, the miners gnaw on down the drifts, the grove grows and is felled, the pens breed,
 * the Warlock mends), the clan eats and breeds (WarrenHeartBlockEntity#passNight), and the Warlord gets
 * the night's report, like the start of a new turn. At most {@link #MAX_SECONDS} of it. The bed also
 * sets the Warlord's respawn point, as any bed does.
 */
@EventBusSubscriber(modid = Core.MODID)
public final class Den {

    /** A whole Minecraft night is about 10 minutes (12,000 ticks). */
    public static final int MAX_SECONDS = 600;

    private Den() {
    }

    @SubscribeEvent
    public static void sleepFinished(SleepFinishedTimeEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        slept(level, e.getNewTime() - level.getDayTime());
    }

    /**
     * The night passed ({@code skipped} ticks) while {@code level}'s players slept: each one abed in their Den hears what
     * their settlement did. Other levels, whose time is the overworld's and whose sleep a race lets pass the overworld's
     * night, call this themselves.
     */
    public static void slept(ServerLevel level, long skipped) {
        if (skipped <= 20) {
            return;
        }
        int seconds = (int) Math.min(MAX_SECONDS, skipped / 20);
        Set<Settlement> done = new HashSet<>();
        for (ServerPlayer p : level.players()) {
            BlockPos bed = p.isSleeping() ? p.getSleepingPos().orElse(null) : null;
            if (bed == null) {
                continue;
            }
            Settlement h = Settlements.of(p);
            if (h == null || !h.inDen(bed) || !done.add(h)) {
                continue;
            }
            List<Component> report = nightsWork(level, h, seconds);
            p.sendSystemMessage(Component.literal("While you slept in your Den, " + h.peopleName() + " worked on:").withStyle(ChatFormatting.GOLD));
            report.forEach(p::sendSystemMessage);
        }
    }

    /** The night's work for this Heart (also used by the tests): its rooms at their own pace (Settlement#workPace). */
    public static List<Component> nightsWork(ServerLevel level, Settlement h, int seconds) {
        List<Component> report = new ArrayList<>();
        h.works().nightsWork(level, Math.round(seconds * h.workPace()), report);
        h.passNight(level, seconds, report);
        return report;
    }
}
