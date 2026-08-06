package dev.renan.afking.client.automation;

public interface AutonomousBehavior {

    String id();

    default String displayName() {
        return id();
    }

    int priority();

    /*
     * Called when the player attempts to enable this behavior.
     *
     * Return false to reject activation. For copper processing,
     * this is where the station is captured and validated.
     */
    default boolean arm(AutomationContext context) {
        return true;
    }

    boolean shouldRun(AutomationContext context);

    /*
     * Called whenever the behavior gains execution control.
     */
    default void start(AutomationContext context) {
    }

    BehaviorResult tick(AutomationContext context);

    default void stop(
            AutomationContext context,
            BehaviorStopReason reason
    ) {
    }

    default boolean disarmWhenAutomationStops() {
        return false;
    }
}