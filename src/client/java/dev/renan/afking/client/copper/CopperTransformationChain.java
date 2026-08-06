package dev.renan.afking.client.copper;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.Optional;

public final class CopperTransformationChain {

    public static final Block WAXED_OXIDIZED =
            Blocks.COPPER_BLOCK
                    .waxed()
                    .oxidized();

    public static final Block OXIDIZED =
            Blocks.COPPER_BLOCK
                    .weathering()
                    .oxidized();

    public static final Block WEATHERED =
            Blocks.COPPER_BLOCK
                    .weathering()
                    .weathered();

    public static final Block EXPOSED =
            Blocks.COPPER_BLOCK
                    .weathering()
                    .exposed();

    public static final Block PLAIN =
            Blocks.COPPER_BLOCK
                    .weathering()
                    .unaffected();

    public static final Item PLAIN_ITEM =
            Items.COPPER_BLOCK
                    .weathering()
                    .unaffected();

    private static final Map<Block, Block> NEXT_AFTER_AXE =
            Map.of(
                    WAXED_OXIDIZED, OXIDIZED,
                    OXIDIZED, WEATHERED,
                    WEATHERED, EXPOSED,
                    EXPOSED, PLAIN
            );

    private CopperTransformationChain() {
    }

    public static boolean isSupportedState(
            BlockState state
    ) {
        Block block = state.getBlock();

        return block == WAXED_OXIDIZED
                || block == OXIDIZED
                || block == WEATHERED
                || block == EXPOSED
                || block == PLAIN;
    }

    public static boolean isFinalState(
            BlockState state
    ) {
        return state.getBlock() == PLAIN;
    }

    public static Optional<Block> expectedAfterAxe(
            BlockState currentState
    ) {
        return Optional.ofNullable(
                NEXT_AFTER_AXE.get(
                        currentState.getBlock()
                )
        );
    }

    public static int remainingAxeUses(
            BlockState state
    ) {
        Block block = state.getBlock();

        if (block == WAXED_OXIDIZED) {
            return 4;
        }

        if (block == OXIDIZED) {
            return 3;
        }

        if (block == WEATHERED) {
            return 2;
        }

        if (block == EXPOSED) {
            return 1;
        }

        if (block == PLAIN) {
            return 0;
        }

        return -1;
    }

    public static String describe(
            BlockState state
    ) {
        Block block = state.getBlock();

        if (block == WAXED_OXIDIZED) {
            return "Waxed Oxidized Copper";
        }

        if (block == OXIDIZED) {
            return "Oxidized Copper";
        }

        if (block == WEATHERED) {
            return "Weathered Copper";
        }

        if (block == EXPOSED) {
            return "Exposed Copper";
        }

        if (block == PLAIN) {
            return "Block of Copper";
        }

        return "an unsupported block";
    }
}