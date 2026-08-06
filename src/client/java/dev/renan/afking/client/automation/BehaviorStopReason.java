package dev.renan.afking.client.automation;

public enum BehaviorStopReason {

    /*
     * A higher-priority behavior temporarily took control.
     */
    PREEMPTED,

    /*
     * The behavior finished normally.
     */
    COMPLETED,

    /*
     * The player explicitly stopped the behavior or AFKing.
     */
    CANCELLED,

    /*
     * The behavior encountered a condition that prevents it
     * from safely continuing.
     */
    FAILED,

    /*
     * The player left the world or disconnected.
     */
    WORLD_LEFT
}