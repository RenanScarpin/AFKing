package dev.renan.afking.client.behavior;

import dev.renan.afking.client.automation.AutomationContext;
import dev.renan.afking.client.automation.AutonomousBehavior;
import dev.renan.afking.client.automation.BehaviorResult;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Set;

public final class AutoEatBehavior
        implements AutonomousBehavior {

    public static final String ID = "auto_eat";

    private static final int PRIORITY = 800;

    private static final Set<Item> ALLOWED_FOODS = Set.of(
            Items.BREAD,
            Items.BAKED_POTATO,
            Items.COOKED_BEEF,
            Items.COOKED_PORKCHOP,
            Items.COOKED_CHICKEN,
            Items.COOKED_MUTTON,
            Items.COOKED_RABBIT,
            Items.COOKED_COD,
            Items.COOKED_SALMON,
            Items.APPLE,
            Items.CARROT,
            Items.PUMPKIN_PIE
    );

    private final int startEatingHunger;
    private final int stopEatingHunger;

    private boolean eating;
    private boolean noFoodWarningShown;

    private int previousHotbarSlot = -1;
    private int selectedFoodSlot = -1;

    public AutoEatBehavior(
            int startEatingHunger,
            int stopEatingHunger
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

        if (eating) {
            return true;
        }

        int hunger =
                client.player.getFoodData().getFoodLevel();

        return hunger <= startEatingHunger;
    }

    @Override
    public BehaviorResult tick(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        if (client.player == null) {
            resetState(context, false);
            return BehaviorResult.RELEASE_CONTROL;
        }

        int hunger =
                client.player.getFoodData().getFoodLevel();

        if (eating) {
            return continueEating(context, hunger);
        }

        if (hunger > startEatingHunger) {
            noFoodWarningShown = false;
            return BehaviorResult.RELEASE_CONTROL;
        }

        /*
         * Do not interrupt an item already being used manually.
         */
        if (client.player.isUsingItem()) {
            return BehaviorResult.KEEP_RUNNING;
        }

        int foodSlot = findAllowedFoodInHotbar(client);

        if (foodSlot < 0) {
            showNoFoodWarning(client);
            return BehaviorResult.KEEP_RUNNING;
        }

        startEating(context, foodSlot);

        return continueEating(context, hunger);
    }

    @Override
    public void stop(AutomationContext context) {
        resetState(context, true);
    }

    private void startEating(
            AutomationContext context,
            int foodSlot
    ) {
        Minecraft client = context.client();

        previousHotbarSlot =
                client.player
                        .getInventory()
                        .getSelectedSlot();

        selectedFoodSlot = foodSlot;
        eating = true;
        noFoodWarningShown = false;

        client.player
                .getInventory()
                .setSelectedSlot(foodSlot);
    }

    private BehaviorResult continueEating(
            AutomationContext context,
            int currentHunger
    ) {
        Minecraft client = context.client();

        if (currentHunger >= stopEatingHunger) {
            resetState(context, true);
            return BehaviorResult.RELEASE_CONTROL;
        }

        int currentSlot =
                client.player
                        .getInventory()
                        .getSelectedSlot();

        /*
         * Stop if the player manually changes slots.
         */
        if (currentSlot != selectedFoodSlot) {
            resetState(context, false);
            return BehaviorResult.RELEASE_CONTROL;
        }

        ItemStack selectedStack =
                client.player.getMainHandItem();

        /*
         * The previous food stack may have become empty.
         */
        if (!isAllowedFood(selectedStack)) {
            int replacementSlot =
                    findAllowedFoodInHotbar(client);

            if (replacementSlot < 0) {
                resetState(context, true);
                showNoFoodWarning(client);

                return BehaviorResult.KEEP_RUNNING;
            }

            selectedFoodSlot = replacementSlot;

            client.player
                    .getInventory()
                    .setSelectedSlot(replacementSlot);

            selectedStack =
                    client.player.getMainHandItem();

            if (!isAllowedFood(selectedStack)) {
                resetState(context, true);
                return BehaviorResult.RELEASE_CONTROL;
            }
        }

        context.input().press(client.options.keyUse);

        if (
                !client.player.isUsingItem()
                        && client.gameMode != null
        ) {
            client.gameMode.useItem(
                    client.player,
                    InteractionHand.MAIN_HAND
            );
        }

        return BehaviorResult.KEEP_RUNNING;
    }

    private int findAllowedFoodInHotbar(
            Minecraft client
    ) {
        int currentSlot =
                client.player
                        .getInventory()
                        .getSelectedSlot();

        ItemStack currentStack =
                client.player
                        .getInventory()
                        .getItem(currentSlot);

        if (isAllowedFood(currentStack)) {
            return currentSlot;
        }

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack =
                    client.player
                            .getInventory()
                            .getItem(slot);

            if (isAllowedFood(stack)) {
                return slot;
            }
        }

        return -1;
    }

    private boolean isAllowedFood(ItemStack stack) {
        return !stack.isEmpty()
                && ALLOWED_FOODS.contains(stack.getItem());
    }

    private void resetState(
            AutomationContext context,
            boolean restorePreviousSlot
    ) {
        Minecraft client = context.client();

        context.input().release(client.options.keyUse);

        if (
                restorePreviousSlot
                        && client.player != null
                        && previousHotbarSlot >= 0
                        && selectedFoodSlot >= 0
                        && client.player
                        .getInventory()
                        .getSelectedSlot()
                        == selectedFoodSlot
        ) {
            client.player
                    .getInventory()
                    .setSelectedSlot(previousHotbarSlot);
        }

        eating = false;
        previousHotbarSlot = -1;
        selectedFoodSlot = -1;
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
                                    + "in the hotbar."
                    )
            );
        }

        noFoodWarningShown = true;
    }
}