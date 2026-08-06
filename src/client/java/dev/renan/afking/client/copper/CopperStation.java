package dev.renan.afking.client.copper;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public record CopperStation(
        ResourceKey<Level> dimension,
        BlockPos supportPosition,
        Direction clickedFace,
        BlockPos placementPosition,
        Vec3 hitLocation,
        boolean insideBlock
) {

    /*
     * This is a session safety radius, not a replacement for the
     * server's actual interaction-range validation.
     *
     * The player should normally remain standing at the station.
     */
    private static final double MAX_STATION_DISTANCE =
            6.0D;

    private static final double MAX_STATION_DISTANCE_SQUARED =
            MAX_STATION_DISTANCE * MAX_STATION_DISTANCE;

    public static CaptureResult capture(
            Minecraft client
    ) {
        if (client.player == null || client.level == null) {
            return CaptureResult.failure(
                    "enter a world before selecting a station."
            );
        }

        HitResult currentHit = client.hitResult;

        if (
                !(currentHit instanceof BlockHitResult blockHit)
                        || blockHit.getType()
                        != HitResult.Type.BLOCK
        ) {
            return CaptureResult.failure(
                    "look directly at a block face before pressing F9."
            );
        }

        if (blockHit.isWorldBorderHit()) {
            return CaptureResult.failure(
                    "the world border cannot be used as a station."
            );
        }

        BlockPos supportPosition =
                blockHit
                        .getBlockPos()
                        .immutable();

        Direction clickedFace =
                blockHit.getDirection();

        BlockPos placementPosition =
                supportPosition
                        .relative(clickedFace)
                        .immutable();

        BlockState supportState =
                client.level.getBlockState(
                        supportPosition
                );

        if (supportState.isAir()) {
            return CaptureResult.failure(
                    "the selected support block no longer exists."
            );
        }

        if (
                !client.level.isInWorldBounds(
                        placementPosition
                )
        ) {
            return CaptureResult.failure(
                    "the placement position is outside the world bounds."
            );
        }

        BlockState placementState =
                client.level.getBlockState(
                        placementPosition
                );

        /*
         * For the initial implementation, require actual air.
         *
         * This avoids unexpectedly replacing plants, snow, liquids,
         * or other replaceable blocks.
         */
        if (!placementState.isAir()) {
            return CaptureResult.failure(
                    "the block adjacent to the selected face is not empty."
            );
        }

        CopperStation station =
                new CopperStation(
                        client.level.dimension(),
                        supportPosition,
                        clickedFace,
                        placementPosition,
                        blockHit.getLocation(),
                        blockHit.isInside()
                );

        StationValidation distanceValidation =
                station.validateDistance(client);

        if (!distanceValidation.valid()) {
            return CaptureResult.failure(
                    distanceValidation.errorMessage()
            );
        }

        return CaptureResult.success(station);
    }

    /*
     * Checks properties that must remain valid throughout the entire
     * processing session.
     *
     * It deliberately does not require the placement position to be
     * air, because future milestones will place copper there.
     */
    public StationValidation validatePersistent(
            Minecraft client
    ) {
        if (client.player == null || client.level == null) {
            return StationValidation.failure(
                    "the player is no longer in a world."
            );
        }

        if (
                !client.level
                        .dimension()
                        .equals(dimension)
        ) {
            return StationValidation.failure(
                    "the player changed dimension."
            );
        }

        if (
                !client.level.isInWorldBounds(
                        placementPosition
                )
        ) {
            return StationValidation.failure(
                    "the station is outside the world bounds."
            );
        }

        BlockState supportState =
                client.level.getBlockState(
                        supportPosition
                );

        if (supportState.isAir()) {
            return StationValidation.failure(
                    "the station's support block was removed."
            );
        }

        return validateDistance(client);
    }

    /*
     * Used before beginning a new placement cycle.
     */
    public StationValidation validateReadyForPlacement(
            Minecraft client
    ) {
        StationValidation persistentValidation =
                validatePersistent(client);

        if (!persistentValidation.valid()) {
            return persistentValidation;
        }

        BlockState placementState =
                client.level.getBlockState(
                        placementPosition
                );

        if (!placementState.isAir()) {
            return StationValidation.failure(
                    "the station's placement position is occupied."
            );
        }

        return StationValidation.success();
    }

    private StationValidation validateDistance(
            Minecraft client
    ) {
        if (client.player == null) {
            return StationValidation.failure(
                    "the player is unavailable."
            );
        }

        Vec3 playerEyes =
                client.player.getEyePosition();

        Vec3 placementCenter =
                Vec3.atCenterOf(placementPosition);

        double distanceSquared =
                playerEyes.distanceToSqr(
                        placementCenter
                );

        if (
                distanceSquared
                        > MAX_STATION_DISTANCE_SQUARED
        ) {
            return StationValidation.failure(
                    "the player moved too far from the station."
            );
        }

        return StationValidation.success();
    }

    /*
     * This will be used during the placement milestone.
     */
    public BlockHitResult createInteractionHitResult() {
        return new BlockHitResult(
                hitLocation,
                clickedFace,
                supportPosition,
                insideBlock
        );
    }

    /*
     * Creates a hit result for interacting with the copper block itself,
     * rather than the support block used during placement.
     *
     * The original hit location lies on the boundary shared by the
     * support block and placed copper block. From the copper block's
     * perspective, the clicked face points in the opposite direction.
     */
    public BlockHitResult createTargetInteractionHitResult() {
        return new BlockHitResult(
                hitLocation,
                clickedFace.getOpposite(),
                placementPosition,
                false
        );
    }

    /*
     * The copper block occupies the position adjacent to the originally
     * clicked support face. From the copper block's perspective, its
     * interaction face points in the opposite direction.
     */
    public Direction targetInteractionFace() {
        return clickedFace.getOpposite();
    }

    public String describe() {
        return "support "
                + coordinates(supportPosition)
                + ", face "
                + clickedFace
                + ", placement "
                + coordinates(placementPosition);
    }

    private static String coordinates(
            BlockPos position
    ) {
        return position.getX()
                + ", "
                + position.getY()
                + ", "
                + position.getZ();
    }

    public record CaptureResult(
            CopperStation station,
            String errorMessage
    ) {

        public static CaptureResult success(
                CopperStation station
        ) {
            return new CaptureResult(
                    station,
                    ""
            );
        }

        public static CaptureResult failure(
                String errorMessage
        ) {
            return new CaptureResult(
                    null,
                    errorMessage
            );
        }

        public boolean succeeded() {
            return station != null;
        }
    }
}