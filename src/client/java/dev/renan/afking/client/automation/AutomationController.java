package dev.renan.afking.client.automation;

import dev.renan.afking.client.input.ControlledInput;
import dev.renan.afking.client.service.InventoryService;
import dev.renan.afking.client.service.BlockInteractionService;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class AutomationController {

    private final List<BehaviorEntry> behaviors =
            new ArrayList<>();

    private final ControlledInput input =
            new ControlledInput();

    private final InventoryService inventory =
            new InventoryService();

    private final BlockInteractionService blockInteractions =
            new BlockInteractionService();

    private AutonomousBehavior activeBehavior;
    private boolean masterEnabled;

    public void register(
            AutonomousBehavior behavior,
            boolean enabledByDefault
    ) {
        boolean duplicateId = behaviors.stream()
                .anyMatch(entry ->
                        entry.behavior
                                .id()
                                .equals(behavior.id())
                );

        if (duplicateId) {
            throw new IllegalArgumentException(
                    "Duplicate AFKing behavior ID: "
                            + behavior.id()
            );
        }

        behaviors.add(
                new BehaviorEntry(
                        behavior,
                        enabledByDefault
                )
        );

        behaviors.sort(
                Comparator.comparingInt(
                        (BehaviorEntry entry) ->
                                entry.behavior.priority()
                ).reversed()
        );
    }

    public void tick(Minecraft client) {
        /*
         * Pending inventory restoration can continue even if the
         * behavior that created the lease is no longer active.
         */
        inventory.tick(client);

        AutomationContext context =
                createContext(client);

        if (client.player == null || client.level == null) {
            if (
                    masterEnabled
                            || activeBehavior != null
                            || hasArmedSessionTask()
            ) {
                disableSilently(
                        context,
                        BehaviorStopReason.WORLD_LEFT
                );
            }

            return;
        }

        if (!masterEnabled) {
            return;
        }

        AutonomousBehavior selectedBehavior =
                selectBehavior(context);

        switchActiveBehavior(
                context,
                selectedBehavior
        );

        if (activeBehavior == null) {
            return;
        }

        BehaviorResult result =
                activeBehavior.tick(context);

        switch (result) {
            case KEEP_RUNNING -> {
                // Behavior keeps control.
            }

            case RELEASE_CONTROL -> {
                stopActiveBehavior(
                        context,
                        BehaviorStopReason.COMPLETED
                );
            }

            case COMPLETE_BEHAVIOR -> {
                disableActiveBehavior(
                        context,
                        BehaviorStopReason.COMPLETED
                );
            }

            case DISABLE_BEHAVIOR -> {
                disableActiveBehavior(
                        context,
                        BehaviorStopReason.FAILED
                );
            }

            case DISABLE_AUTOMATION -> {
                disableSilently(
                        context,
                        BehaviorStopReason.CANCELLED
                );
            }
        }
    }

    public void toggleMaster(Minecraft client) {
        AutomationContext context =
                createContext(client);

        if (masterEnabled) {
            disableSilently(
                    context,
                    BehaviorStopReason.CANCELLED
            );

            sendMessage(client, "AFKing: OFF");
            return;
        }

        masterEnabled = true;
        sendMessage(client, "AFKing: ON");
    }

    public void toggleBehavior(
            Minecraft client,
            String behaviorId
    ) {
        BehaviorEntry entry =
                findBehavior(behaviorId);

        if (entry == null) {
            throw new IllegalArgumentException(
                    "Unknown AFKing behavior: "
                            + behaviorId
            );
        }

        AutomationContext context =
                createContext(client);

        /*
         * Prevent a task from becoming secretly armed while the
         * master switch is disabled.
         */
        if (!entry.enabled && !masterEnabled) {
            sendMessage(
                    client,
                    "AFKing: enable the master toggle "
                            + "before starting "
                            + entry.behavior.displayName()
                            + "."
            );

            return;
        }

        /*
         * Disable an already enabled behavior.
         */
        if (entry.enabled) {
            entry.enabled = false;

            if (activeBehavior == entry.behavior) {
                stopActiveBehavior(
                        context,
                        BehaviorStopReason.CANCELLED
                );
            } else {
                /*
                 * It may currently be paused by a higher-priority
                 * behavior but still retain session state.
                 */
                entry.behavior.stop(
                        context,
                        BehaviorStopReason.CANCELLED
                );
            }

            sendMessage(
                    client,
                    "AFKing — "
                            + entry.behavior.displayName()
                            + ": OFF"
            );

            return;
        }

        /*
         * Let the behavior capture and validate everything it needs
         * before its enabled flag is changed.
         */
        boolean armed =
                entry.behavior.arm(context);

        if (!armed) {
            return;
        }

        entry.enabled = true;

        sendMessage(
                client,
                "AFKing — "
                        + entry.behavior.displayName()
                        + ": ON"
        );
    }

    public boolean isMasterEnabled() {
        return masterEnabled;
    }

    public boolean isBehaviorEnabled(
            String behaviorId
    ) {
        BehaviorEntry entry =
                findBehavior(behaviorId);

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

        stopActiveBehavior(
                context,
                BehaviorStopReason.PREEMPTED
        );

        activeBehavior = selectedBehavior;

        if (activeBehavior != null) {
            activeBehavior.start(context);
        }
    }

    private void stopActiveBehavior(
            AutomationContext context,
            BehaviorStopReason reason
    ) {
        if (activeBehavior == null) {
            return;
        }

        AutonomousBehavior behaviorBeingStopped =
                activeBehavior;

        activeBehavior = null;

        behaviorBeingStopped.stop(
                context,
                reason
        );
    }

    /*
     * Disables only the behavior that currently has control.
     */
    private void disableActiveBehavior(
            AutomationContext context,
            BehaviorStopReason reason
    ) {
        if (activeBehavior == null) {
            return;
        }

        BehaviorEntry entry =
                findBehavior(activeBehavior.id());

        if (entry != null) {
            entry.enabled = false;
        }

        stopActiveBehavior(context, reason);
    }

    private void disableSilently(
            AutomationContext context,
            BehaviorStopReason reason
    ) {
        masterEnabled = false;

        AutonomousBehavior behaviorActiveAtShutdown =
                activeBehavior;

        stopActiveBehavior(context, reason);

        /*
         * A stateful task may currently be paused and therefore not
         * be the active behavior. Notify every registered behavior.
         */
        for (BehaviorEntry entry : behaviors) {
            if (
                    entry.behavior
                            != behaviorActiveAtShutdown
            ) {
                entry.behavior.stop(
                        context,
                        reason
                );
            }

            if (
                    entry.behavior
                            .disarmWhenAutomationStops()
            ) {
                entry.enabled = false;
            }
        }

        inventory.releaseActiveLease(
                context.client()
        );

        input.releaseAll();
    }

    private boolean hasArmedSessionTask() {
        for (BehaviorEntry entry : behaviors) {
            if (
                    entry.enabled
                            && entry.behavior
                            .disarmWhenAutomationStops()
            ) {
                return true;
            }
        }

        return false;
    }

    private BehaviorEntry findBehavior(
            String behaviorId
    ) {
        for (BehaviorEntry entry : behaviors) {
            if (
                    entry.behavior
                            .id()
                            .equals(behaviorId)
            ) {
                return entry;
            }
        }

        return null;
    }

    private AutomationContext createContext(
            Minecraft client
    ) {
        return new AutomationContext(
                client,
                input,
                inventory,
                blockInteractions
        );
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