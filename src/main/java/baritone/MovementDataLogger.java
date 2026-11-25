
package baritone;

import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
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

    private static final String LEGACY_HEADER =
            "tick,x,y,z,health,on_ground,delta_x,delta_y,delta_z,damage_delta";

    private static final String HEADER =
            "session_id,tick,x,y,z,health,on_ground,"
            + "delta_x,delta_y,delta_z,damage_delta,"
            + "velocity_x,velocity_y,velocity_z,fall_distance,"
            + "sprinting,in_water,in_lava,on_fire,"
            + "nearby_hostile_count,nearest_hostile_distance,"
            + "hazard_below,hazard_feet,hazard_head\n";

    private final Minecraft minecraft;
    private final Path enabledFile;
    private final Path outputFile;
    private final String sessionId = UUID.randomUUID().toString();

    private long lastTick = -1;
    private double lastX, lastY, lastZ;
    private float lastHealth = -1.0F;

    private boolean schemaReady = false;
    private boolean loggedActivationState = false;

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
        if (event.getType() != TickEvent.Type.IN
                || event.getCount() % 20 != 0) {
            return;
        }

        if (!isEnabled()) {
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

        LocalPlayer player = minecraft.player;

        if (player == null || minecraft.level == null) {
            resetSample();
            return;
        }

        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float health = player.getHealth();

        if (lastTick < 0) {
            savePreviousSample(event.getCount(), x, y, z, health);
            return;
        }

        double dx = x - lastX;
        double dy = y - lastY;
        double dz = z - lastZ;
        double damage = Math.max(0.0F, lastHealth - health);

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

        String row = String.format(
                Locale.ROOT,
                "%s,%d,%.3f,%.3f,%.3f,%.2f,%b,"
                        + "%.3f,%.3f,%.3f,%.2f,"
                        + "%.3f,%.3f,%.3f,%.3f,"
                        + "%b,%b,%b,%b,%d,%.3f,%s,%s,%s%n",
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
                hazardHead
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
            if (!Files.exists(outputFile)
                    || Files.size(outputFile) == 0) {
                Files.writeString(
                        outputFile,
                        HEADER,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                );
                return true;
            }

            try (BufferedReader reader = Files.newBufferedReader(
                    outputFile, StandardCharsets.UTF_8)) {

                String firstLine = reader.readLine();

                if (HEADER.trim().equals(firstLine)) {
                    return true;
                }

                if (!LEGACY_HEADER.equals(firstLine)) {
                    System.err.println(
                            "[Baritone 2.0] Unknown CSV header. "
                                    + "Migration stopped to protect data."
                    );
                    return false;
                }
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

                    if (line.split(",", -1).length != 10) {
                        throw new IOException(
                                "Unexpected legacy CSV row. Migration stopped."
                        );
                    }

                    writer.write("legacy,");
                    writer.write(line);
                    writer.write(",,,,,,,,,,,,");
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
                    "[Baritone 2.0] Existing CSV migrated successfully."
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
    }
}
