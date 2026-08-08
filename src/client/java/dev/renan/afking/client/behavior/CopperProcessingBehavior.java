package dev.renan.afking.client.behavior;

import dev.renan.afking.client.automation.AutomationContext;
import dev.renan.afking.client.automation.AutonomousBehavior;
import dev.renan.afking.client.automation.BehaviorResult;
import dev.renan.afking.client.automation.BehaviorStopReason;
import dev.renan.afking.client.copper.CopperStation;
import dev.renan.afking.client.copper.CopperCatalog;
import dev.renan.afking.client.copper.CopperFamily;
import dev.renan.afking.client.copper.CopperVariant;
import dev.renan.afking.client.copper.StationValidation;
import dev.renan.afking.client.service.InventoryService;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

public final class CopperProcessingBehavior
        implements AutonomousBehavior {

    public static final String ID =
            "copper_processing";

    private static final int PRIORITY = 100;

    /*
     * Inventory and slot synchronization delays.
     */
    private static final int MAX_LEASE_WAIT_TICKS = 20;
    private static final int SLOT_SELECTION_DELAY_TICKS = 1;
    private static final int MAX_INVENTORY_BUSY_TICKS = 40;

    /*
     * Placement confirmation and retry limits.
     */
    private static final int PLACEMENT_CONFIRMATION_TICKS = 20;
    private static final int MAX_PLACEMENT_ATTEMPTS = 3;

    /*
     * Scraping confirmation and retry limits.
     *
     * One interaction is sent, then AFKing waits for the server
     * to publish the expected block-state transition.
     */
    private static final int SCRAPE_CONFIRMATION_TICKS = 20;
    private static final int MAX_SCRAPE_ATTEMPTS_PER_STAGE = 3;

    /*
     * Ten seconds at the normal 20 client ticks per second.
     *
     * This is intentionally much longer than copper normally needs,
     * while still preventing endless mining in a protected area.
     */
    private static final int MAX_MINING_TICKS = 200;

    /*
     * Maximum time to remain blocked waiting for outstanding drops.
     *
     * Normally AFKing no longer waits after every block. This timeout
     * applies only when the outstanding-drop threshold is reached or
     * when processing has finished and remaining drops still need to
     * be collected.
     */
    private static final int PICKUP_CONFIRMATION_TICKS = 60;

    /*
     * Leaves a safety margin rather than using a pickaxe until it is
     * almost broken.
     */
    private static final int MIN_PICKAXE_REMAINING_DURABILITY = 10;

    /*
     * AFKing will never choose an axe if completing the remaining
     * scraping operations would leave it below this reserve.
     */
    private static final int MIN_AXE_REMAINING_DURABILITY = 10;

    /*
     * Number of processed copper drops that may remain temporarily
     * uncollected while AFKing continues processing more blocks.
     *
     * Once this many outputs are outstanding, AFKing pauses new cycles
     * until at least one is collected.
     */
    private static final int MAX_TEMPORARILY_UNCOLLECTED_ITEMS = 3;

    /*
     * Short buffer between successful cycles.
     */
    private static final int INTER_CYCLE_DELAY_TICKS = 5;

    /*
     * Number of complete blocks processed during the current F9 session.
     */
    private int completedCycles;

    /*
     * Delay counter between consecutive processing cycles.
     */
    private int interCycleWaitTicks;

    private CopperStation station;

    private CopperProcessingPhase phase =
            CopperProcessingPhase.RECONCILE_WORLD;

    private InventoryService.HotbarLease copperLease;
    private InventoryService.HotbarLease axeLease;
    private InventoryService.HotbarLease pickaxeLease;

    private boolean hasHeldControl;

    /*
     * The player's selected slot before the current inventory lease.
     */
    private int previousHotbarSlot = -1;

    private int leaseWaitTicks;
    private int selectionWaitTicks;
    private int inventoryBusyTicks;

    private int placementWaitTicks;
    private int placementAttempts;

    private int scrapeWaitTicks;
    private int scrapeAttempts;

    private int miningTicks;
    private int pickupWaitTicks;

    /*
     * Number of final output items already present when this F9 session
     * started. Combined with completedByFamily, this lets AFKing derive
     * how many processed drops are still waiting to be collected.
     */
    private final Map<CopperFamily, Integer> initialOutputCountsByFamily =
            new LinkedHashMap<>();

    /*
     * Used only while AFKing is actually blocked waiting for drops.
     * Progress resets the pickup timeout.
     */
    private int lastOutstandingPickupCount;

    private final Map<CopperFamily, Integer> completedByFamily =
            new LinkedHashMap<>();

    /*
     * True after AFKing has begun mining the processed block.
     *
     * This state survives preemption. If the block finishes breaking
     * while automatic eating has control, AFKing will wait for pickup
     * rather than placing a second copper block.
     */
    private boolean miningCycleStarted;

    /*
     * True after a copper block has been successfully placed or observed
     * at the station during this session.
     *
     * If the block later disappears before AFKing begins mining, that is
     * treated as external interference rather than a request to place
     * another block.
     */
    private boolean cycleCopperPlaced;

    /*
     * Exact source selected for the current cycle.
     */
    private CopperVariant activeSourceVariant;

    /*
     * Family being processed during the current cycle.
     */
    private CopperFamily activeFamily;

    /*
     * Variant observed before the latest axe interaction.
     */
    private CopperVariant scrapeSourceVariant;

    /*
     * Exact variant expected after one axe interaction.
     */
    private CopperVariant expectedVariantAfterScrape;

    private String completionMessage;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Copper processing";
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public boolean arm(
            AutomationContext context
    ) {
        resetSessionState();

        CopperStation.CaptureResult capture =
                CopperStation.capture(
                        context.client()
                );

        if (!capture.succeeded()) {
            sendMessage(
                    context.client(),
                    "AFKing: copper processing could not start — "
                            + capture.errorMessage()
            );

            return false;
        }

        station = capture.station();

        initializeOutputBaselines(context);

        phase = CopperProcessingPhase.RECONCILE_WORLD;

        sendMessage(
                context.client(),
                "AFKing: copper station selected — "
                        + station.describe()
                        + "."
        );

        return true;
    }

    @Override
    public boolean shouldRun(
            AutomationContext context
    ) {
        return station != null
                && context.client().player != null;
    }

    @Override
    public void start(
            AutomationContext context
    ) {
        if (!hasHeldControl) {
            hasHeldControl = true;

            sendMessage(
                    context.client(),
                    "AFKing: copper processing started."
            );

            return;
        }

        sendMessage(
                context.client(),
                "AFKing: copper processing resumed."
        );
    }

    @Override
    public BehaviorResult tick(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        if (
                station == null
                        || client.player == null
                        || client.level == null
        ) {
            return fail(
                    client,
                    "no valid copper station is selected."
            );
        }

        StationValidation persistentValidation =
                station.validatePersistent(client);

        if (!persistentValidation.valid()) {
            return fail(
                    client,
                    persistentValidation.errorMessage()
            );
        }

        return switch (phase) {
            case RECONCILE_WORLD ->
                    reconcileWorld(context);

            case ACQUIRE_COPPER ->
                    acquireCopper(context);

            case WAIT_FOR_COPPER ->
                    waitForCopper(context);

            case SELECT_COPPER ->
                    selectCopper(context);

            case WAIT_AFTER_COPPER_SELECTION ->
                    waitAfterCopperSelection(context);

            case PLACE_COPPER ->
                    placeCopper(context);

            case WAIT_FOR_PLACEMENT ->
                    waitForPlacement(context);

            case INSPECT_COPPER ->
                    inspectCopper(context);

            case ACQUIRE_AXE ->
                    acquireAxe(context);

            case WAIT_FOR_AXE ->
                    waitForAxe(context);

            case SELECT_AXE ->
                    selectAxe(context);

            case WAIT_AFTER_AXE_SELECTION ->
                    waitAfterAxeSelection(context);

            case SCRAPE_COPPER ->
                    scrapeCopper(context);

            case WAIT_FOR_SCRAPE ->
                    waitForScrape(context);

            case ACQUIRE_PICKAXE ->
                    acquirePickaxe(context);

            case WAIT_FOR_PICKAXE ->
                    waitForPickaxe(context);

            case SELECT_PICKAXE ->
                    selectPickaxe(context);

            case WAIT_AFTER_PICKAXE_SELECTION ->
                    waitAfterPickaxeSelection(context);

            case START_MINING ->
                    startMining(context);

            case CONTINUE_MINING ->
                    continueMining(context);

            case WAIT_FOR_PICKUP ->
                    waitForPickup(context);

            case VALIDATE_CYCLE ->
                    validateCycle(context);

            case WAIT_BETWEEN_CYCLES ->
                    waitBetweenCycles(context);
        };
    }

    @Override
    public void stop(
            AutomationContext context,
            BehaviorStopReason reason
    ) {
        /*
         * Safe even when no mining operation is active.
         */
        context.blockInteractions().stopMining(
                context.client()
        );

        switch (reason) {
            case PREEMPTED -> {
                releaseCopperLease(context, true);
                releaseAxeLease(context, true);
                releasePickaxeLease(context, true);

                /*
                 * Preserve miningCycleStarted and the inventory baseline.
                 * This allows AFKing to detect a block that finished
                 * breaking during the preemption.
                 */
                phase =
                        CopperProcessingPhase.RECONCILE_WORLD;

                clearTransientActionState();

                if (station != null) {
                    sendMessage(
                            context.client(),
                            "AFKing: copper processing paused."
                    );
                }
            }

            case COMPLETED -> {
                releaseCopperLease(context, true);
                releaseAxeLease(context, true);
                releasePickaxeLease(context, true);

                if (completionMessage != null) {
                    sendMessage(
                            context.client(),
                            completionMessage
                    );
                }

                resetSessionState();
            }

            case CANCELLED -> {
                releaseCopperLease(context, true);
                releaseAxeLease(context, true);
                releasePickaxeLease(context, true);

                if (station != null) {
                    sendMessage(
                            context.client(),
                            "AFKing: copper processing stopped."
                    );
                }

                resetSessionState();
            }

            case FAILED -> {
                releaseCopperLease(context, true);
                releaseAxeLease(context, true);
                releasePickaxeLease(context, true);

                resetSessionState();
            }

            case WORLD_LEFT -> {
                releaseCopperLease(context, false);
                releaseAxeLease(context, false);
                releasePickaxeLease(context, false);

                resetSessionState();
            }
        }
    }

    @Override
    public boolean disarmWhenAutomationStops() {
        return true;
    }

    private void initializeOutputBaselines(
            AutomationContext context
    ) {
        initialOutputCountsByFamily.clear();

        for (CopperFamily family : CopperFamily.values()) {
            initialOutputCountsByFamily.put(
                    family,
                    countFamilyOutput(
                            context,
                            family
                    )
            );
        }
    }

    private int countFamilyOutput(
            AutomationContext context,
            CopperFamily family
    ) {
        return context.inventory().countMatching(
                context.client(),
                stack ->
                        !stack.isEmpty()
                                && stack.getItem()
                                == family
                                .finalOutput()
                                .item()
        );
    }

    private int outstandingPickupsForFamily(
            AutomationContext context,
            CopperFamily family
    ) {
        int processed =
                completedByFamily.getOrDefault(
                        family,
                        0
                );

        int initial =
                initialOutputCountsByFamily.getOrDefault(
                        family,
                        0
                );

        int current =
                countFamilyOutput(
                        context,
                        family
                );

        int collectedDuringSession =
                Math.max(
                        0,
                        current - initial
                );

        return Math.max(
                0,
                processed - collectedDuringSession
        );
    }

    private int totalOutstandingPickups(
            AutomationContext context
    ) {
        int total = 0;

        for (CopperFamily family : CopperFamily.values()) {
            total += outstandingPickupsForFamily(
                    context,
                    family
            );
        }

        return total;
    }

    /*
     * ------------------------------------------------------------
     * World reconciliation
     * ------------------------------------------------------------
     */

    private BehaviorResult reconcileWorld(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        BlockState targetState =
                getTargetState(client);

        if (targetState.isAir()) {
            if (miningCycleStarted) {
                phase =
                        CopperProcessingPhase.WAIT_FOR_PICKUP;

                return BehaviorResult.KEEP_RUNNING;
            }

            if (cycleCopperPlaced) {
                return fail(
                        client,
                        "the copper block disappeared before "
                                + "AFKing could mine it."
                );
            }

            /*
             * If we were interrupted before placement, choosing a new
             * source item is safe.
             */
            clearActiveCycleSelection();

            phase =
                    CopperProcessingPhase.VALIDATE_CYCLE;

            return BehaviorResult.KEEP_RUNNING;
        }

        Optional<CopperVariant> currentVariant =
                CopperCatalog.find(targetState);

        if (currentVariant.isEmpty()) {
            return fail(
                    client,
                    "the station contains an unsupported block."
            );
        }

        CopperVariant variant =
                currentVariant.get();

        if (
                activeFamily != null
                        && variant.family() != activeFamily
        ) {
            return fail(
                    client,
                    "the copper block at the station belongs "
                            + "to a different copper family."
            );
        }

        activeFamily = variant.family();
        cycleCopperPlaced = true;

        /*
         * Never remove wax from an unaffected waxed block.
         */
        if (variant.isProtectedWaxedUnaffected()) {
            return fail(
                    client,
                    "the station contains waxed unaffected copper. "
                            + "AFKing will not remove its wax."
            );
        }

        miningCycleStarted = false;
        pickupWaitTicks = 0;

        phase =
                CopperProcessingPhase.INSPECT_COPPER;

        return BehaviorResult.KEEP_RUNNING;
    }

    /*
     * ------------------------------------------------------------
     * Copper placement
     * ------------------------------------------------------------
     */

    private BehaviorResult validateCycle(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        StationValidation stationValidation =
                station.validateReadyForPlacement(client);

        if (!stationValidation.valid()) {
            return fail(
                    client,
                    stationValidation.errorMessage()
            );
        }

        /*
         * Find the next affected copper item.
         *
         * InventoryService already prefers:
         *
         * current hotbar slot
         * → remaining hotbar
         * → main inventory
         */
        Optional<InventoryService.InventorySlot> sourceSlot =
                context.inventory().findFirst(
                        client,
                        CopperCatalog::isProcessableInput
                );

        if (sourceSlot.isEmpty()) {
            int outstanding =
                    totalOutstandingPickups(context);

            if (outstanding > 0) {
                pickupWaitTicks = 0;
                lastOutstandingPickupCount =
                        outstanding;

                phase =
                        CopperProcessingPhase.WAIT_FOR_PICKUP;

                return BehaviorResult.KEEP_RUNNING;
            }

            return complete(
                    createFinishedMessage()
            );
        }

        ItemStack sourceStack =
                client.player
                        .getInventory()
                        .getItem(
                                sourceSlot.get().inventoryIndex()
                        );

        Optional<CopperVariant> sourceVariant =
                CopperCatalog.find(sourceStack);

        if (sourceVariant.isEmpty()) {
            return fail(
                    client,
                    "the selected copper item could not be identified."
            );
        }

        activeSourceVariant = sourceVariant.get();
        activeFamily = activeSourceVariant.family();

        int requiredAxeUses =
                activeSourceVariant.requiredAxeUses();

        /*
         * Validate the axe before placing anything.
         */
        boolean anyAxeExists =
                context.inventory().hasMatchingItem(
                        client,
                        this::isAxe
                );

        if (!anyAxeExists) {
            return fail(
                    client,
                    "no axe was found in the inventory."
            );
        }

        boolean usableAxeExists =
                context.inventory().hasMatchingItem(
                        client,
                        stack -> isUsableAxe(
                                stack,
                                requiredAxeUses
                        )
                );

        if (!usableAxeExists) {
            int minimumRemainingDurability =
                    MIN_AXE_REMAINING_DURABILITY
                            + requiredAxeUses;

            return fail(
                    client,
                    "no axe has at least "
                            + minimumRemainingDurability
                            + " durability points remaining "
                            + "to process the next copper item "
                            + "while keeping a "
                            + MIN_AXE_REMAINING_DURABILITY
                            + "-point safety reserve."
            );
        }

        /*
         * Pickaxe requirements depend on the final block belonging
         * to the selected family.
         */
        BlockState finalState =
                activeFamily
                        .finalOutput()
                        .block()
                        .defaultBlockState();

        boolean anyPickaxeExists =
                context.inventory().hasMatchingItem(
                        client,
                        this::isPickaxe
                );

        if (!anyPickaxeExists) {
            return fail(
                    client,
                    "no pickaxe was found in the inventory."
            );
        }

        boolean correctPickaxeExists =
                context.inventory().hasMatchingItem(
                        client,
                        stack -> isCorrectPickaxe(
                                stack,
                                finalState
                        )
                );

        if (!correctPickaxeExists) {
            return fail(
                    client,
                    "no available pickaxe can harvest "
                            + activeFamily.singularOutputName()
                            + "."
            );
        }

        boolean usablePickaxeExists =
                context.inventory().hasMatchingItem(
                        client,
                        stack -> isUsablePickaxe(
                                stack,
                                finalState
                        )
                );

        if (!usablePickaxeExists) {
            return fail(
                    client,
                    "no suitable pickaxe has at least "
                            + MIN_PICKAXE_REMAINING_DURABILITY
                            + " durability points remaining."
            );
        }

        phase =
                CopperProcessingPhase.ACQUIRE_COPPER;

        return BehaviorResult.KEEP_RUNNING;
    }

    private BehaviorResult acquireCopper(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        StationValidation validation =
                station.validateReadyForPlacement(client);

        if (!validation.valid()) {
            return fail(
                    client,
                    validation.errorMessage()
            );
        }

        if (client.player.isUsingItem()) {
            return BehaviorResult.KEEP_RUNNING;
        }

        previousHotbarSlot =
                client.player
                        .getInventory()
                        .getSelectedSlot();

        Optional<InventoryService.HotbarLease> lease =
                context.inventory().acquireToHotbar(
                        client,
                        this::isActiveSourceItem,
                        previousHotbarSlot
                );

        if (lease.isEmpty()) {
            boolean sourceStillExists =
                    context.inventory().hasMatchingItem(
                            client,
                            this::isActiveSourceItem
                    );

            if (!sourceStillExists) {
                /*
                 * Something changed in the inventory between validation and
                 * acquisition. Select another source rather than treating the
                 * whole batch as finished.
                 */
                clearActiveCycleSelection();

                phase =
                        CopperProcessingPhase.VALIDATE_CYCLE;

                return BehaviorResult.KEEP_RUNNING;
            }

            inventoryBusyTicks++;

            if (
                    inventoryBusyTicks
                            > MAX_INVENTORY_BUSY_TICKS
            ) {
                return fail(
                        client,
                        "the inventory remained unavailable "
                                + "for too long."
                );
            }

            return BehaviorResult.KEEP_RUNNING;
        }

        copperLease = lease.get();

        leaseWaitTicks = 0;
        inventoryBusyTicks = 0;

        phase =
                CopperProcessingPhase.WAIT_FOR_COPPER;

        return BehaviorResult.KEEP_RUNNING;
    }

    private BehaviorResult waitForCopper(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        if (copperLease == null) {
            phase =
                    CopperProcessingPhase.ACQUIRE_COPPER;

            return BehaviorResult.KEEP_RUNNING;
        }

        int selectedSlot =
                client.player
                        .getInventory()
                        .getSelectedSlot();

        if (selectedSlot != previousHotbarSlot) {
            return fail(
                    client,
                    "the selected hotbar slot was changed manually."
            );
        }

        if (copperLease.isReady(client)) {
            phase =
                    CopperProcessingPhase.SELECT_COPPER;

            return BehaviorResult.KEEP_RUNNING;
        }

        leaseWaitTicks++;

        if (leaseWaitTicks > MAX_LEASE_WAIT_TICKS) {
            return fail(
                    client,
                    "the copper inventory swap did not complete."
            );
        }

        return BehaviorResult.KEEP_RUNNING;
    }

    private BehaviorResult selectCopper(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        if (
                copperLease == null
                        || !copperLease.isReady(client)
        ) {
            phase =
                    CopperProcessingPhase.WAIT_FOR_COPPER;

            return BehaviorResult.KEEP_RUNNING;
        }

        client.player
                .getInventory()
                .setSelectedSlot(
                        copperLease.hotbarSlot()
                );

        selectionWaitTicks = 0;

        phase =
                CopperProcessingPhase
                        .WAIT_AFTER_COPPER_SELECTION;

        return BehaviorResult.KEEP_RUNNING;
    }

    private BehaviorResult waitAfterCopperSelection(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        if (!isControlledCopperSelected(client)) {
            return fail(
                    client,
                    "the selected copper slot was changed "
                            + "before placement."
            );
        }

        selectionWaitTicks++;

        if (
                selectionWaitTicks
                        <= SLOT_SELECTION_DELAY_TICKS
        ) {
            return BehaviorResult.KEEP_RUNNING;
        }

        phase =
                CopperProcessingPhase.PLACE_COPPER;

        return BehaviorResult.KEEP_RUNNING;
    }

    private BehaviorResult placeCopper(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        StationValidation validation =
                station.validateReadyForPlacement(client);

        if (!validation.valid()) {
            return fail(
                    client,
                    validation.errorMessage()
            );
        }

        if (!isControlledCopperSelected(client)) {
            return fail(
                    client,
                    "the selected copper item is no longer selected."
            );
        }

        boolean sent =
                context.blockInteractions()
                        .useItemOnBlock(
                                client,
                                InteractionHand.MAIN_HAND,
                                station.createInteractionHitResult()
                        );

        if (!sent) {
            return fail(
                    client,
                    "the placement interaction could not be sent."
            );
        }

        placementAttempts++;
        placementWaitTicks = 0;

        phase =
                CopperProcessingPhase.WAIT_FOR_PLACEMENT;

        return BehaviorResult.KEEP_RUNNING;
    }

    private BehaviorResult waitForPlacement(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        BlockState targetState =
                getTargetState(client);

        Optional<CopperVariant> placedVariant =
                CopperCatalog.find(targetState);

        if (placedVariant.isPresent()) {
            CopperVariant variant =
                    placedVariant.get();

            if (
                    activeFamily == null
                            || variant.family() != activeFamily
            ) {
                return fail(
                        client,
                        "the placement position contains "
                                + "a different copper family."
                );
            }

            if (variant.isProtectedWaxedUnaffected()) {
                return fail(
                        client,
                        "the placed copper unexpectedly became "
                                + "waxed unaffected copper."
                );
            }

            cycleCopperPlaced = true;

            releaseCopperLease(context, true);

            placementAttempts = 0;
            placementWaitTicks = 0;

            phase =
                    CopperProcessingPhase.INSPECT_COPPER;

            return BehaviorResult.KEEP_RUNNING;
        }

        if (!targetState.isAir()) {
            return fail(
                    client,
                    "the placement position became occupied "
                            + "by an unexpected block."
            );
        }

        placementWaitTicks++;

        if (
                placementWaitTicks
                        <= PLACEMENT_CONFIRMATION_TICKS
        ) {
            return BehaviorResult.KEEP_RUNNING;
        }

        if (
                placementAttempts
                        >= MAX_PLACEMENT_ATTEMPTS
        ) {
            return fail(
                    client,
                    "the server did not confirm placement after "
                            + placementAttempts
                            + " attempts."
            );
        }

        phase =
                CopperProcessingPhase.PLACE_COPPER;

        return BehaviorResult.KEEP_RUNNING;
    }

    /*
     * ------------------------------------------------------------
     * Copper scraping
     * ------------------------------------------------------------
     */

    private BehaviorResult inspectCopper(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        BlockState targetState =
                getTargetState(client);

        if (targetState.isAir()) {
            if (miningCycleStarted) {
                phase =
                        CopperProcessingPhase.WAIT_FOR_PICKUP;

                return BehaviorResult.KEEP_RUNNING;
            }

            return fail(
                    client,
                    "the placed copper block disappeared."
            );
        }

        Optional<CopperVariant> found =
                CopperCatalog.find(targetState);

        if (found.isEmpty()) {
            return fail(
                    client,
                    "the station contains an unsupported block."
            );
        }

        CopperVariant current =
                found.get();

        if (
                activeFamily != null
                        && current.family() != activeFamily
        ) {
            return fail(
                    client,
                    "the copper family changed unexpectedly."
            );
        }

        activeFamily = current.family();
        cycleCopperPlaced = true;

        /*
         * Critical protection rule.
         */
        if (current.isProtectedWaxedUnaffected()) {
            return fail(
                    client,
                    "the block is waxed and unaffected. "
                            + "AFKing will not remove its wax."
            );
        }

        /*
         * Unwaxed unaffected is our final scraping target.
         */
        if (current.isFinalOutput()) {
            releaseAxeLease(context, true);

            ItemStack expectedOutput =
                    activeFamily
                            .finalOutput()
                            .item()
                            .getDefaultInstance();

            if (
                    !context.inventory().canAccept(
                            client,
                            expectedOutput
                    )
            ) {
                return fail(
                        client,
                        "the inventory has no room for "
                                + activeFamily.singularOutputName()
                                + "."
                );
            }

            phase =
                    CopperProcessingPhase.ACQUIRE_PICKAXE;

            return BehaviorResult.KEEP_RUNNING;
        }

        int requiredDurability =
                current.requiredAxeUses();

        if (axeLease != null) {
            int selectedSlot =
                    client.player
                            .getInventory()
                            .getSelectedSlot();

            if (selectedSlot != axeLease.hotbarSlot()) {
                return fail(
                        client,
                        "the axe hotbar slot was changed manually."
                );
            }

            ItemStack selectedAxe =
                    client.player.getMainHandItem();

            if (
                    isUsableAxe(
                            selectedAxe,
                            requiredDurability
                    )
            ) {
                phase =
                        CopperProcessingPhase.SCRAPE_COPPER;

                return BehaviorResult.KEEP_RUNNING;
            }

            releaseAxeLease(context, true);
        }

        phase =
                CopperProcessingPhase.ACQUIRE_AXE;

        return BehaviorResult.KEEP_RUNNING;
    }

    private BehaviorResult acquireAxe(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        Optional<CopperVariant> found =
                CopperCatalog.find(
                        getTargetState(client)
                );

        if (found.isEmpty()) {
            phase =
                    CopperProcessingPhase.RECONCILE_WORLD;

            return BehaviorResult.KEEP_RUNNING;
        }

        CopperVariant current =
                found.get();

        if (current.isProtectedWaxedUnaffected()) {
            return fail(
                    client,
                    "AFKing will not remove wax from "
                            + "unaffected copper."
            );
        }

        if (current.isFinalOutput()) {
            phase =
                    CopperProcessingPhase.INSPECT_COPPER;

            return BehaviorResult.KEEP_RUNNING;
        }

        int requiredDurability =
                current.requiredAxeUses();

        if (requiredDurability <= 0) {
            phase =
                    CopperProcessingPhase.INSPECT_COPPER;

            return BehaviorResult.KEEP_RUNNING;
        }

        previousHotbarSlot =
                client.player
                        .getInventory()
                        .getSelectedSlot();

        Optional<InventoryService.HotbarLease> lease =
                context.inventory().acquireToHotbar(
                        client,
                        stack -> isUsableAxe(
                                stack,
                                requiredDurability
                        ),
                        previousHotbarSlot
                );

        if (lease.isEmpty()) {
            boolean anyAxeExists =
                    context.inventory().hasMatchingItem(
                            client,
                            this::isAxe
                    );

            boolean usableAxeExists =
                    context.inventory().hasMatchingItem(
                            client,
                            stack -> isUsableAxe(
                                    stack,
                                    requiredDurability
                            )
                    );

            if (!anyAxeExists) {
                return fail(
                        client,
                        "no axe was found in the inventory."
                );
            }

            if (!usableAxeExists) {
                int minimumRemainingDurability =
                        MIN_AXE_REMAINING_DURABILITY
                                + requiredDurability;

                return fail(
                        client,
                        "no axe has at least "
                                + minimumRemainingDurability
                                + " durability points remaining "
                                + "to finish scraping this block "
                                + "while keeping a "
                                + MIN_AXE_REMAINING_DURABILITY
                                + "-point safety reserve."
                );
            }

            inventoryBusyTicks++;

            if (
                    inventoryBusyTicks
                            > MAX_INVENTORY_BUSY_TICKS
            ) {
                return fail(
                        client,
                        "the inventory remained unavailable "
                                + "while acquiring an axe."
                );
            }

            return BehaviorResult.KEEP_RUNNING;
        }

        axeLease = lease.get();

        leaseWaitTicks = 0;
        inventoryBusyTicks = 0;

        phase =
                CopperProcessingPhase.WAIT_FOR_AXE;

        return BehaviorResult.KEEP_RUNNING;
    }

    private BehaviorResult waitForAxe(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        if (axeLease == null) {
            phase =
                    CopperProcessingPhase.ACQUIRE_AXE;

            return BehaviorResult.KEEP_RUNNING;
        }

        int selectedSlot =
                client.player
                        .getInventory()
                        .getSelectedSlot();

        if (selectedSlot != previousHotbarSlot) {
            return fail(
                    client,
                    "the selected hotbar slot was changed manually."
            );
        }

        if (axeLease.isReady(client)) {
            phase =
                    CopperProcessingPhase.SELECT_AXE;

            return BehaviorResult.KEEP_RUNNING;
        }

        leaseWaitTicks++;

        if (leaseWaitTicks > MAX_LEASE_WAIT_TICKS) {
            return fail(
                    client,
                    "the axe inventory swap did not complete."
            );
        }

        return BehaviorResult.KEEP_RUNNING;
    }

    private BehaviorResult selectAxe(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        if (
                axeLease == null
                        || !axeLease.isReady(client)
        ) {
            phase =
                    CopperProcessingPhase.WAIT_FOR_AXE;

            return BehaviorResult.KEEP_RUNNING;
        }

        client.player
                .getInventory()
                .setSelectedSlot(
                        axeLease.hotbarSlot()
                );

        selectionWaitTicks = 0;

        phase =
                CopperProcessingPhase
                        .WAIT_AFTER_AXE_SELECTION;

        return BehaviorResult.KEEP_RUNNING;
    }

    private BehaviorResult waitAfterAxeSelection(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        BlockState targetState =
                getTargetState(client);

        Optional<CopperVariant> found =
                CopperCatalog.find(
                        getTargetState(client)
                );

        if (found.isEmpty()) {
            return fail(
                    client,
                    "the copper block changed unexpectedly."
            );
        }

        CopperVariant current =
                found.get();

        if (
                current.isFinalOutput()
                        || current.isProtectedWaxedUnaffected()
        ) {
            phase =
                    CopperProcessingPhase.INSPECT_COPPER;

            return BehaviorResult.KEEP_RUNNING;
        }

        int requiredDurability =
                current.requiredAxeUses();

        if (
                axeLease == null
                        || client.player
                        .getInventory()
                        .getSelectedSlot()
                        != axeLease.hotbarSlot()
                        || !isUsableAxe(
                        client.player.getMainHandItem(),
                        Math.max(requiredDurability, 1)
                )
        ) {
            return fail(
                    client,
                    "the axe slot changed before scraping."
            );
        }

        selectionWaitTicks++;

        if (
                selectionWaitTicks
                        <= SLOT_SELECTION_DELAY_TICKS
        ) {
            return BehaviorResult.KEEP_RUNNING;
        }

        phase =
                CopperProcessingPhase.SCRAPE_COPPER;

        return BehaviorResult.KEEP_RUNNING;
    }

    private BehaviorResult scrapeCopper(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        Optional<CopperVariant> found =
                CopperCatalog.find(
                        getTargetState(client)
                );

        if (found.isEmpty()) {
            return fail(
                    client,
                    "the block is no longer supported copper."
            );
        }

        CopperVariant current =
                found.get();

        if (
                activeFamily != null
                        && current.family() != activeFamily
        ) {
            return fail(
                    client,
                    "the copper family changed unexpectedly."
            );
        }

        if (current.isProtectedWaxedUnaffected()) {
            return fail(
                    client,
                    "AFKing will not remove wax from "
                            + "unaffected copper."
            );
        }

        if (current.isFinalOutput()) {
            phase =
                    CopperProcessingPhase.INSPECT_COPPER;

            return BehaviorResult.KEEP_RUNNING;
        }

        Optional<CopperVariant> expectedNext =
                CopperCatalog.expectedAfterAxe(current);

        if (expectedNext.isEmpty()) {
            return fail(
                    client,
                    "no safe scraping transition exists "
                            + "for the current copper block."
            );
        }

        int requiredDurability =
                current.requiredAxeUses();

        if (
                axeLease == null
                        || client.player
                        .getInventory()
                        .getSelectedSlot()
                        != axeLease.hotbarSlot()
                        || !isUsableAxe(
                        client.player.getMainHandItem(),
                        requiredDurability
                )
        ) {
            return fail(
                    client,
                    "the required axe is no longer selected."
            );
        }

        scrapeSourceVariant = current;
        expectedVariantAfterScrape =
                expectedNext.get();

        boolean sent =
                context.blockInteractions()
                        .useItemOnBlock(
                                client,
                                InteractionHand.MAIN_HAND,
                                station.createTargetInteractionHitResult()
                        );

        if (!sent) {
            return fail(
                    client,
                    "the axe interaction could not be sent."
            );
        }

        scrapeAttempts++;
        scrapeWaitTicks = 0;

        phase =
                CopperProcessingPhase.WAIT_FOR_SCRAPE;

        return BehaviorResult.KEEP_RUNNING;
    }

    private BehaviorResult waitForScrape(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        Optional<CopperVariant> found =
                CopperCatalog.find(
                        getTargetState(client)
                );

        if (found.isEmpty()) {
            return fail(
                    client,
                    "the copper block changed into "
                            + "an unexpected block."
            );
        }

        CopperVariant current =
                found.get();

        /*
         * Exact expected transition confirmed.
         */
        if (
                expectedVariantAfterScrape != null
                        && current.equals(
                        expectedVariantAfterScrape
                )
        ) {
            scrapeAttempts = 0;
            scrapeWaitTicks = 0;

            scrapeSourceVariant = null;
            expectedVariantAfterScrape = null;

            phase =
                    CopperProcessingPhase.INSPECT_COPPER;

            return BehaviorResult.KEEP_RUNNING;
        }

        /*
         * Something changed the block externally.
         *
         * If it remains a valid member of the same copper family,
         * re-read the state rather than blindly sending another click.
         */
        if (
                scrapeSourceVariant != null
                        && !current.equals(scrapeSourceVariant)
        ) {
            if (
                    activeFamily != null
                            && current.family() != activeFamily
            ) {
                return fail(
                        client,
                        "the copper family changed unexpectedly."
                );
            }

            if (current.isProtectedWaxedUnaffected()) {
                return fail(
                        client,
                        "the block became waxed unaffected copper. "
                                + "AFKing will not remove its wax."
                );
            }

            scrapeAttempts = 0;
            scrapeWaitTicks = 0;

            scrapeSourceVariant = null;
            expectedVariantAfterScrape = null;

            phase =
                    CopperProcessingPhase.INSPECT_COPPER;

            return BehaviorResult.KEEP_RUNNING;
        }

        scrapeWaitTicks++;

        if (
                scrapeWaitTicks
                        <= SCRAPE_CONFIRMATION_TICKS
        ) {
            return BehaviorResult.KEEP_RUNNING;
        }

        if (
                scrapeAttempts
                        >= MAX_SCRAPE_ATTEMPTS_PER_STAGE
        ) {
            return fail(
                    client,
                    "the server did not confirm the copper "
                            + "scraping interaction after "
                            + scrapeAttempts
                            + " attempts."
            );
        }

        phase =
                CopperProcessingPhase.SCRAPE_COPPER;

        return BehaviorResult.KEEP_RUNNING;
    }

    /*
     * ------------------------------------------------------------
     * Pickaxe acquisition
     * ------------------------------------------------------------
     */

    private BehaviorResult acquirePickaxe(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        if (activeFamily == null) {
            return fail(
                    client,
                    "the active copper family is unknown."
            );
        }

        BlockState targetState =
                getTargetState(client);

        Optional<CopperVariant> found =
                CopperCatalog.find(targetState);

        if (
                found.isEmpty()
                        || found.get().family() != activeFamily
                        || !found.get().isFinalOutput()
        ) {
            phase =
                    CopperProcessingPhase.RECONCILE_WORLD;

            return BehaviorResult.KEEP_RUNNING;
        }

        previousHotbarSlot =
                client.player
                        .getInventory()
                        .getSelectedSlot();

        Optional<InventoryService.HotbarLease> lease =
                context.inventory().acquireToHotbar(
                        client,
                        stack -> isUsablePickaxe(
                                stack,
                                targetState
                        ),
                        previousHotbarSlot
                );

        if (lease.isEmpty()) {
            boolean anyPickaxeExists =
                    context.inventory().hasMatchingItem(
                            client,
                            this::isPickaxe
                    );

            boolean correctPickaxeExists =
                    context.inventory().hasMatchingItem(
                            client,
                            stack -> isCorrectPickaxe(
                                    stack,
                                    targetState
                            )
                    );

            boolean usablePickaxeExists =
                    context.inventory().hasMatchingItem(
                            client,
                            stack -> isUsablePickaxe(
                                    stack,
                                    targetState
                            )
                    );

            if (!anyPickaxeExists) {
                return fail(
                        client,
                        "no pickaxe was found in the inventory."
                );
            }

            if (!correctPickaxeExists) {
                return fail(
                        client,
                        "no available pickaxe can harvest "
                                + activeFamily.singularOutputName()
                                + "."
                );
            }

            if (!usablePickaxeExists) {
                return fail(
                        client,
                        "no suitable pickaxe has enough "
                                + "durability to mine the block."
                );
            }

            inventoryBusyTicks++;

            if (
                    inventoryBusyTicks
                            > MAX_INVENTORY_BUSY_TICKS
            ) {
                return fail(
                        client,
                        "the inventory remained unavailable "
                                + "while acquiring a pickaxe."
                );
            }

            return BehaviorResult.KEEP_RUNNING;
        }

        pickaxeLease = lease.get();

        leaseWaitTicks = 0;
        inventoryBusyTicks = 0;

        phase =
                CopperProcessingPhase.WAIT_FOR_PICKAXE;

        return BehaviorResult.KEEP_RUNNING;
    }

    private BehaviorResult waitForPickaxe(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        if (pickaxeLease == null) {
            phase =
                    CopperProcessingPhase.ACQUIRE_PICKAXE;

            return BehaviorResult.KEEP_RUNNING;
        }

        int selectedSlot =
                client.player
                        .getInventory()
                        .getSelectedSlot();

        if (selectedSlot != previousHotbarSlot) {
            return fail(
                    client,
                    "the selected hotbar slot was changed manually."
            );
        }

        if (pickaxeLease.isReady(client)) {
            phase =
                    CopperProcessingPhase.SELECT_PICKAXE;

            return BehaviorResult.KEEP_RUNNING;
        }

        leaseWaitTicks++;

        if (leaseWaitTicks > MAX_LEASE_WAIT_TICKS) {
            return fail(
                    client,
                    "the pickaxe inventory swap did not complete."
            );
        }

        return BehaviorResult.KEEP_RUNNING;
    }

    private BehaviorResult selectPickaxe(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        if (
                pickaxeLease == null
                        || !pickaxeLease.isReady(client)
        ) {
            phase =
                    CopperProcessingPhase.WAIT_FOR_PICKAXE;

            return BehaviorResult.KEEP_RUNNING;
        }

        client.player
                .getInventory()
                .setSelectedSlot(
                        pickaxeLease.hotbarSlot()
                );

        selectionWaitTicks = 0;

        phase =
                CopperProcessingPhase
                        .WAIT_AFTER_PICKAXE_SELECTION;

        return BehaviorResult.KEEP_RUNNING;
    }

    private BehaviorResult waitAfterPickaxeSelection(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        BlockState targetState =
                getTargetState(client);

        if (
                pickaxeLease == null
                        || client.player
                        .getInventory()
                        .getSelectedSlot()
                        != pickaxeLease.hotbarSlot()
                        || !isUsablePickaxe(
                        client.player.getMainHandItem(),
                        targetState
                )
        ) {
            return fail(
                    client,
                    "the pickaxe slot changed before mining."
            );
        }

        selectionWaitTicks++;

        if (
                selectionWaitTicks
                        <= SLOT_SELECTION_DELAY_TICKS
        ) {
            return BehaviorResult.KEEP_RUNNING;
        }

        phase =
                CopperProcessingPhase.START_MINING;

        return BehaviorResult.KEEP_RUNNING;
    }

    /*
     * ------------------------------------------------------------
     * Mining
     * ------------------------------------------------------------
     */

    private BehaviorResult startMining(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        BlockState targetState =
                getTargetState(client);

        Optional<CopperVariant> found =
                CopperCatalog.find(targetState);

        if (
                found.isEmpty()
                        || activeFamily == null
                        || found.get().family() != activeFamily
                        || !found.get().isFinalOutput()
        ) {
            phase =
                    CopperProcessingPhase.RECONCILE_WORLD;

            return BehaviorResult.KEEP_RUNNING;
        }

        boolean started =
                context.blockInteractions().startMining(
                        client,
                        station.placementPosition(),
                        station.targetInteractionFace()
                );

        if (!started) {
            return fail(
                    client,
                    "the mining interaction could not be started."
            );
        }

        miningCycleStarted = true;
        miningTicks = 0;
        pickupWaitTicks = 0;

        phase =
                CopperProcessingPhase.CONTINUE_MINING;

        return BehaviorResult.KEEP_RUNNING;
    }

    private BehaviorResult continueMining(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        BlockState targetState =
                getTargetState(client);

        /*
         * The server has confirmed that the block disappeared.
         */
        if (targetState.isAir()) {
            context.blockInteractions().stopMining(client);

            releasePickaxeLease(context, true);

            pickupWaitTicks = 0;

            phase =
                    CopperProcessingPhase.WAIT_FOR_PICKUP;

            return BehaviorResult.KEEP_RUNNING;
        }

        Optional<CopperVariant> found =
                CopperCatalog.find(targetState);

        if (
                found.isEmpty()
                        || activeFamily == null
                        || found.get().family() != activeFamily
                        || !found.get().isFinalOutput()
        ) {
            context.blockInteractions().stopMining(client);

            return fail(
                    client,
                    "the target changed while it was being mined."
            );
        }

        if (!isControlledPickaxeSelected(client, targetState)) {
            context.blockInteractions().stopMining(client);

            return fail(
                    client,
                    "the pickaxe was changed while mining."
            );
        }

        context.blockInteractions().continueMining(
                client,
                station.placementPosition(),
                station.targetInteractionFace()
        );

        miningTicks++;

        if (miningTicks > MAX_MINING_TICKS) {
            context.blockInteractions().stopMining(client);

            return fail(
                    client,
                    "the server did not confirm that "
                            + activeFamily.singularOutputName()
                            + " was mined."
            );
        }

        return BehaviorResult.KEEP_RUNNING;
    }

    /*
     * ------------------------------------------------------------
     * Pickup confirmation
     * ------------------------------------------------------------
     */

    private BehaviorResult waitForPickup(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        /*
         * ------------------------------------------------------------
         * Case 1: we have just mined a block.
         *
         * WAIT_FOR_PICKUP first serves as a one-tick server correction
         * check. We do NOT wait for the dropped item here anymore.
         * ------------------------------------------------------------
         */
        if (miningCycleStarted) {
            BlockState targetState =
                    getTargetState(client);

            /*
             * The server restored the block after the client initially
             * observed air.
             */
            if (!targetState.isAir()) {
                Optional<CopperVariant> found =
                        CopperCatalog.find(targetState);

                if (
                        found.isPresent()
                                && activeFamily != null
                                && found.get().family()
                                == activeFamily
                                && found.get().isFinalOutput()
                ) {
                    miningCycleStarted = false;
                    miningTicks = 0;
                    pickupWaitTicks = 0;

                    phase =
                            CopperProcessingPhase.INSPECT_COPPER;

                    return BehaviorResult.KEEP_RUNNING;
                }

                return fail(
                        client,
                        "an unexpected block appeared after mining."
                );
            }

            /*
             * The block is still gone one tick later.
             *
             * Count the cycle as successfully processed and immediately
             * continue. Collection may happen asynchronously while later
             * blocks are processed.
             */
            return completeCurrentCycle(context);
        }

        /*
         * ------------------------------------------------------------
         * Case 2: processing is blocked because too many drops are
         * outstanding, or all source copper has been processed and we
         * are waiting for the final drops.
         * ------------------------------------------------------------
         */

        int outstanding =
                totalOutstandingPickups(context);

        boolean processableCopperRemains =
                context.inventory().hasMatchingItem(
                        client,
                        CopperCatalog::isProcessableInput
                );

        /*
         * Everything has been collected.
         */
        if (outstanding == 0) {
            pickupWaitTicks = 0;
            lastOutstandingPickupCount = 0;

            if (!processableCopperRemains) {
                return complete(
                        createFinishedMessage()
                );
            }

            phase =
                    CopperProcessingPhase.VALIDATE_CYCLE;

            return BehaviorResult.KEEP_RUNNING;
        }

        /*
         * At least one pending item was collected since the previous
         * blocked tick.
         */
        if (
                lastOutstandingPickupCount == 0
                        || outstanding
                        < lastOutstandingPickupCount
        ) {
            pickupWaitTicks = 0;
        }

        lastOutstandingPickupCount =
                outstanding;

        /*
         * We were waiting because the outstanding threshold had been
         * reached, but enough items have now been collected to safely
         * resume.
         */
        if (
                processableCopperRemains
                        && outstanding
                        < MAX_TEMPORARILY_UNCOLLECTED_ITEMS
        ) {
            pickupWaitTicks = 0;
            lastOutstandingPickupCount = 0;

            phase =
                    CopperProcessingPhase.VALIDATE_CYCLE;

            return BehaviorResult.KEEP_RUNNING;
        }

        /*
         * Either:
         *
         * - the outstanding threshold is still full; or
         * - there is no source copper left, so AFKing must drain all
         *   remaining drops before reporting successful completion.
         */
        pickupWaitTicks++;

        if (
                pickupWaitTicks
                        > PICKUP_CONFIRMATION_TICKS
        ) {
            return fail(
                    client,
                    outstanding
                            + " processed copper item"
                            + (outstanding == 1 ? " remains" : "s remain")
                            + " uncollected."
            );
        }

        return BehaviorResult.KEEP_RUNNING;
    }

    private BehaviorResult waitBetweenCycles(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        BlockState targetState =
                getTargetState(client);

        /*
         * Pickup confirmation occurred only after the target became air.
         * If something appears here between cycles, do not overwrite or
         * process it automatically.
         */
        if (!targetState.isAir()) {
            return fail(
                    client,
                    "the station's placement position became "
                            + "occupied between processing cycles."
            );
        }

        interCycleWaitTicks++;

        if (
                interCycleWaitTicks
                        <= INTER_CYCLE_DELAY_TICKS
        ) {
            return BehaviorResult.KEEP_RUNNING;
        }

        StationValidation validation =
                station.validateReadyForPlacement(client);

        if (!validation.valid()) {
            return fail(
                    client,
                    validation.errorMessage()
            );
        }

        interCycleWaitTicks = 0;

        int outstanding =
                totalOutstandingPickups(context);

        if (
                outstanding
                        >= MAX_TEMPORARILY_UNCOLLECTED_ITEMS
        ) {
            pickupWaitTicks = 0;
            lastOutstandingPickupCount =
                    outstanding;

            phase =
                    CopperProcessingPhase.WAIT_FOR_PICKUP;

            return BehaviorResult.KEEP_RUNNING;
        }

        phase =
                CopperProcessingPhase.VALIDATE_CYCLE;

        return BehaviorResult.KEEP_RUNNING;
    }

    /*
     * ------------------------------------------------------------
     * Inventory and item helpers
     * ------------------------------------------------------------
     */

    private boolean isActiveSourceItem(
            ItemStack stack
    ) {
        return activeSourceVariant != null
                && !stack.isEmpty()
                && stack.getItem()
                == activeSourceVariant.item();
    }

    private boolean isAxe(ItemStack stack) {
        return !stack.isEmpty()
                && stack.is(ItemTags.AXES);
    }

    private boolean isUsableAxe(
            ItemStack stack,
            int requiredDurability
    ) {
        if (!isAxe(stack)) {
            return false;
        }

        /*
         * Non-damageable modded axes are effectively unlimited.
         */
        if (!stack.isDamageableItem()) {
            return true;
        }

        int remainingDurability =
                stack.getMaxDamage()
                        - stack.getDamageValue();

        /*
         * MIN_AXE_REMAINING_DURABILITY is a reserve AFTER all remaining
         * scraping operations have completed.
         *
         * Example:
         *
         * 14 durability remaining
         * 4 axe uses required
         * 10 durability remaining afterwards
         *
         * Accepted.
         */
        return remainingDurability
                >= MIN_AXE_REMAINING_DURABILITY
                + requiredDurability;
    }

    private boolean isPickaxe(ItemStack stack) {
        return !stack.isEmpty()
                && stack.is(ItemTags.PICKAXES);
    }

    private boolean isCorrectPickaxe(
            ItemStack stack,
            BlockState targetState
    ) {
        if (!isPickaxe(stack)) {
            return false;
        }

        return stack
                .getItem()
                .isCorrectToolForDrops(
                        stack,
                        targetState
                );
    }

    private boolean isUsablePickaxe(
            ItemStack stack,
            BlockState targetState
    ) {
        if (
                !isCorrectPickaxe(
                        stack,
                        targetState
                )
        ) {
            return false;
        }

        /*
         * A non-damageable modded pickaxe is treated as having unlimited
         * durability.
         */
        if (!stack.isDamageableItem()) {
            return true;
        }

        int remainingDurability =
                stack.getMaxDamage()
                        - stack.getDamageValue();

        return remainingDurability
                >= MIN_PICKAXE_REMAINING_DURABILITY + 1;
    }

    private boolean isControlledPickaxeSelected(
            Minecraft client,
            BlockState targetState
    ) {
        if (
                client.player == null
                        || pickaxeLease == null
        ) {
            return false;
        }

        return client.player
                .getInventory()
                .getSelectedSlot()
                == pickaxeLease.hotbarSlot()
                && isUsablePickaxe(
                client.player.getMainHandItem(),
                targetState
        );
    }

    private boolean isCurrentOutputItem(
            ItemStack stack
    ) {
        return activeFamily != null
                && !stack.isEmpty()
                && stack.getItem()
                == activeFamily
                .finalOutput()
                .item();
    }

    private boolean isControlledCopperSelected(
            Minecraft client
    ) {
        if (
                client.player == null
                        || copperLease == null
                        || activeSourceVariant == null
        ) {
            return false;
        }

        return client.player
                .getInventory()
                .getSelectedSlot()
                == copperLease.hotbarSlot()
                && isActiveSourceItem(
                client.player.getMainHandItem()
        );
    }

    private void releaseCopperLease(
            AutomationContext context,
            boolean restoreSelection
    ) {
        if (copperLease == null) {
            return;
        }

        releaseLease(
                context,
                copperLease,
                restoreSelection
        );

        copperLease = null;
    }

    private void releaseAxeLease(
            AutomationContext context,
            boolean restoreSelection
    ) {
        if (axeLease == null) {
            return;
        }

        releaseLease(
                context,
                axeLease,
                restoreSelection
        );

        axeLease = null;
    }

    private void releasePickaxeLease(
            AutomationContext context,
            boolean restoreSelection
    ) {
        if (pickaxeLease == null) {
            return;
        }

        releaseLease(
                context,
                pickaxeLease,
                restoreSelection
        );

        pickaxeLease = null;
    }

    private void releaseLease(
            AutomationContext context,
            InventoryService.HotbarLease lease,
            boolean restoreSelection
    ) {
        Minecraft client = context.client();

        int controlledSlot =
                lease.hotbarSlot();

        boolean shouldRestore =
                restoreSelection
                        && client.player != null
                        && previousHotbarSlot >= 0
                        && client.player
                        .getInventory()
                        .getSelectedSlot()
                        == controlledSlot;

        lease.release(client);

        if (
                shouldRestore
                        && client.player != null
        ) {
            client.player
                    .getInventory()
                    .setSelectedSlot(
                            previousHotbarSlot
                    );
        }

        previousHotbarSlot = -1;
    }

    /*
     * ------------------------------------------------------------
     * State and result helpers
     * ------------------------------------------------------------
     */

    private BlockState getTargetState(
            Minecraft client
    ) {
        return client.level.getBlockState(
                station.placementPosition()
        );
    }

    private BehaviorResult complete(
            String message
    ) {
        completionMessage = message;

        return BehaviorResult.COMPLETE_BEHAVIOR;
    }

    private BehaviorResult fail(
            Minecraft client,
            String reason
    ) {
        sendMessage(
                client,
                "AFKing: copper processing stopped — "
                        + reason
        );

        return BehaviorResult.DISABLE_BEHAVIOR;
    }

    private void clearTransientActionState() {
        leaseWaitTicks = 0;
        selectionWaitTicks = 0;
        inventoryBusyTicks = 0;

        placementWaitTicks = 0;
        placementAttempts = 0;

        scrapeWaitTicks = 0;
        scrapeAttempts = 0;

        miningTicks = 0;
        pickupWaitTicks = 0;
        lastOutstandingPickupCount = 0;

        interCycleWaitTicks = 0;

        scrapeSourceVariant = null;
        expectedVariantAfterScrape = null;
    }

    private void resetSessionState() {
        station = null;

        phase =
                CopperProcessingPhase.RECONCILE_WORLD;

        copperLease = null;
        axeLease = null;
        pickaxeLease = null;

        activeSourceVariant = null;
        activeFamily = null;

        scrapeSourceVariant = null;
        expectedVariantAfterScrape = null;

        hasHeldControl = false;
        previousHotbarSlot = -1;

        miningCycleStarted = false;
        cycleCopperPlaced = false;

        initialOutputCountsByFamily.clear();
        lastOutstandingPickupCount = 0;

        completedCycles = 0;
        completedByFamily.clear();

        completionMessage = null;

        clearTransientActionState();
    }

    private BehaviorResult completeCurrentCycle(
            AutomationContext context
    ) {
        context.blockInteractions().stopMining(
                context.client()
        );

        releaseCopperLease(context, true);
        releaseAxeLease(context, true);
        releasePickaxeLease(context, true);

        if (activeFamily == null) {
            return fail(
                    context.client(),
                    "the completed copper family is unknown."
            );
        }

        completedCycles++;

        completedByFamily.merge(
                activeFamily,
                1,
                Integer::sum
        );

        miningCycleStarted = false;
        cycleCopperPlaced = false;

        completionMessage = null;

        clearActiveCycleSelection();
        clearTransientActionState();

        phase =
                CopperProcessingPhase.WAIT_BETWEEN_CYCLES;

        return BehaviorResult.KEEP_RUNNING;
    }

    private void clearActiveCycleSelection() {
        activeSourceVariant = null;
        activeFamily = null;
    }

    private String createFinishedMessage() {
        if (completedCycles == 0) {
            return "AFKing: no processable affected copper "
                    + "was found in the inventory. "
                    + "Waxed unaffected copper is intentionally ignored. "
                    + "Copper processing is now OFF.";
        }

        StringJoiner processed =
                new StringJoiner(", ");

        for (
                Map.Entry<CopperFamily, Integer> entry
                : completedByFamily.entrySet()
        ) {
            CopperFamily family =
                    entry.getKey();

            int count =
                    entry.getValue();

            String name =
                    count == 1
                            ? family.singularOutputName()
                            : family.pluralOutputName();

            processed.add(
                    count + " " + name
            );
        }

        return "AFKing: copper processing finished — "
                + processed
                + " processed and collected. "
                + "No processable affected copper remains. "
                + "Copper processing is now OFF.";
    }

    private void sendMessage(
            Minecraft client,
            String message
    ) {
        if (client.player != null) {
            client.player.sendSystemMessage(
                    Component.literal(message)
            );
        }
    }
}