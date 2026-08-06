package dev.renan.afking.client.service;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

public final class InventoryService {

    public static final int FIRST_HOTBAR_SLOT = 0;
    public static final int LAST_HOTBAR_SLOT = 8;

    public static final int FIRST_MAIN_INVENTORY_SLOT = 9;
    public static final int LAST_MAIN_INVENTORY_SLOT = 35;

    private HotbarLease activeLease;

    /*
     * Called once per client tick.
     *
     * Its main purpose is to finish a delayed restoration if a behavior
     * released an item while another container, such as a chest, was open.
     */
    public void tick(Minecraft client) {
        if (activeLease == null) {
            return;
        }

        /*
         * If the player left the world, the old inventory state is no
         * longer relevant.
         */
        if (client.player == null || client.level == null) {
            clearLease(activeLease);
            return;
        }

        if (activeLease.releaseRequested) {
            tryFinishRelease(client, activeLease);
        }
    }

    /*
     * Finds an item anywhere in the regular player inventory.
     *
     * Search order:
     * 1. Currently selected hotbar slot
     * 2. Remaining hotbar slots
     * 3. Main inventory
     *
     * Armor, crafting slots, and the offhand are intentionally excluded.
     */
    public Optional<InventorySlot> findFirst(
            Minecraft client,
            Predicate<ItemStack> matcher
    ) {
        Objects.requireNonNull(matcher, "matcher");

        if (client.player == null) {
            return Optional.empty();
        }

        int selectedSlot =
                client.player.getInventory().getSelectedSlot();

        ItemStack selectedStack =
                client.player.getInventory().getItem(selectedSlot);

        if (matches(selectedStack, matcher)) {
            return Optional.of(
                    new InventorySlot(selectedSlot, true)
            );
        }

        for (
                int slot = FIRST_HOTBAR_SLOT;
                slot <= LAST_HOTBAR_SLOT;
                slot++
        ) {
            if (slot == selectedSlot) {
                continue;
            }

            ItemStack stack =
                    client.player.getInventory().getItem(slot);

            if (matches(stack, matcher)) {
                return Optional.of(
                        new InventorySlot(slot, true)
                );
            }
        }

        for (
                int slot = FIRST_MAIN_INVENTORY_SLOT;
                slot <= LAST_MAIN_INVENTORY_SLOT;
                slot++
        ) {
            ItemStack stack =
                    client.player.getInventory().getItem(slot);

            if (matches(stack, matcher)) {
                return Optional.of(
                        new InventorySlot(slot, false)
                );
            }
        }

        return Optional.empty();
    }

    public boolean hasMatchingItem(
            Minecraft client,
            Predicate<ItemStack> matcher
    ) {
        return findFirst(client, matcher).isPresent();
    }

    /*
     * Counts matching items in the normal player inventory:
     *
     * 0-8  = hotbar
     * 9-35 = main inventory
     *
     * Equipment and offhand slots are deliberately excluded because
     * ordinary item pickup is placed into the hotbar/main inventory.
     */
    public int countMatching(
            Minecraft client,
            Predicate<ItemStack> matcher
    ) {
        Objects.requireNonNull(matcher, "matcher");

        if (client.player == null) {
            return 0;
        }

        int total = 0;

        for (
                int slot = FIRST_HOTBAR_SLOT;
                slot <= LAST_MAIN_INVENTORY_SLOT;
                slot++
        ) {
            ItemStack stack =
                    client.player
                            .getInventory()
                            .getItem(slot);

            if (matches(stack, matcher)) {
                total += stack.getCount();
            }
        }

        return total;
    }

    /*
     * Checks whether one stack can enter the regular player inventory.
     *
     * This succeeds when:
     * - an empty slot exists; or
     * - a compatible stack has remaining capacity.
     */
    public boolean canAccept(
            Minecraft client,
            ItemStack incomingStack
    ) {
        if (
                client.player == null
                        || incomingStack.isEmpty()
        ) {
            return false;
        }

        var inventory =
                client.player.getInventory();

        return inventory.getFreeSlot() >= 0
                || inventory.getSlotWithRemainingSpace(
                incomingStack
        ) >= 0;
    }

