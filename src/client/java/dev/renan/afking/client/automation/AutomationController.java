package dev.renan.afking.client.automation;

import dev.renan.afking.client.input.ControlledInput;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import dev.renan.afking.client.service.InventoryService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class AutomationController {

    private final List<BehaviorEntry> behaviors = new ArrayList<>();
    private final ControlledInput input = new ControlledInput();
    private final InventoryService inventory = new InventoryService();

    private AutonomousBehavior activeBehavior;
    private boolean masterEnabled;

    public void register(
            AutonomousBehavior behavior,
            boolean enabledByDefault
    ) {
        boolean duplicateId = behaviors.stream()
                .anyMatch(entry ->
                        entry.behavior.id().equals(behavior.id())
                );

        if (duplicateId) {
            throw new IllegalArgumentException(
                    "Duplicate AFKing behavior ID: " + behavior.id()
            );
        }

        behaviors.add(
                new BehaviorEntry(behavior, enabledByDefault)
        );

        behaviors.sort(
                Comparator.comparingInt(
                        (BehaviorEntry entry) ->
                                entry.behavior.priority()
                ).reversed()
        );
    }

    private AutomationContext createContext(
            Minecraft client
    ) {
        return new AutomationContext(
                client,
                input,
                inventory
        );
    }

    public void tick(Minecraft client) {
        inventory.tick(client);

        AutomationContext context =
                createContext(client);

        if (client.player == null || client.level == null) {
            if (masterEnabled || activeBehavior != null) {
                disableSilently(context);
            }

            return;
        }

        if (!masterEnabled) {
            return;
        }

        AutonomousBehavior selectedBehavior =
                selectBehavior(context);

        switchActiveBehavior(context, selectedBehavior);

        if (activeBehavior == null) {
            return;
        }

        BehaviorResult result =
                activeBehavior.tick(context);

        switch (result) {
            case KEEP_RUNNING -> {
            }

            case RELEASE_CONTROL -> {
                stopActiveBehavior(context);
            }

            case DISABLE_AUTOMATION -> {
                disableSilently(context);
            }
        }
    }

    public void toggleMaster(Minecraft client) {
        AutomationContext context =
                createContext(client);

        if (masterEnabled) {
            disableSilently(context);
            sendMessage(client, "AFKing: OFF");
            return;
        }

        masterEnabled = true;
        sendMessage(client, "AFKing: ON");
    }

    /*
     * This method is ready for future per-behavior key mappings.
     */
    public void toggleBehavior(
            Minecraft client,
            String behaviorId
    ) {
        BehaviorEntry entry = findBehavior(behaviorId);

        if (entry == null) {
            throw new IllegalArgumentException(
                    "Unknown AFKing behavior: " + behaviorId
            );
        }

        entry.enabled = !entry.enabled;

        if (
                !entry.enabled
                        && activeBehavior == entry.behavior
        ) {
            AutomationContext context =
                    createContext(client);

            stopActiveBehavior(context);
        }

        sendMessage(
                client,
                "AFKing — "
                        + behaviorId
                        + ": "
                        + (entry.enabled ? "ON" : "OFF")
        );
    }

    public boolean isMasterEnabled() {
        return masterEnabled;
    }

    public boolean isBehaviorEnabled(String behaviorId) {
        BehaviorEntry entry = findBehavior(behaviorId);

        return entry != null && entry.enabled;
    }

    private AutonomousBehavior selectBehavior(
            AutomationContext context
    ) {
        for (BehaviorEntry entry : behaviors) {
            if (
                    entry.enabled
                            && entry.behavior.shouldRun(context)
            ) {
                return entry.behavior;
            }
        }

        return null;
    }

    private void switchActiveBehavior(
            AutomationContext context,
            AutonomousBehavior selectedBehavior
    ) {
        if (activeBehavior == selectedBehavior) {
            return;
        }

        stopActiveBehavior(context);
        activeBehavior = selectedBehavior;
    }

    private void stopActiveBehavior(
            AutomationContext context
    ) {
        if (activeBehavior == null) {
            return;
        }

        activeBehavior.stop(context);
        activeBehavior = null;
    }

    private void disableSilently(
            AutomationContext context
    ) {
        masterEnabled = false;

        stopActiveBehavior(context);
        inventory.releaseActiveLease(context.client());
        input.releaseAll();
    }

    private BehaviorEntry findBehavior(String behaviorId) {
        for (BehaviorEntry entry : behaviors) {
            if (entry.behavior.id().equals(behaviorId)) {
                return entry;
            }
        }

        return null;
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

    private static final class BehaviorEntry {

        private final AutonomousBehavior behavior;
        private boolean enabled;

        private BehaviorEntry(
                AutonomousBehavior behavior,
                boolean enabled
        ) {
            this.behavior = behavior;
            this.enabled = enabled;
        }
    }
}