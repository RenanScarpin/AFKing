package dev.renan.afking.client.copper;

public enum CopperOxidationStage {

    UNAFFECTED(0),
    EXPOSED(1),
    WEATHERED(2),
    OXIDIZED(3);

    private final int stepsToUnaffected;

    CopperOxidationStage(int stepsToUnaffected) {
        this.stepsToUnaffected = stepsToUnaffected;
    }

    public int stepsToUnaffected() {
        return stepsToUnaffected;
    }

    public CopperOxidationStage previousStage() {
        return switch (this) {
            case OXIDIZED -> WEATHERED;
            case WEATHERED -> EXPOSED;
            case EXPOSED -> UNAFFECTED;
            case UNAFFECTED -> null;
        };
    }
}