    /*
     * Temporarily makes a matching item available in the hotbar.
     *
     * preferredHotbarSlot is normally the player's currently selected
     * slot. If the item comes from the main inventory, that hotbar slot
     * is temporarily swapped with the inventory item.
     */
    public Optional<HotbarLease> acquireToHotbar(
            Minecraft client,
            Predicate<ItemStack> matcher,
            int preferredHotbarSlot
    ) {
        Objects.requireNonNull(matcher, "matcher");

        validateHotbarSlot(preferredHotbarSlot);

        /*
         * Finish any previously requested restoration first.
         */
        tick(client);

        if (client.player == null) {
            return Optional.empty();
        }

        /*
         * Only one behavior may borrow an inventory item at a time.
         */
        if (activeLease != null) {
            return Optional.empty();
        }

        /*
         * Prefer an item that is already in the hotbar. No inventory
         * interaction is needed in this case.
         */
        int hotbarSlot = findMatchingHotbarSlot(
                client,
                matcher,
                preferredHotbarSlot
        );

        if (hotbarSlot >= 0) {
            Item expectedItem =
                    client.player
                            .getInventory()
                            .getItem(hotbarSlot)
                            .getItem();

            HotbarLease lease = new HotbarLease(
                    this,
                    hotbarSlot,
                    hotbarSlot,
                    expectedItem,
                    false
            );

            activeLease = lease;

            return Optional.of(lease);
        }

        int mainInventorySlot =
                findMatchingMainInventorySlot(
                        client,
                        matcher
                );

        if (mainInventorySlot < 0) {
            return Optional.empty();
        }

        /*
         * A server-synchronized swap is only safe while the player's
         * own inventory menu is the active container.
         */
        if (!canManipulatePlayerInventory(client)) {
            return Optional.empty();
        }

        Item expectedItem =
                client.player
                        .getInventory()
                        .getItem(mainInventorySlot)
                        .getItem();

        swapInventorySlotWithHotbar(
                client,
                mainInventorySlot,
                preferredHotbarSlot
        );

        HotbarLease lease = new HotbarLease(
                this,
                mainInventorySlot,
                preferredHotbarSlot,
                expectedItem,
                true
        );

        activeLease = lease;

        return Optional.of(lease);
    }

    public boolean hasActiveLease() {
        return activeLease != null;
    }

    public void releaseActiveLease(Minecraft client) {
        if (activeLease != null) {
            requestRelease(client, activeLease);
        }
    }

    private int findMatchingHotbarSlot(
            Minecraft client,
            Predicate<ItemStack> matcher,
            int preferredHotbarSlot
    ) {
        ItemStack preferredStack =
                client.player
                        .getInventory()
                        .getItem(preferredHotbarSlot);

        if (matches(preferredStack, matcher)) {
            return preferredHotbarSlot;
        }

        for (
                int slot = FIRST_HOTBAR_SLOT;
                slot <= LAST_HOTBAR_SLOT;
                slot++
        ) {
            if (slot == preferredHotbarSlot) {
                continue;
            }

            ItemStack stack =
                    client.player.getInventory().getItem(slot);

            if (matches(stack, matcher)) {
                return slot;
            }
        }

        return -1;
    }

    private int findMatchingMainInventorySlot(
            Minecraft client,
            Predicate<ItemStack> matcher
    ) {
        for (
                int slot = FIRST_MAIN_INVENTORY_SLOT;
                slot <= LAST_MAIN_INVENTORY_SLOT;
                slot++
        ) {
            ItemStack stack =
                    client.player.getInventory().getItem(slot);

            if (matches(stack, matcher)) {
                return slot;
            }
        }

        return -1;
    }

    private boolean matches(
            ItemStack stack,
            Predicate<ItemStack> matcher
    ) {
        return !stack.isEmpty() && matcher.test(stack);
    }

    private boolean canManipulatePlayerInventory(
            Minecraft client
    ) {
        return client.player != null
                && client.gameMode != null
                && client.player.containerMenu
                == client.player.inventoryMenu;
    }

    private void swapInventorySlotWithHotbar(
            Minecraft client,
            int inventorySlot,
            int hotbarSlot
    ) {
        validateHotbarSlot(hotbarSlot);

        int inventoryMenuSlot =
                toInventoryMenuSlotId(inventorySlot);

        client.gameMode.handleContainerInput(
                client.player.inventoryMenu.containerId,
                inventoryMenuSlot,
                hotbarSlot,
                ContainerInput.SWAP,
                client.player
        );
    }

