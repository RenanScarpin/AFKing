package dev.renan.afking.client.behavior;

import dev.renan.afking.client.automation.AutomationContext;
import dev.renan.afking.client.automation.AutonomousBehavior;
import dev.renan.afking.client.automation.BehaviorResult;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class LowHealthDisconnectBehavior
        implements AutonomousBehavior {

    public static final String ID =
            "low_health_disconnect";

    private static final int PRIORITY = 1000;

    private final float healthThreshold;

    public LowHealthDisconnectBehavior(
            float healthThreshold
    ) {
        this.healthThreshold = healthThreshold;
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

        return client.player != null
                && client.player.getHealth()
                <= healthThreshold;
    }

    @Override
    public BehaviorResult tick(
            AutomationContext context
    ) {
        Minecraft client = context.client();

        if (client.player == null) {
            return BehaviorResult.DISABLE_AUTOMATION;
        }

        float currentHealth = client.player.getHealth();

        Component reason = Component.literal(
                "AFKing disconnected because health reached "
                        + formatHealth(currentHealth)
                        + " hearts."
        );

        if (client.getConnection() != null) {
            client.getConnection()
                    .getConnection()
                    .disconnect(reason);
        }

        return BehaviorResult.DISABLE_AUTOMATION;
    }

    private String formatHealth(float healthPoints) {
        float hearts = healthPoints / 2.0F;

        if (hearts == Math.floor(hearts)) {
            return Integer.toString((int) hearts);
        }

        return Float.toString(hearts);
    }
}