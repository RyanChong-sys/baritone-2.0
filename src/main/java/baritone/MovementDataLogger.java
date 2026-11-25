package baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Blocks;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class MovementDataLogger implements AbstractGameEventListener {

    private static final int SAMPLE_INTERVAL_TICKS = 20;
    private static final double MAX_SAMPLE_DISTANCE = 64.0D;

    private static final String LEGACY_HEADER =
            "tick,x,y,z,health,on_ground,delta_x,delta_y,delta_z,damage_delta";

    private static final String PREVIOUS_HEADER =
            "session_id,tick,x,y,z,health,on_ground,"
            + "delta_x,delta_y,delta_z,damage_delta,"
            + "velocity_x,velocity_y,velocity_z,fall_distance,"
            + "sprinting,in_water,in_lava,on_fire,"
            + "nearby_hostile_count,nearest_hostile_distance,"
            + "hazard_below,hazard_feet,hazard_head";

    private static final String HEADER =
            PREVIOUS_HEADER + ","
            + "rotation_yaw,rotation_pitch,"
            + "action_forward_ticks,action_backward_ticks,"
            + "action_left_ticks,action_right_ticks,"
            + "action_jump_ticks,action_sneak_ticks,action_sprint_ticks,"
            + "action_attack_ticks,action_use_ticks,"
            + "front_1_feet,front_1_body,front_1_clearance,"
            + "front_2_feet,front_2_body,front_2_clearance,"
            + "front_3_feet,front_3_body,front_3_clearance\n";

    private final Minecraft minecraft;
    private final Path enabledFile;
    private final Path outputFile;
    private final String sessionId = UUID.randomUUID().toString();

    private long lastTick = -1;
    private double lastX, lastY, lastZ;
    private float lastHealth = -1.0F;

    private boolean loggerActive = false;
    private boolean schemaReady = false;
    private boolean loggedActivationState = false;

    // Counts of ticks on which each action was active in the current sample window.
    private int actionForwardTicks;
    private int actionBackwardTicks;
    private int actionLeftTicks;
    private int actionRightTicks;
    private int actionJumpTicks;
    private int actionSneakTicks;
    private int actionSprintTicks;
    private int actionAttackTicks;
    private int actionUseTicks;

    public MovementDataLogger(Minecraft minecraft, Path directory) {
        this.minecraft = minecraft;
        this.enabledFile = directory.resolve("movement-data-logger.enabled");
        this.outputFile = directory.resolve("movement-data.csv");

        System.out.println(
                "[Baritone 2.0] Logger initialized. CSV: " + outputFile
        );
    }

    @Override
    public void onPostTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN) {
            return;
        }

        boolean sampleTick = event.getCount() % SAMPLE_INTERVAL_TICKS == 0;

        // Check the enable file only once per sample interval, not every game tick.
        if (sampleTick) {
            loggerActive = isEnabled();

            if (!loggerActive) {
                resetSample();
                return;
            }

            if (!loggedActivationState) {
                loggedActivationState = true;
                System.out.println("[Baritone 2.0] Logger enabled.");
            }

            if (!schemaReady) {
                schemaReady = prepareCsv();
                if (!schemaReady) {
                    return;
                }
            }
        }

        if (!loggerActive || !schemaReady) {
            return;
        }

        LocalPlayer player = minecraft.player;

        if (player == null || minecraft.level == null) {
            resetSample();
            return;
        }

        // Accumulate controls on every tick so the row describes the full 20-tick interval.
        accumulateActions(player);

        if (!sampleTick) {
            return;
        }

        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float health = player.getHealth();

        // Ignore repeated samples while dead.
        if (health <= 0.0F && lastHealth <= 0.0F) {
            resetSample();
            return;
        }

        // Establish a fresh baseline after death or respawn.
        if (lastTick < 0 || lastHealth <= 0.0F) {
            savePreviousSample(event.getCount(), x, y, z, health);
            resetActionCounts();
            return;
        }

        double dx = x - lastX;
        double dy = y - lastY;
        double dz = z - lastZ;
        double damage = Math.max(0.0F, lastHealth - health);
        double distanceSquared = dx * dx + dy * dy + dz * dz;

        // Skip teleport-like movement between samples, including respawn snaps.
        if (distanceSquared > MAX_SAMPLE_DISTANCE * MAX_SAMPLE_DISTANCE) {
            System.out.println(
                    "[Baritone 2.0] Skipping teleport-like sample: "
                            + Math.sqrt(distanceSquared)
                            + " blocks."
            );

            savePreviousSample(event.getCount(), x, y, z, health);
            resetActionCounts();
            return;
        }

        var velocity = player.getDeltaMovement();

        List<Mob> nearbyMobs = minecraft.level.getEntitiesOfClass(
                Mob.class,
                player.getBoundingBox().inflate(8.0D)
        );

        int hostileCount = 0;
        double nearestHostile = -1.0D;

        for (Mob mob : nearbyMobs) {
            if (mob instanceof Enemy) {
                hostileCount++;
                double distance = mob.distanceTo(player);

                if (nearestHostile < 0 || distance < nearestHostile) {
                    nearestHostile = distance;
                }
            }
        }

        String hazardBelow = getHazard(
                BlockPos.containing(x, y - 0.1D, z)
        );
        String hazardFeet = getHazard(
                BlockPos.containing(x, y + 0.1D, z)
        );
        String hazardHead = getHazard(
                BlockPos.containing(x, y + 1.5D, z)
        );

        double yaw = player.getYRot();
        double pitch = player.getXRot();

        // Scan three forward distances at feet, body, and overhead clearance heights.
        String front1Feet = getFrontBlock(player, 0.75D, 0.1D);
        String front1Body = getFrontBlock(player, 0.75D, 1.0D);
        String front1Clearance = getFrontBlock(player, 0.75D, 1.9D);

        String front2Feet = getFrontBlock(player, 1.5D, 0.1D);
        String front2Body = getFrontBlock(player, 1.5D, 1.0D);
        String front2Clearance = getFrontBlock(player, 1.5D, 1.9D);

        String front3Feet = getFrontBlock(player, 2.5D, 0.1D);
        String front3Body = getFrontBlock(player, 2.5D, 1.0D);
        String front3Clearance = getFrontBlock(player, 2.5D, 1.9D);

        String row = String.format(
                Locale.ROOT,
                "%s,%d,%.3f,%.3f,%.3f,%.2f,%b,"
                        + "%.3f,%.3f,%.3f,%.2f,"
                        + "%.3f,%.3f,%.3f,%.3f,"
                        + "%b,%b,%b,%b,%d,%.3f,%s,%s,%s,"
                        + "%.2f,%.2f,"
                        + "%d,%d,%d,%d,%d,%d,%d,%d,%d,"
                        + "%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                sessionId,
                event.getCount(),
                x, y, z,
                health,
                player.onGround(),
                dx, dy, dz,
                damage,
                velocity.x, velocity.y, velocity.z,
                player.fallDistance,
                player.isSprinting(),
                player.isInWater(),
                player.isInLava(),
                player.isOnFire(),
                hostileCount,
                nearestHostile,
                hazardBelow,
                hazardFeet,
                hazardHead,
                yaw,
                pitch,
                actionForwardTicks,
                actionBackwardTicks,
                actionLeftTicks,
                actionRightTicks,
                actionJumpTicks,
                actionSneakTicks,
                actionSprintTicks,
                actionAttackTicks,
                actionUseTicks,
                front1Feet,
                front1Body,
                front1Clearance,
                front2Feet,
                front2Body,
                front2Clearance,
                front3Feet,
                front3Body,
                front3Clearance
        );

        try {
            Files.writeString(
                    outputFile,
                    row,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            System.err.println(
                    "[Baritone 2.0] CSV write failed: "
                            + exception.getMessage()
            );
        }

        savePreviousSample(event.getCount(), x, y, z, health);
        resetActionCounts();
    }

    private void accumulateActions(LocalPlayer player) {
        var keys = player.input.keyPresses;

        if (keys.forward()) actionForwardTicks++;
        if (keys.backward()) actionBackwardTicks++;
        if (keys.left()) actionLeftTicks++;
        if (keys.right()) actionRightTicks++;
        if (keys.jump()) actionJumpTicks++;
        if (keys.shift()) actionSneakTicks++;
        if (keys.sprint()) actionSprintTicks++;

        IBaritone baritone = BaritoneAPI.getProvider()
                .getBaritoneForMinecraft(minecraft);
        IInputOverrideHandler inputHandler = baritone == null
                ? null
                : baritone.getInputOverrideHandler();

        boolean attackDown = minecraft.options.keyAttack.isDown()
                || (inputHandler != null
                && inputHandler.isInputForcedDown(Input.CLICK_LEFT));
        boolean useDown = minecraft.options.keyUse.isDown()
                || (inputHandler != null
                && inputHandler.isInputForcedDown(Input.CLICK_RIGHT));

        if (attackDown) actionAttackTicks++;
        if (useDown) actionUseTicks++;
    }

    private void resetActionCounts() {
        actionForwardTicks = 0;
        actionBackwardTicks = 0;
        actionLeftTicks = 0;
        actionRightTicks = 0;
        actionJumpTicks = 0;
        actionSneakTicks = 0;
        actionSprintTicks = 0;
        actionAttackTicks = 0;
        actionUseTicks = 0;
    }

    private String getFrontBlock(
            LocalPlayer player,
            double distance,
            double height
    ) {
        double yawRadians = Math.toRadians(player.getYRot());
        double forwardX = -Math.sin(yawRadians);
        double forwardZ = Math.cos(yawRadians);

        BlockPos position = BlockPos.containing(
                player.getX() + forwardX * distance,
                player.getY() + height,
                player.getZ() + forwardZ * distance
        );

        var state = minecraft.level.getBlockState(position);
        if (state.isAir()) {
            return "air";
        }

        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private String getHazard(BlockPos position) {
        var state = minecraft.level.getBlockState(position);

        if (state.is(Blocks.LAVA)) return "lava";
        if (state.is(Blocks.FIRE)) return "fire";
        if (state.is(Blocks.SOUL_FIRE)) return "soul_fire";
        if (state.is(Blocks.MAGMA_BLOCK)) return "magma";
        if (state.is(Blocks.CACTUS)) return "cactus";
        if (state.is(Blocks.SWEET_BERRY_BUSH)) return "berry_bush";
        if (state.is(Blocks.CAMPFIRE)) return "campfire";
        if (state.is(Blocks.SOUL_CAMPFIRE)) return "soul_campfire";
        if (state.is(Blocks.POWDER_SNOW)) return "powder_snow";

        return "none";
    }

    private boolean prepareCsv() {
        try {
            if (!Files.exists(outputFile) || Files.size(outputFile) == 0) {
                Files.writeString(
                        outputFile,
                        HEADER,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                );
                return true;
            }

            String firstLine;
            try (BufferedReader reader = Files.newBufferedReader(
                    outputFile, StandardCharsets.UTF_8)) {
                firstLine = reader.readLine();
            }

            if (HEADER.trim().equals(firstLine)) {
                return true;
            }

            final int oldColumnCount;
            final int trailingEmptyColumns;
            final boolean addLegacySession;

            if (PREVIOUS_HEADER.equals(firstLine)) {
                oldColumnCount = 24;
                trailingEmptyColumns = 20;
                addLegacySession = false;
            } else if (LEGACY_HEADER.equals(firstLine)) {
                oldColumnCount = 10;
                trailingEmptyColumns = 33;
                addLegacySession = true;
            } else {
                System.err.println(
                        "[Baritone 2.0] Unknown CSV header. "
                                + "Migration stopped to protect data."
                );
                return false;
            }

            Path temporaryFile = outputFile.resolveSibling(
                    "movement-data.csv.tmp"
            );

            try (BufferedReader reader = Files.newBufferedReader(
                    outputFile, StandardCharsets.UTF_8);
                 BufferedWriter writer = Files.newBufferedWriter(
                         temporaryFile,
                         StandardCharsets.UTF_8,
                         StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING)) {

                reader.readLine();
                writer.write(HEADER);

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }

                    int actualColumnCount = line.split(",", -1).length;

                    // Earlier 24-column migration left legacy rows one field short.
                    // Restore that final empty field before expanding to the new schema.
                    if (!addLegacySession
                            && actualColumnCount == oldColumnCount - 1) {
                        line = line + ",";
                        actualColumnCount++;
                    }

                    if (actualColumnCount != oldColumnCount) {
                        throw new IOException(
                                "Unexpected CSV row: expected "
                                        + oldColumnCount + " columns but found "
                                        + actualColumnCount + ". Migration stopped."
                        );
                    }

                    if (addLegacySession) {
                        writer.write("legacy,");
                    }
                    writer.write(line);
                    writer.write(",".repeat(trailingEmptyColumns));
                    writer.newLine();
                }
            }

            try {
                Files.move(
                        temporaryFile,
                        outputFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temporaryFile,
                        outputFile,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

            System.out.println(
                    "[Baritone 2.0] Existing CSV migrated to the action/obstacle schema."
            );
            return true;

        } catch (IOException exception) {
            System.err.println(
                    "[Baritone 2.0] CSV preparation failed: "
                            + exception.getMessage()
            );
            return false;
        }
    }

    private boolean isEnabled() {
        try {
            return Files.isRegularFile(enabledFile)
                    && "true".equalsIgnoreCase(
                    Files.readString(
                            enabledFile,
                            StandardCharsets.UTF_8
                    ).trim()
            );
        } catch (IOException ignored) {
            return false;
        }
    }

    private void savePreviousSample(
            long tick,
            double x,
            double y,
            double z,
            float health
    ) {
        lastTick = tick;
        lastX = x;
        lastY = y;
        lastZ = z;
        lastHealth = health;
    }

    private void resetSample() {
        lastTick = -1;
        lastHealth = -1.0F;
        resetActionCounts();
    }
}