    /*
     * Converts PlayerInventory indexes into InventoryMenu slot IDs.
     *
     * Player inventory:
     *   0-8  = hotbar
     *   9-35 = main inventory
     *
     * InventoryMenu:
     *   9-35  = main inventory
     *   36-44 = hotbar
     */
    private int toInventoryMenuSlotId(int inventorySlot) {
        if (
                inventorySlot >= FIRST_HOTBAR_SLOT
                        && inventorySlot <= LAST_HOTBAR_SLOT
        ) {
            return 36 + inventorySlot;
        }

        if (
                inventorySlot >= FIRST_MAIN_INVENTORY_SLOT
                        && inventorySlot
                        <= LAST_MAIN_INVENTORY_SLOT
        ) {
            return inventorySlot;
        }

        throw new IllegalArgumentException(
                "Unsupported player inventory slot: "
                        + inventorySlot
        );
    }

    private void validateHotbarSlot(int hotbarSlot) {
        if (
                hotbarSlot < FIRST_HOTBAR_SLOT
                        || hotbarSlot > LAST_HOTBAR_SLOT
        ) {
            throw new IllegalArgumentException(
                    "Hotbar slot must be between 0 and 8: "
                            + hotbarSlot
            );
        }
    }

    private void requestRelease(
            Minecraft client,
            HotbarLease lease
    ) {
        if (lease.released) {
            return;
        }

        if (activeLease != lease) {
            lease.released = true;
            return;
        }

        lease.releaseRequested = true;
        tryFinishRelease(client, lease);
    }

    private void tryFinishRelease(
            Minecraft client,
            HotbarLease lease
    ) {
        if (lease.released || activeLease != lease) {
            return;
        }

        /*
         * If no inventory swap occurred, releasing the lease requires
         * no network action.
         */
        if (!lease.swappedFromMainInventory) {
            clearLease(lease);
            return;
        }

        /*
         * If another container is open, keep the release pending.
         * tick() will retry after the player returns to their own menu.
         */
        if (!canManipulatePlayerInventory(client)) {
            return;
        }

        /*
         * Repeating the same SWAP restores the displaced hotbar item.
         */
        swapInventorySlotWithHotbar(
                client,
                lease.sourceInventorySlot,
                lease.hotbarSlot
        );

        clearLease(lease);
    }

    private void clearLease(HotbarLease lease) {
        lease.released = true;
        lease.releaseRequested = false;

        if (activeLease == lease) {
            activeLease = null;
        }
    }

    public record InventorySlot(
            int inventoryIndex,
            boolean hotbar
    ) {
    }

    public static final class HotbarLease {

        private final InventoryService owner;

        private final int sourceInventorySlot;
        private final int hotbarSlot;
        private final Item expectedItem;
        private final boolean swappedFromMainInventory;

        private boolean releaseRequested;
        private boolean released;

        private HotbarLease(
                InventoryService owner,
                int sourceInventorySlot,
                int hotbarSlot,
                Item expectedItem,
                boolean swappedFromMainInventory
        ) {
            this.owner = owner;
            this.sourceInventorySlot = sourceInventorySlot;
            this.hotbarSlot = hotbarSlot;
            this.expectedItem = expectedItem;
            this.swappedFromMainInventory =
                    swappedFromMainInventory;
        }

        public int hotbarSlot() {
            return hotbarSlot;
        }

        /*
         * The inventory interaction may take a tick to become visible.
         * Behaviors should wait until this returns true.
         */
        public boolean isReady(Minecraft client) {
            if (
                    released
                            || releaseRequested
                            || client.player == null
            ) {
                return false;
            }

            ItemStack stack =
                    client.player
                            .getInventory()
                            .getItem(hotbarSlot);

            return !stack.isEmpty()
                    && stack.getItem() == expectedItem;
        }

        public boolean isReleasePending() {
            return releaseRequested && !released;
        }

        public boolean isReleased() {
            return released;
        }

        public void release(Minecraft client) {
            owner.requestRelease(client, this);
        }
    }
}