package com.oneshotanchor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JSON config stored at .minecraft/config/oneshotanchor.json.
 * Values are clamped on load, and the normalised file is written back,
 * so a missing or broken file is always repaired.
 */
public final class AnchorConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AnchorConfig instance = new AnchorConfig();

    /** Master toggle (H key). Saved so it survives restarts. */
    public boolean enabled = false;
    /** Ticks to wait between steps (20 ticks = 1 second). Raise this on high ping. */
    public int stepDelayTicks = 2;
    /** Glowstone charges to apply before detonating (1-4). */
    public int glowstoneCharges = 1;
    /** Put the hotbar slot back to what it was before the key press. */
    public boolean restoreOriginalSlot = true;
    /** Refuse to run if the anchor would be closer than safeDistance to the player. */
    public boolean safeMode = true;
    public double safeDistance = 3.5;
    /** Place the anchor with a sneak packet so it never opens chests, doors, etc. */
    public boolean sneakPlacement = true;
    /** Max ticks to wait for the server to confirm a placement/charge before aborting. */
    public int confirmTimeoutTicks = 20;

    public static AnchorConfig get() {
        return instance;
    }

    public static void load() {
        Path path = path();
        AnchorConfig cfg = new AnchorConfig();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                AnchorConfig read = GSON.fromJson(reader, AnchorConfig.class);
                if (read != null) cfg = read;
            } catch (Exception e) {
                OneShotAnchorClient.LOGGER.warn("Could not read config, using defaults", e);
            }
        }
        cfg.sanitize();
        instance = cfg;
        cfg.save();
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("oneshotanchor.json");
    }

    /** Writes the current values to disk (used when the toggle changes). */
    public void save() {
        try {
            Files.writeString(path(), GSON.toJson(this));
        } catch (IOException e) {
            OneShotAnchorClient.LOGGER.warn("Could not write config", e);
        }
    }

    private void sanitize() {
        stepDelayTicks = Math.max(0, Math.min(20, stepDelayTicks));
        glowstoneCharges = Math.max(1, Math.min(4, glowstoneCharges));
        safeDistance = Math.max(0.0, Math.min(16.0, safeDistance));
        confirmTimeoutTicks = Math.max(5, Math.min(100, confirmTimeoutTicks));
    }
}
