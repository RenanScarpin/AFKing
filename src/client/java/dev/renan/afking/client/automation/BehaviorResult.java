package dev.renan.afking.client.automation;

public enum BehaviorResult {

    /*
     * The behavior still needs control and should remain active.
     */
    KEEP_RUNNING,

    /*
     * The behavior completed its current action.
     */
    RELEASE_CONTROL,

    /*
     * AFKing should shut down completely after this tick.
     */
    DISABLE_AUTOMATION
}