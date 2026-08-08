package dev.renan.afking.client.copper;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

public final class CopperCatalog {

    private static final Map<Item, CopperVariant> BY_ITEM =
            new IdentityHashMap<>();

    private static final Map<Block, CopperVariant> BY_BLOCK =
            new IdentityHashMap<>();

    static {
        for (CopperFamily family : CopperFamily.values()) {
            for (CopperVariant variant : family.variants()) {
                BY_ITEM.put(
                        variant.item(),
                        variant
                );

                BY_BLOCK.put(
                        variant.block(),
                        variant
                );
            }
        }
    }

    private CopperCatalog() {
    }

    public static Optional<CopperVariant> find(
            ItemStack stack
    ) {
        if (stack.isEmpty()) {
            return Optional.empty();
        }

        return Optional.ofNullable(
                BY_ITEM.get(stack.getItem())
        );
    }

    public static Optional<CopperVariant> find(
            BlockState state
    ) {
        return Optional.ofNullable(
                BY_BLOCK.get(state.getBlock())
        );
    }

    /*
     * Source selection rule.
     *
     * Only affected copper is accepted.
     *
     * Therefore:
     *
     * Exposed             -> yes
     * Waxed Exposed       -> yes
     * Weathered           -> yes
     * Waxed Weathered     -> yes
     * Oxidized            -> yes
     * Waxed Oxidized      -> yes
     *
     * Unaffected          -> no
     * Waxed Unaffected    -> no
     */
    public static boolean isProcessableInput(
            ItemStack stack
    ) {
        return find(stack)
                .map(CopperVariant::isProcessableInput)
                .orElse(false);
    }

    public static Optional<CopperVariant> expectedAfterAxe(
            CopperVariant current
    ) {
        /*
         * This is critical:
         *
         * neither unaffected nor waxed unaffected has a legal
         * transition in AFKing.
         */
        if (!current.isProcessableInput()) {
            return Optional.empty();
        }

        /*
         * First axe use on an affected waxed block only removes wax.
         */
        if (current.waxed()) {
            return Optional.of(
                    current.family().variant(
                            current.stage(),
                            false
                    )
            );
        }

        CopperOxidationStage previousStage =
                current.stage().previousStage();

        if (previousStage == null) {
            return Optional.empty();
        }

        return Optional.of(
                current.family().variant(
                        previousStage,
                        false
                )
        );
    }
}