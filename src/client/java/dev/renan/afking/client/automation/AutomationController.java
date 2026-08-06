package dev.renan.afking.client.automation;

import dev.renan.afking.client.input.ControlledInput;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class AutomationController {

    private final List<BehaviorEntry> behaviors = new ArrayList<>();
    private final ControlledInput input = new ControlledInput();

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

    public void tick(Minecraft client) {
        AutomationContext context =
                new AutomationContext(client, input);

        /*
         * Leaving a world always disarms AFKing.
         */
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

        BehaviorResult result = activeBehavior.tick(context);

        switch (result) {
            case KEEP_RUNNING -> {
                // The behavior remains active.
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
                new AutomationContext(client, input);

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
                    new AutomationContext(client, input);

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