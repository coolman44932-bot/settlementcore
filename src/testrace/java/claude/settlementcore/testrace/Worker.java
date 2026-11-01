package claude.settlementcore.testrace;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/** The test race's worker: crews the Heart's rooms and digs; fights back when it must. */
public class Worker extends TestUnit {

    public Worker(EntityType<? extends Worker> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return baseAttributes()
                .add(Attributes.MAX_HEALTH, 14.0)
                .add(Attributes.MOVEMENT_SPEED, 0.32)
                .add(Attributes.ATTACK_DAMAGE, 2.0);
    }

    @Override
    public boolean isWorker() {
        return true;
    }
}
