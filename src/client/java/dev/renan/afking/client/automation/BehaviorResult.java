package dev.renan.afking.client.automation;

public enum BehaviorResult {

    /*
     * Keep this behavior active.
     */
    KEEP_RUNNING,

    /*
     * Release control, but leave the behavior enabled.
     */
    RELEASE_CONTROL,

    /*
     * The behavior completed successfully and should be disabled.
     */
    COMPLETE_BEHAVIOR,

    /*
     * The behavior failed and should be disabled.
     */
    DISABLE_BEHAVIOR,

    /*
     * Disable the entire AFKing system.
     */
    DISABLE_AUTOMATION
}