package com.oneshotanchor;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint. Two keybinds:
 *  - toggle key (H): master ON/OFF switch, saved to the config file
 *  - sequence key (3): runs one anchor sequence, but only while the toggle is ON
 */
public class OneShotAnchorClient implements ClientModInitializer {
    public static final String MOD_ID = "oneshotanchor";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final AnchorSequence SEQUENCE = new AnchorSequence();
    private static KeyBinding toggleKey;
    private static KeyBinding sequenceKey;

    /**
     * Hotbar slot at the very start of the tick. The default sequence key (3) is also the
     * vanilla "hotbar slot 3" key, which switches slots before our END_CLIENT_TICK handler
     * runs. Reading the slot at tick start gives the slot the player was really holding.
     */
    private static int slotAtTickStart = -1;

    @Override
    public void onInitializeClient() {
        AnchorConfig.load();

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.oneshotanchor.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, category));
        sequenceKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.oneshotanchor.activate", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_3, category));

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.player != null && !SEQUENCE.isRunning()) {
                slotAtTickStart = client.player.getInventory().getSelectedSlot();
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
    }

    private void onEndTick(MinecraftClient client) {
        AnchorConfig cfg = AnchorConfig.get();

        // Toggle: each press flips the state once (no auto-repeat, wasPressed consumes it).
        while (toggleKey.wasPressed()) {
            if (client.currentScreen != null || client.player == null) continue;
            cfg.enabled = !cfg.enabled;
            cfg.save();
            client.player.sendMessage(
                    Text.literal(cfg.enabled ? "AutoAnchor: ON" : "AutoAnchor: OFF")
                            .formatted(cfg.enabled ? Formatting.GREEN : Formatting.RED),
                    true); // true = action bar
        }

        // Sequence key: consume every queued press so one press can never start two runs.
        boolean pressed = false;
        while (sequenceKey.wasPressed()) {
            pressed = true;
        }
        // Does nothing at all while the toggle is OFF.
        if (pressed && cfg.enabled && !SEQUENCE.isRunning() && client.currentScreen == null) {
            SEQUENCE.start(client, slotAtTickStart);
        }
        SEQUENCE.tick(client);
    }
}
