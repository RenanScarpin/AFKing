package dev.renan.afking.client.automation;

import dev.renan.afking.client.input.ControlledInput;
import net.minecraft.client.Minecraft;

public final class AutomationContext {

    private final Minecraft client;
    private final ControlledInput input;

    public AutomationContext(
            Minecraft client,
            ControlledInput input
    ) {
        this.client = client;
        this.input = input;
    }

    public Minecraft client() {
        return client;
    }

    public ControlledInput input() {
        return input;
    }
}