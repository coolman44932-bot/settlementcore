package claude.settlementcore.testrace;

import claude.settlementcore.food.Fare;
import claude.settlementcore.settlement.SettlementBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The test race's Heart: a settlement where it stands. Placed, it is its placer's; used with an emerald (or a stack of them) it is paid,
 * with food it is fed; used with an empty hand it says how it stands.
 */
public class HeartBlock extends BaseEntityBlock {
    public static final MapCodec<HeartBlock> CODEC = simpleCodec(HeartBlock::new);

    public HeartBlock(Properties props) {
        super(props);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HeartBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, TRRegistry.HEART_ENTITY.get(),
                (l, p, s, be) -> SettlementBlockEntity.serverTick(l, p, s, be));
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        super.setPlacedBy(level, pos, state, by, stack);
        if (by instanceof ServerPlayer p && level.getBlockEntity(pos) instanceof HeartBlockEntity heart) {
            heart.claim(p, p.getGameProfile().name() + "'s Hold");
            heart.tellOwner(heart.settlementName() + " wakes: feed it, and its people rise.", ChatFormatting.GREEN);
        }
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
                                          BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof HeartBlockEntity heart) || stack.isEmpty()) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (stack.is(heart.currencyItem())) {
            heart.addCurrency(stack.getCount(), "the Heart's owner");
            stack.setCount(0);
            return InteractionResult.SUCCESS;
        }
        int each = Fare.nutrition(stack);
        if (each > 0) {
            heart.addFood(heart.foodValue(each) * stack.getCount(), Fare.grade(stack), "the Heart's owner");
            stack.setCount(0);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof HeartBlockEntity heart) {
            player.sendSystemMessage(Component.literal(heart.settlementName() + ": " + heart.peopleCount() + " up, " + heart.reserveWorkers()
                    + " workers and " + heart.reserveSoldiers() + " guards below, food " + heart.food() + ", emeralds " + heart.currency())
                    .withStyle(ChatFormatting.GREEN));
        }
        return InteractionResult.SUCCESS;
    }
}
