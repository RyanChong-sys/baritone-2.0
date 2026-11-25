package baritone;

import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import et.minecraft.client.Minecraft;
import et.minecraft.client.player.LocalPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Optional low-frequency telemetry for the Baritone 2.0 training dataset.
 * Logging is disabled unless movement-data-logger.enabled contains "true".
 * This listener never changes movement inputs or pathfinding decisions.
 */
public final class MovementDataLogger implements AbstractGameEventListener {
    private static final String HEADER =
            "tick,x,y,z,health,on_ground,delta_x,delta_y,delta_z,damage_delta\n";

    private final Minecraft minecraft;
    private final Path enabledFile;
    private final Path outputFile;

    private long lastTick = -1;
    private double lastX, lastY, lastZ;
    private float lastHealth = -1.0F;

    public MovementDataLogger(Minecraft minecraft, Path directory) {
        this.minecraft = minecraft;
        this.enabledFile = directory.resolve("movement-data-logger.enabled");
        this.outputFile = directory.resolve("movement-data.csv");
    }

    @Override
    public void onPostTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN || event.getCount() % 20 != 0) return;
        if (!isEnabled()) {
            resetSample();
            return;
        }

        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            resetSample();
            return;
        }

        double x = player.getX(), y = player.getY(), z = player.getZ();
        float health = player.getHealth();
        if (lastTick &lt; 0) {
 lastTick = event.getCount();
            lastX = x; lastY = y; lastZ = z; lastHealth = health;
            return;
        }

        String row = String.format(Locale.ROOT,
                "%d,%.3f,%.3f,%.3f,%.2f,%b,%.3f,%.3f,%.3f,%.2f%n",
 event.getCount(), x, y, z, health, player.onGround(),
 x - lastX, y - lastY, z - lastZ, Math.max(0.0F, lastHealth - health));

        try {
            if (!Files.exists(outputFile)) {
 Files.writeString(outputFile, HEADER, StandardCharsets.UTF_8,
 StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
 Files.writeString(outputFile, row, StandardCharsets.UTF_8,
 StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
 System.err.println("[Baritone 2.0] Could not write movement telemetry: " + exception.getMessage());
        }

 lastTick = event.getCount();
        lastX = x; lastY = y; lastZ = z; lastHealth = health;
    }

    private boolean isEnabled() {
        try {
            return Files.isRegularFile(enabledFile)
 && "true".equalsIgnoreCase(Files.readString(enabledFile, StandardCharsets.UTF_8).trim());
        } catch (IOException ignored) {
            return false;
        }
    }

    private void resetSample() {
 lastTick = -1;
 lastHealth = -1.0F;
    }
}
