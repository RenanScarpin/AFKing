package dev.renan.afking.client.behavior;

public enum CopperProcessingPhase {

    RECONCILE_WORLD,

    /*
     * Checks that a complete new cycle can start before placing
     * another copper block.
     */
    VALIDATE_CYCLE,

    ACQUIRE_COPPER,
    WAIT_FOR_COPPER,
    SELECT_COPPER,
    WAIT_AFTER_COPPER_SELECTION,
    PLACE_COPPER,
    WAIT_FOR_PLACEMENT,

    INSPECT_COPPER,

    ACQUIRE_AXE,
    WAIT_FOR_AXE,
    SELECT_AXE,
    WAIT_AFTER_AXE_SELECTION,
    SCRAPE_COPPER,
    WAIT_FOR_SCRAPE,

    ACQUIRE_PICKAXE,
    WAIT_FOR_PICKAXE,
    SELECT_PICKAXE,
    WAIT_AFTER_PICKAXE_SELECTION,
    START_MINING,
    CONTINUE_MINING,
    WAIT_FOR_PICKUP,

    /*
     * Short pause after successful collection before beginning
     * another cycle.
     */
    WAIT_BETWEEN_CYCLES
}