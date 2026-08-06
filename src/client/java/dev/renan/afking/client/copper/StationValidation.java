package dev.renan.afking.client.copper;

public record StationValidation(
        boolean valid,
        String errorMessage
) {

    public static StationValidation success() {
        return new StationValidation(true, "");
    }

    public static StationValidation failure(
            String errorMessage
    ) {
        return new StationValidation(
                false,
                errorMessage
        );
    }
}