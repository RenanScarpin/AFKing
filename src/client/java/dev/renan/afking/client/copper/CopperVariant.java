package dev.renan.afking.client.copper;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

public record CopperVariant(
        CopperFamily family,
        CopperOxidationStage stage,
        boolean waxed,
        Item item,
        Block block
) {

    /*
     * Only affected copper is accepted as a source.
     *
     * This means both:
     *
     * Block of Copper
     * Waxed Block of Copper
     *
     * are excluded.
     */
    public boolean isProcessableInput() {
        return stage != CopperOxidationStage.UNAFFECTED;
    }

    /*
     * Final state wanted by AFKing.
     */
    public boolean isFinalOutput() {
        return stage == CopperOxidationStage.UNAFFECTED
                && !waxed;
    }

    /*
     * Explicitly protected by our policy.
     *
     * AFKing must never remove wax from this state.
     */
    public boolean isProtectedWaxedUnaffected() {
        return stage == CopperOxidationStage.UNAFFECTED
                && waxed;
    }

    /*
     * Affected stages require one axe use per oxidation level.
     *
     * Waxed affected copper requires one additional axe use
     * to remove the wax first.
     */
    public int requiredAxeUses() {
        if (!isProcessableInput()) {
            return 0;
        }

        return stage.stepsToUnaffected()
                + (waxed ? 1 : 0);
    }
}