package dev.renan.afking.client.behavior;

import dev.renan.afking.client.automation.AutomationContext;
import dev.renan.afking.client.automation.AutonomousBehavior;
import dev.renan.afking.client.automation.BehaviorResult;
import dev.renan.afking.client.food.FoodPolicy;
import dev.renan.afking.client.service.InventoryService;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.Optional;

public final class AutoEatBehavior
        implements AutonomousBehavior {

    public static final String ID = "auto_eat";

    private static final int PRIORITY = 800;

    /*
     * Maximum time to wait for an inventory-to-hotbar swap
     * to become visible on the client.
     */
    private static final int MAX_LEASE_WAIT_TICKS = 10;

    private final int startEatingHunger;
    private final int stopEatingHunger;
    private final FoodPolicy foodPolicy;

    /*
     * Only used when food must be selected from the hotbar
     * or moved from the main inventory.
     *
     * Offhand food does not require a lease.
     */
    private InventoryService.HotbarLease foodLease;

    /*
     * Null while no food is actively being consumed.
     *
     * OFF_HAND means AFKing is eating directly from the offhand.
     * MAIN_HAND means AFKing is using a borrowed hotbar slot.
     */
    private InteractionHand eatingHand;

    private boolean noFoodWarningShown;

    /*
     * Only relevant when using a hotbar lease.
     */
    private int previousHotbarSlot = -1;
    private int leaseWaitTicks;

    public AutoEatBehavior(
            int startEatingHunger,
            int stopEatingHunger,
            FoodPolicy foodPolicy
    ) {
        if (
                startEatingHunger < 0
                        || startEatingHunger > 20
        ) {
            throw new IllegalArgumentException(
                    "Start hunger must be between 0 and 20."
            );
        }

        if (
                stopEatingHunger < startEatingHunger
                        || stopEatingHunger > 20
        ) {
            throw new IllegalArgumentException(
                    "Stop hunger must be between "
                            + "the start threshold and 20."
            );
        }

        this.startEatingHunger = startEatingHunger;
        this.stopEatingHunger = stopEatingHunger;

        this.foodPolicy = Objects.requireNonNull(
                foodPolicy,
                "foodPolicy"
        );
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public boolean shouldRun(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        if (client.player == null) {
            return false;
        }

        /*
         * Keep control while eating, preparing a hotbar item,
         * or restoring an inventory lease.
         */
        if (eatingHand != null || foodLease != null) {
            return true;
        }

        int hunger =
                client.player
                        .getFoodData()
                        .getFoodLevel();

        return hunger <= startEatingHunger;
    }

    @Override
    public BehaviorResult tick(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        if (client.player == null) {
            finishEating(context, false);
            return BehaviorResult.RELEASE_CONTROL;
        }

        int hunger =
                client.player
                        .getFoodData()
                        .getFoodLevel();

        /*
         * Stop after reaching the target hunger level.
         */
        if (hunger >= stopEatingHunger) {
            finishEating(context, true);
            noFoodWarningShown = false;

            return BehaviorResult.RELEASE_CONTROL;
        }

        /*
         * Continue an item-use action already started by AFKing.
         */
        if (eatingHand != null) {
            return continueEating(context);
        }

        /*
         * A main-inventory item was borrowed, but AFKing is still
         * waiting for the swap to complete.
         */
        if (foodLease != null) {
            return prepareLeasedFood(context);
        }

        /*
         * No food source has been selected yet.
         */
        return acquireFood(context);
    }

    @Override
    public void stop(AutomationContext context) {
        finishEating(context, true);
        noFoodWarningShown = false;
    }

    private BehaviorResult acquireFood(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        /*
         * Avoid interrupting an item-use action started manually.
         */
        if (client.player.isUsingItem()) {
            return BehaviorResult.KEEP_RUNNING;
        }

        /*
         * Priority 1: offhand.
         *
         * It requires no slot change and no inventory interaction.
         */
        ItemStack offhandStack =
                client.player.getItemInHand(
                        InteractionHand.OFF_HAND
                );

        if (isAllowedFood(offhandStack)) {
            eatingHand = InteractionHand.OFF_HAND;
            noFoodWarningShown = false;

            return continueEating(context);
        }

        /*
         * Remaining priority:
         *
         * 2. Currently selected hotbar slot
         * 3. Other hotbar slots
         * 4. Main inventory
         *
         * InventoryService already implements this ordering.
         */
        previousHotbarSlot =
                client.player
                        .getInventory()
                        .getSelectedSlot();

        Optional<InventoryService.HotbarLease> lease =
                context.inventory().acquireToHotbar(
                        client,
                        this::isAllowedFood,
                        previousHotbarSlot
                );

        if (lease.isEmpty()) {
            previousHotbarSlot = -1;

            /*
             * Because the offhand was checked first, this search now
             * needs only to inspect the hotbar and main inventory.
             */
            boolean foodExistsInInventory =
                    context.inventory().hasMatchingItem(
                            client,
                            this::isAllowedFood
                    );

            if (!foodExistsInInventory) {
                showNoFoodWarning(client);
            }

            return BehaviorResult.KEEP_RUNNING;
        }

        foodLease = lease.get();
        leaseWaitTicks = 0;
        noFoodWarningShown = false;

        /*
         * Wait until the following tick before selecting or using
         * the leased item.
         */
        return BehaviorResult.KEEP_RUNNING;
    }

    private BehaviorResult prepareLeasedFood(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        int currentSlot =
                client.player
                        .getInventory()
                        .getSelectedSlot();

        /*
         * Respect manual intervention while waiting for the swap.
         */
        if (currentSlot != previousHotbarSlot) {
            finishEating(context, false);
            return BehaviorResult.RELEASE_CONTROL;
        }

        if (!foodLease.isReady(client)) {
            leaseWaitTicks++;

            if (leaseWaitTicks > MAX_LEASE_WAIT_TICKS) {
                finishEating(context, true);

                client.player.sendSystemMessage(
                        Component.literal(
                                "AFKing: the food inventory swap "
                                        + "did not complete."
                        )
                );

                return BehaviorResult.RELEASE_CONTROL;
            }

            return BehaviorResult.KEEP_RUNNING;
        }

        client.player
                .getInventory()
                .setSelectedSlot(foodLease.hotbarSlot());

        eatingHand = InteractionHand.MAIN_HAND;

        return continueEating(context);
    }

    private BehaviorResult continueEating(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        if (eatingHand == null) {
            return BehaviorResult.RELEASE_CONTROL;
        }

        ItemStack foodStack;

        if (eatingHand == InteractionHand.OFF_HAND) {
            /*
             * No selected-slot check is needed for offhand food.
             *
             * The player may change hotbar slots freely without
             * interrupting AFKing.
             */
            foodStack = client.player.getItemInHand(
                    InteractionHand.OFF_HAND
            );
        } else {
            /*
             * Main-hand eating is controlled through the leased
             * hotbar slot.
             */
            if (foodLease == null) {
                finishEating(context, false);
                return BehaviorResult.RELEASE_CONTROL;
            }

            int currentSlot =
                    client.player
                            .getInventory()
                            .getSelectedSlot();

            /*
             * A manual hotbar change cancels main-hand automation.
             */
            if (currentSlot != foodLease.hotbarSlot()) {
                finishEating(context, false);
                return BehaviorResult.RELEASE_CONTROL;
            }

            foodStack = client.player.getItemInHand(
                    InteractionHand.MAIN_HAND
            );
        }

        /*
         * The stack may have been exhausted, replaced, or converted
         * into another item, such as an empty bowl.
         *
         * Release the current source. If hunger remains low, the
         * behavior will search again during a later tick.
         */
        if (!isAllowedFood(foodStack)) {
            finishEating(context, true);
            return BehaviorResult.RELEASE_CONTROL;
        }

        context.input().press(client.options.keyUse);

        /*
         * Explicitly start using the selected hand.
         *
         * This is especially important for the offhand because the
         * main hand may contain another usable item.
         */
        if (
                !client.player.isUsingItem()
                        && client.gameMode != null
        ) {
            client.gameMode.useItem(
                    client.player,
                    eatingHand
            );
        }

        return BehaviorResult.KEEP_RUNNING;
    }

    private boolean isAllowedFood(ItemStack stack) {
        return foodPolicy.isAllowed(stack);
    }

    private void finishEating(
            AutomationContext context,
            boolean restorePreviousSelection
    ) {
        Minecraft client = context.client();

        context.input().release(client.options.keyUse);

        /*
         * Offhand eating never changes the selected hotbar slot and
         * never creates an inventory lease.
         */
        if (foodLease != null) {
            int controlledHotbarSlot =
                    foodLease.hotbarSlot();

            boolean shouldRestoreSelection =
                    restorePreviousSelection
                            && client.player != null
                            && previousHotbarSlot >= 0
                            && client.player
                            .getInventory()
                            .getSelectedSlot()
                            == controlledHotbarSlot;

            foodLease.release(client);
            foodLease = null;

            if (
                    shouldRestoreSelection
                            && client.player != null
            ) {
                client.player
                        .getInventory()
                        .setSelectedSlot(previousHotbarSlot);
            }
        }

        eatingHand = null;
        previousHotbarSlot = -1;
        leaseWaitTicks = 0;
    }

    private void showNoFoodWarning(
            Minecraft client
    ) {
        if (noFoodWarningShown) {
            return;
        }

        if (client.player != null) {
            client.player.sendSystemMessage(
                    Component.literal(
                            "AFKing: hunger is low, but no "
                                    + "approved food was found "
                                    + "in the offhand or inventory."
                    )
            );
        }

        noFoodWarningShown = true;
    }
}