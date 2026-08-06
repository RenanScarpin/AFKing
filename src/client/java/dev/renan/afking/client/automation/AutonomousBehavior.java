package dev.renan.afking.client.automation;

public interface AutonomousBehavior {

    /*
     * Stable identifier used for configuration and future key mappings.
     */
    String id();

    /*
     * Higher values take control before lower values.
     */
    int priority();

    /*
     * Whether this behavior currently needs control.
     *
     * This method should inspect state but should not perform actions.
     */
    boolean shouldRun(AutomationContext context);

    /*
     * Performs one client tick of autonomous behavior.
     */
    BehaviorResult tick(AutomationContext context);

    /*
     * Called whenever this behavior loses control.
     */
    default void stop(AutomationContext context) {
    }
}