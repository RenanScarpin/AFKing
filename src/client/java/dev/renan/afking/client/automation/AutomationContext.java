package dev.renan.afking.client.automation;

import dev.renan.afking.client.input.ControlledInput;
import dev.renan.afking.client.service.BlockInteractionService;
import dev.renan.afking.client.service.InventoryService;
import net.minecraft.client.Minecraft;

public final class AutomationContext {

    private final Minecraft client;
    private final ControlledInput input;
    private final InventoryService inventory;
    private final BlockInteractionService blockInteractions;

    public AutomationContext(
            Minecraft client,
            ControlledInput input,
            InventoryService inventory,
            BlockInteractionService blockInteractions
    ) {
        this.client = client;
        this.input = input;
        this.inventory = inventory;
        this.blockInteractions = blockInteractions;
    }

    public Minecraft client() {
        return client;
    }

    public ControlledInput input() {
        return input;
    }

    public InventoryService inventory() {
        return inventory;
    }

    public BlockInteractionService blockInteractions() {
        return blockInteractions;
    }
}