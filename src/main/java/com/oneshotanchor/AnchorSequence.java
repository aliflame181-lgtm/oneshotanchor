package com.oneshotanchor;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.function.Predicate;

/**
 * Tick-based state machine for one anchor run:
 * PLACE -> CHARGE (xN) -> DETONATE -> RESTORE -> IDLE.
 * It only starts via start() and always ends in IDLE, so it can never loop or toggle.
 */
public final class AnchorSequence {
    private enum Step { IDLE, PLACE, CHARGE, DETONATE, RESTORE }

    private static final String MSG_STARTED = "OneShot Anchor Activated";

    private Step step = Step.IDLE;
    private int delay;        // ticks to wait before the next step runs
    private int timeout;      // ticks left while waiting for server confirmation
    private int clicksSent;   // glowstone clicks sent so far
    private int targetCharges;
    private int originalSlot, anchorSlot, glowstoneSlot, detonatorSlot;
    private BlockPos anchorPos;
    private BlockHitResult placeHit;

    public boolean isRunning() {
        return step != Step.IDLE;
    }

    // ------------------------------------------------------------------ start

    /** Validates everything up front; if anything is wrong nothing is placed. */
    public void start(MinecraftClient mc, int slotBeforeKey) {
        AnchorConfig cfg = AnchorConfig.get();
        ClientPlayerEntity player = mc.player;
        ClientWorld world = mc.world;
        if (player == null || world == null || mc.interactionManager == null) return;

        if (world.getRegistryKey() == World.NETHER) {
            msg(player, "Anchors don't explode in the Nether", Formatting.RED);
            return;
        }
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            msg(player, "Look at a block first", Formatting.RED);
            return;
        }

        anchorSlot = findHotbar(player, s -> s.isOf(Items.RESPAWN_ANCHOR));
        if (anchorSlot < 0) { msg(player, "Missing Respawn Anchor", Formatting.RED); return; }

        glowstoneSlot = findHotbar(player, s -> s.isOf(Items.GLOWSTONE));
        if (glowstoneSlot < 0) { msg(player, "Missing Glowstone", Formatting.RED); return; }

        detonatorSlot = findDetonator(player);
        if (detonatorSlot < 0) { msg(player, "Missing Totem of Undying", Formatting.RED); return; }

        // Work out where the anchor will really land (handles replaceable blocks like grass).
        ItemStack anchorStack = player.getInventory().getStack(anchorSlot);
        ItemPlacementContext ctx = new ItemPlacementContext(player, Hand.MAIN_HAND, anchorStack, hit);
        BlockPos placePos = ctx.getBlockPos();
        if (!ctx.canPlace()
                || !world.canPlace(Blocks.RESPAWN_ANCHOR.getDefaultState(), placePos, ShapeContext.of(player))) {
            msg(player, "Can't place an anchor there", Formatting.RED);
            return;
        }

        if (cfg.safeMode && distanceTo(player, placePos) < cfg.safeDistance) {
            msg(player, "Too close to the anchor (safe mode)", Formatting.YELLOW);
            return;
        }

        int available = player.getInventory().getStack(glowstoneSlot).getCount();
        targetCharges = Math.min(cfg.glowstoneCharges, available);
        clicksSent = 0;
        anchorPos = placePos;
        placeHit = hit;
        originalSlot = slotBeforeKey >= 0 ? slotBeforeKey : player.getInventory().getSelectedSlot();

