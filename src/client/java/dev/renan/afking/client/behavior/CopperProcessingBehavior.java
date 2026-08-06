package dev.renan.afking.client.behavior;

import dev.renan.afking.client.automation.AutomationContext;
import dev.renan.afking.client.automation.AutonomousBehavior;
import dev.renan.afking.client.automation.BehaviorResult;
import dev.renan.afking.client.automation.BehaviorStopReason;
import dev.renan.afking.client.copper.CopperStation;
import dev.renan.afking.client.copper.CopperTransformationChain;
import dev.renan.afking.client.copper.StationValidation;
import dev.renan.afking.client.service.InventoryService;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

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
     * Three seconds to allow the dropped item to reach the player.
     */
    private static final int PICKUP_CONFIRMATION_TICKS = 60;

    /*
     * Leaves a safety margin rather than using a pickaxe until it is
     * almost broken.
     */
    private static final int MIN_PICKAXE_REMAINING_DURABILITY = 10;

    /*
     * Short buffer between successful cycles.
     */
    private static final int INTER_CYCLE_DELAY_TICKS = 5;

    /*
     * A new Waxed Oxidized Copper block always needs four axe uses:
     * one to remove wax and three to reverse oxidation.
     */
    private static final int AXE_USES_FOR_NEW_CYCLE = 4;

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

    private int copperCountBeforeMining;

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
     * State recorded immediately before sending one axe interaction.
     */
    private Block scrapeSourceBlock;
    private Block expectedBlockAfterScrape;

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
            /*
             * Mining may have completed while automatic eating had
             * control.
             */
            if (miningCycleStarted) {
                phase =
                        CopperProcessingPhase.WAIT_FOR_PICKUP;

                return BehaviorResult.KEEP_RUNNING;
            }

            /*
             * Copper previously existed here but disappeared before
             * AFKing began mining it.
             */
            if (cycleCopperPlaced) {
                return fail(
                        client,
                        "the copper block disappeared before "
                                + "AFKing could mine it."
                );
            }

            /*
             * Beginning a fresh cycle now requires a complete resource
             * validation before another block is placed.
             */
            phase =
                    CopperProcessingPhase.VALIDATE_CYCLE;

            return BehaviorResult.KEEP_RUNNING;
        }

        if (
                CopperTransformationChain
                        .isSupportedState(targetState)
        ) {
            cycleCopperPlaced = true;

            /*
             * If mining was interrupted while the block still exists,
             * restart mining from observed world state.
             */
            miningCycleStarted = false;
            pickupWaitTicks = 0;

            phase =
                    CopperProcessingPhase.INSPECT_COPPER;

            return BehaviorResult.KEEP_RUNNING;
        }

        return fail(
                client,
                "the station contains an unexpected block."
        );
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
         * No more source blocks means the complete repetitive task
         * finished normally.
         */
        boolean copperExists =
                context.inventory().hasMatchingItem(
                        client,
                        this::isWaxedOxidizedCopperItem
                );

        if (!copperExists) {
            return complete(
                    createFinishedMessage()
            );
        }

        /*
         * Check the axe before placing another block. This avoids
         * leaving a newly placed Waxed Oxidized Copper block because
         * no suitable axe was available.
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
                                AXE_USES_FOR_NEW_CYCLE
                        )
                );

        if (!usableAxeExists) {
            return fail(
                    client,
                    "no axe has the "
                            + AXE_USES_FOR_NEW_CYCLE
                            + " durability points required "
                            + "for another complete cycle."
            );
        }

        /*
         * Check the pickaxe before placing another block as well.
         */
        BlockState finalCopperState =
                CopperTransformationChain
                        .PLAIN
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
                                finalCopperState
                        )
                );

        if (!correctPickaxeExists) {
            return fail(
                    client,
                    "no available pickaxe can harvest "
                            + "a Block of Copper."
            );
        }

        boolean usablePickaxeExists =
                context.inventory().hasMatchingItem(
                        client,
                        stack -> isUsablePickaxe(
                                stack,
                                finalCopperState
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
                        this::isWaxedOxidizedCopperItem,
                        previousHotbarSlot
                );

        if (lease.isEmpty()) {
            boolean copperExists =
                    context.inventory().hasMatchingItem(
                            client,
                            this::isWaxedOxidizedCopperItem
                    );

            if (!copperExists) {
                return complete(
                        createFinishedMessage()
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
                    "the Waxed Oxidized Copper slot "
                            + "was changed before placement."
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
                    "Waxed Oxidized Copper is no longer selected."
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

        /*
         * Accept any recognized processing stage. Normally this is
         * Waxed Oxidized Copper, but this also handles an external
         * axe interaction occurring immediately after placement.
         */
        if (
                CopperTransformationChain
                        .isSupportedState(targetState)
        ) {
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

        if (
                !CopperTransformationChain
                        .isSupportedState(targetState)
        ) {
            return fail(
                    client,
                    "the station contains an unsupported block."
            );
        }

        cycleCopperPlaced = true;

        if (
                CopperTransformationChain
                        .isFinalState(targetState)
        ) {
            /*
             * Scraping is finished. Restore the axe before acquiring
             * another tool.
             */
            releaseAxeLease(context, true);

            ItemStack expectedOutput =
                    CopperTransformationChain
                            .PLAIN_ITEM
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
                                + "the processed Block of Copper."
                );
            }

            phase =
                    CopperProcessingPhase.ACQUIRE_PICKAXE;

            return BehaviorResult.KEEP_RUNNING;
        }

        int requiredDurability =
                CopperTransformationChain
                        .remainingAxeUses(targetState);

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

        BlockState targetState =
                getTargetState(client);

        if (
                !CopperTransformationChain
                        .isSupportedState(targetState)
        ) {
            phase =
                    CopperProcessingPhase.RECONCILE_WORLD;

            return BehaviorResult.KEEP_RUNNING;
        }

        int requiredDurability =
                CopperTransformationChain
                        .remainingAxeUses(targetState);

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
                return fail(
                        client,
                        "no axe has the "
                                + requiredDurability
                                + " durability points required "
                                + "to finish scraping this block."
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

        int requiredDurability =
                CopperTransformationChain
                        .remainingAxeUses(targetState);

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

        BlockState currentState =
                getTargetState(client);

        if (
                CopperTransformationChain
                        .isFinalState(currentState)
        ) {
            phase =
                    CopperProcessingPhase.INSPECT_COPPER;

            return BehaviorResult.KEEP_RUNNING;
        }

        Optional<Block> expectedNext =
                CopperTransformationChain
                        .expectedAfterAxe(currentState);

        if (expectedNext.isEmpty()) {
            return fail(
                    client,
                    "the copper block is no longer in "
                            + "a supported scraping stage."
            );
        }

        int requiredDurability =
                CopperTransformationChain
                        .remainingAxeUses(currentState);

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

        scrapeSourceBlock =
                currentState.getBlock();

        expectedBlockAfterScrape =
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

        BlockState currentState =
                getTargetState(client);

        Block currentBlock =
                currentState.getBlock();

        if (
                expectedBlockAfterScrape != null
                        && currentBlock
                        == expectedBlockAfterScrape
        ) {
            /*
             * Exact expected transition confirmed.
             */
            scrapeAttempts = 0;
            scrapeWaitTicks = 0;

            scrapeSourceBlock = null;
            expectedBlockAfterScrape = null;

            phase =
                    CopperProcessingPhase.INSPECT_COPPER;

            return BehaviorResult.KEEP_RUNNING;
        }

        /*
         * The block changed, but not to the exact expected stage.
         *
         * If it remains in the supported chain, re-inspect it. This
         * safely handles another player scraping it at the same time.
         */
        if (
                scrapeSourceBlock != null
                        && currentBlock != scrapeSourceBlock
        ) {
            if (
                    CopperTransformationChain
                            .isSupportedState(currentState)
            ) {
                scrapeAttempts = 0;
                scrapeWaitTicks = 0;

                scrapeSourceBlock = null;
                expectedBlockAfterScrape = null;

                phase =
                        CopperProcessingPhase.INSPECT_COPPER;

                return BehaviorResult.KEEP_RUNNING;
            }

            return fail(
                    client,
                    "the copper block changed into "
                            + "an unexpected block."
            );
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
            String stage =
                    CopperTransformationChain.describe(
                            currentState
                    );

            return fail(
                    client,
                    "the server did not confirm scraping "
                            + stage
                            + " after "
                            + scrapeAttempts
                            + " attempts."
            );
        }

        /*
         * Retry the same stage after the confirmation timeout.
         */
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

        BlockState targetState =
                getTargetState(client);

        if (
                !CopperTransformationChain
                        .isFinalState(targetState)
        ) {
            phase =
                    CopperProcessingPhase.RECONCILE_WORLD;

            return BehaviorResult.KEEP_RUNNING;
        }

        ItemStack expectedOutput =
                CopperTransformationChain
                        .PLAIN_ITEM
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
                            + "the processed Block of Copper."
            );
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
                                + "a Block of Copper."
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

        if (
                !CopperTransformationChain
                        .isFinalState(targetState)
        ) {
            phase =
                    CopperProcessingPhase.RECONCILE_WORLD;

            return BehaviorResult.KEEP_RUNNING;
        }

        if (!isControlledPickaxeSelected(client, targetState)) {
            return fail(
                    client,
                    "the required pickaxe is no longer selected."
            );
        }

        ItemStack expectedOutput =
                CopperTransformationChain
                        .PLAIN_ITEM
                        .getDefaultInstance();

        if (
                !context.inventory().canAccept(
                        client,
                        expectedOutput
                )
        ) {
            return fail(
                    client,
                    "the inventory no longer has room for "
                            + "the processed Block of Copper."
            );
        }

        copperCountBeforeMining =
                context.inventory().countMatching(
                        client,
                        this::isPlainCopperItem
                );

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

        if (
                !CopperTransformationChain
                        .isFinalState(targetState)
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
                    "the server did not confirm that the "
                            + "Block of Copper was mined."
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

        BlockState targetState =
                getTargetState(client);

        /*
         * A server correction may restore the block after the client
         * initially observed air.
         */
        if (!targetState.isAir()) {
            if (
                    CopperTransformationChain
                            .isFinalState(targetState)
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
                    "an unexpected block appeared while "
                            + "waiting for the copper item."
            );
        }

        int currentCopperCount =
                context.inventory().countMatching(
                        client,
                        this::isPlainCopperItem
                );

        if (
                currentCopperCount
                        > copperCountBeforeMining
        ) {
            return completeCurrentCycle(context);
        }

        pickupWaitTicks++;

        if (
                pickupWaitTicks
                        > PICKUP_CONFIRMATION_TICKS
        ) {
            return fail(
                    client,
                    "the processed copper block was broken, "
                            + "but its item was not collected."
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

        phase =
                CopperProcessingPhase.VALIDATE_CYCLE;

        return BehaviorResult.KEEP_RUNNING;
    }

    /*
     * ------------------------------------------------------------
     * Inventory and item helpers
     * ------------------------------------------------------------
     */

    private boolean isWaxedOxidizedCopperItem(
            ItemStack stack
    ) {
        return !stack.isEmpty()
                && stack.getItem()
                == Items.COPPER_BLOCK
                .waxed()
                .oxidized();
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
         * Non-damageable modded axes are treated as having unlimited
         * durability.
         */
        if (!stack.isDamageableItem()) {
            return true;
        }

        int remainingDurability =
                stack.getMaxDamage()
                        - stack.getDamageValue();

        return remainingDurability
                >= requiredDurability;
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
                >= MIN_PICKAXE_REMAINING_DURABILITY;
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

    private boolean isPlainCopperItem(
            ItemStack stack
    ) {
        return !stack.isEmpty()
                && stack.getItem()
                == CopperTransformationChain.PLAIN_ITEM;
    }

    private boolean isControlledCopperSelected(
            Minecraft client
    ) {
        if (
                client.player == null
                        || copperLease == null
        ) {
            return false;
        }

        return client.player
                .getInventory()
                .getSelectedSlot()
                == copperLease.hotbarSlot()
                && isWaxedOxidizedCopperItem(
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

        interCycleWaitTicks = 0;

        scrapeSourceBlock = null;
        expectedBlockAfterScrape = null;
    }

    private void resetSessionState() {
        station = null;

        phase =
                CopperProcessingPhase.RECONCILE_WORLD;

        copperLease = null;
        axeLease = null;
        pickaxeLease = null;

        hasHeldControl = false;
        previousHotbarSlot = -1;

        miningCycleStarted = false;
        cycleCopperPlaced = false;

        copperCountBeforeMining = 0;
        completedCycles = 0;

        completionMessage = null;

        clearTransientActionState();
    }

    private BehaviorResult completeCurrentCycle(
            AutomationContext context
    ) {
        /*
         * These should already be released, but doing it here makes the
         * cycle boundary robust against future state-machine changes.
         */
        context.blockInteractions().stopMining(
                context.client()
        );

        releaseCopperLease(context, true);
        releaseAxeLease(context, true);
        releasePickaxeLease(context, true);

        completedCycles++;

        /*
         * Clear only state belonging to the completed block.
         *
         * The station, F9 session, behavior enablement, and completed
         * cycle count remain intact.
         */
        miningCycleStarted = false;
        cycleCopperPlaced = false;

        copperCountBeforeMining = 0;

        completionMessage = null;

        clearTransientActionState();

        phase =
                CopperProcessingPhase.WAIT_BETWEEN_CYCLES;

        return BehaviorResult.KEEP_RUNNING;
    }

    private String createFinishedMessage() {
        if (completedCycles == 0) {
            return "AFKing: no Waxed Oxidized Copper "
                    + "was found in the inventory. "
                    + "Copper processing is now OFF.";
        }

        String processedBlocks =
                completedCycles == 1
                        ? "1 Block of Copper"
                        : completedCycles
                        + " Blocks of Copper";

        return "AFKing: copper processing finished — "
                + processedBlocks
                + " processed and collected. "
                + "No Waxed Oxidized Copper remains. "
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