        step = Step.PLACE;
        delay = 0;
        msg(player, MSG_STARTED, Formatting.GREEN);
    }

    // ------------------------------------------------------------------- tick

    public void tick(MinecraftClient mc) {
        if (step == Step.IDLE) return;

        ClientPlayerEntity player = mc.player;
        ClientWorld world = mc.world;
        if (player == null || world == null || mc.interactionManager == null || !player.isAlive()) {
            reset();
            return;
        }
        if (delay > 0) {
            delay--;
            return;
        }

        AnchorConfig cfg = AnchorConfig.get();
        switch (step) {
            case PLACE -> {
                select(player, anchorSlot);
                // Sneak-placing never triggers the clicked block's own use action (chest, door...).
                boolean ok = interact(mc, player, placeHit, cfg.sneakPlacement);
                if (!ok) { abort(mc, player, "Anchor placement failed"); return; }
                step = Step.CHARGE;
                timeout = cfg.confirmTimeoutTicks;
                delay = cfg.stepDelayTicks;
            }
            case CHARGE -> {
                BlockState state = world.getBlockState(anchorPos);
                if (!state.isOf(Blocks.RESPAWN_ANCHOR)) {
                    // Placement not confirmed yet, keep waiting.
                    if (--timeout <= 0) abort(mc, player, "Anchor was not placed");
                    return;
                }
                int charges = state.get(RespawnAnchorBlock.CHARGES);
                if (charges >= targetCharges) {
                    step = Step.DETONATE;
                    timeout = cfg.confirmTimeoutTicks;
                    delay = cfg.stepDelayTicks;
                } else if (charges < clicksSent) {
                    // A previous click has not been confirmed by the server yet.
                    if (--timeout <= 0) abort(mc, player, "Charge not confirmed");
                } else {
                    if (!player.getInventory().getStack(glowstoneSlot).isOf(Items.GLOWSTONE)) {
                        abort(mc, player, "Missing Glowstone");
                        return;
                    }
                    select(player, glowstoneSlot);
                    if (!interact(mc, player, faceHit(player, anchorPos), false)) {
                        abort(mc, player, "Charging failed");
                        return;
                    }
                    clicksSent++;
                    timeout = cfg.confirmTimeoutTicks;
                    delay = cfg.stepDelayTicks;
                }
            }
            case DETONATE -> {
                BlockState state = world.getBlockState(anchorPos);
                if (!state.isOf(Blocks.RESPAWN_ANCHOR) || state.get(RespawnAnchorBlock.CHARGES) < 1) {
                    if (--timeout <= 0) abort(mc, player, "Anchor is not charged");
                    return;
                }
                // Re-check: the player may have walked towards the anchor meanwhile.
                if (cfg.safeMode && distanceTo(player, anchorPos) < cfg.safeDistance) {
                    abort(mc, player, "Too close, detonation skipped");
                    return;
                }
                select(player, detonatorSlot);
                if (!interact(mc, player, faceHit(player, anchorPos), false)) {
                    abort(mc, player, "Detonation failed");
                    return;
                }
                step = Step.RESTORE;
                delay = cfg.stepDelayTicks;
            }
            case RESTORE -> {
                if (cfg.restoreOriginalSlot) select(player, originalSlot);
                msg(player, "Sequence complete", Formatting.GREEN);
                reset();
            }
            default -> reset();
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Right-clicks a block through the interaction manager, which sends the real
     * slot-sync and block-interact packets. Optionally forces the sneak state for
     * the duration of the click (server-side too, via a player-input packet).
     */
    private boolean interact(MinecraftClient mc, ClientPlayerEntity player, BlockHitResult hit, boolean sneak) {
        boolean wasSneaking = player.isSneaking();
        boolean override = wasSneaking != sneak;
        if (override) {
            sendInput(mc, player, sneak);
            player.setSneaking(sneak);
        }

        ActionResult result = mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        boolean accepted = result.isAccepted();
        if (accepted) player.swingHand(Hand.MAIN_HAND);

        if (override) {
            // Put the server back to the sneak state the player really has.
            sendInput(mc, player, mc.options.sneakKey.isPressed());
            player.setSneaking(wasSneaking);
        }
        return accepted;
    }

    private void sendInput(MinecraftClient mc, ClientPlayerEntity player, boolean sneak) {
        if (mc.getNetworkHandler() == null) return;
        GameOptions o = mc.options;
        PlayerInput input = new PlayerInput(
                o.forwardKey.isPressed(), o.backKey.isPressed(),
                o.leftKey.isPressed(), o.rightKey.isPressed(),
                o.jumpKey.isPressed(), sneak, player.isSprinting());
        mc.getNetworkHandler().sendPacket(new PlayerInputC2SPacket(input));
    }

    /** A hit on the anchor face that points at the player, always within server tolerance. */
    private BlockHitResult faceHit(ClientPlayerEntity player, BlockPos pos) {
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d eye = player.getEyePos();
        Direction face = Direction.getFacing(eye.x - center.x, eye.y - center.y, eye.z - center.z);
        Vec3d hitPos = center.add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
        return new BlockHitResult(hitPos, face, pos, false);
    }

    private void select(ClientPlayerEntity player, int slot) {
        // interactBlock() syncs the selected slot to the server before it sends the click.
        player.getInventory().setSelectedSlot(slot);
    }

    private int findHotbar(ClientPlayerEntity player, Predicate<ItemStack> test) {
        for (int i = 0; i < 9; i++) {
            if (test.test(player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    /** Totem of Undying first, otherwise any non-empty, non-block item. */
    private int findDetonator(ClientPlayerEntity player) {
        int totem = findHotbar(player, s -> s.isOf(Items.TOTEM_OF_UNDYING));
        if (totem >= 0) return totem;
        return findHotbar(player, s -> !s.isEmpty() && !(s.getItem() instanceof BlockItem));
    }

    private double distanceTo(ClientPlayerEntity player, BlockPos pos) {
        return Math.sqrt(player.squaredDistanceTo(Vec3d.ofCenter(pos)));
    }

    private void abort(MinecraftClient mc, ClientPlayerEntity player, String reason) {
        if (AnchorConfig.get().restoreOriginalSlot) select(player, originalSlot);
        msg(player, reason, Formatting.RED);
        reset();
    }

    private void reset() {
        step = Step.IDLE;
        delay = 0;
        timeout = 0;
        clicksSent = 0;
        anchorPos = null;
        placeHit = null;
    }

    private void msg(ClientPlayerEntity player, String text, Formatting color) {
        player.sendMessage(Text.literal(text).formatted(color), true); // true = action bar
    }
}
