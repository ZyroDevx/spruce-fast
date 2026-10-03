package com.example.sprucefast;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import net.minecraft.text.Text;

import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

import net.minecraft.util.hit.BlockHitResult;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import org.lwjgl.glfw.GLFW;

public class SpruceFastClient implements ClientModInitializer {

    private static KeyBinding startKey;

    private static boolean enabled = false;

    private static BlockPos craftingTable = null;

    private static int timer = 0;

    private static final int SEARCH_RADIUS = 32;

    @Override
    public void onInitializeClient() {

        startKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding(
                "key.spruce_fast.start",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_BACKSPACE,
                KeyBinding.Category.create(
                    Identifier.of("spruce_fast", "main")
                )
            )
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            while (startKey.wasPressed()) {

                enabled = !enabled;

                craftingTable = null;
                timer = 0;

                stopWalking(client);

                if (client.player != null) {
                    client.player.sendMessage(
                        Text.literal(
                            "Spruce Fast: " +
                            (enabled ? "ON" : "OFF")
                        ),
                        true
                    );
                }
            }

            if (!enabled) {
                return;
            }

            if (client.player == null ||
                client.world == null ||
                client.interactionManager == null) {
                return;
            }

            timer++;

            if (timer < 2) {
                return;
            }

            timer = 0;

            run(client);
        });
    }

    private static void run(MinecraftClient client) {

        /*
         * Stop when there are no logs.
         */
        if (countLogs(client) <= 0) {

            stopWalking(client);

            if (client.player.currentScreenHandler
                    instanceof CraftingScreenHandler) {
                client.player.closeHandledScreen();
            }

            enabled = false;

            client.player.sendMessage(
                Text.literal("Spruce Fast: Finished."),
                true
            );

            return;
        }

        /*
         * If crafting table GUI is open,
         * perform the actual crafting.
         */
        if (client.player.currentScreenHandler
                instanceof CraftingScreenHandler) {

            stopWalking(client);

            craftOneLog(client);

            return;
        }

        /*
         * Find crafting table.
         */
        if (craftingTable == null ||
            !client.world.getBlockState(craftingTable)
                .isOf(Blocks.CRAFTING_TABLE)) {

            craftingTable = findCraftingTable(client);

            if (craftingTable == null) {

                stopWalking(client);

                enabled = false;

                client.player.sendMessage(
                    Text.literal(
                        "Spruce Fast: No crafting table within "
                        + SEARCH_RADIUS + " blocks."
                    ),
                    true
                );

                return;
            }
        }

        /*
         * Check distance.
         */
        double distance =
            client.player.getEntityPos()
                .distanceTo(
                    Vec3d.ofCenter(craftingTable)
                );

        /*
         * Walk toward table.
         */
        if (distance > 3.0) {

            walkToCraftingTable(client);

            return;
        }

        /*
         * Close enough.
         */
        stopWalking(client);

        openCraftingTable(client);
    }

    /*
     * Find the closest crafting table.
     */
    private static BlockPos findCraftingTable(
            MinecraftClient client) {

        BlockPos playerPos =
            client.player.getBlockPos();

        BlockPos closest = null;

        double closestDistance =
            Double.MAX_VALUE;

        for (int x = -SEARCH_RADIUS;
             x <= SEARCH_RADIUS;
             x++) {

            for (int y = -8;
                 y <= 8;
                 y++) {

                for (int z = -SEARCH_RADIUS;
                     z <= SEARCH_RADIUS;
                     z++) {

                    BlockPos pos =
                        playerPos.add(x, y, z);

                    if (!client.world
                        .getBlockState(pos)
                        .isOf(Blocks.CRAFTING_TABLE)) {
                        continue;
                    }

                    double distance =
                        client.player.getEntityPos()
                            .squaredDistanceTo(
                                Vec3d.ofCenter(pos)
                            );

                    if (distance < closestDistance) {

                        closestDistance = distance;
                        closest = pos;
                    }
                }
            }
        }

        return closest;
    }

    /*
     * Walk directly toward the crafting table.
     */
    private static void walkToCraftingTable(
            MinecraftClient client) {

        if (craftingTable == null) {
            return;
        }

        Vec3d target =
            Vec3d.ofCenter(craftingTable);

        Vec3d player =
            client.player.getEntityPos();

        double dx =
            target.x - player.x;

        double dz =
            target.z - player.z;

        double distance =
            Math.sqrt(dx * dx + dz * dz);

        if (distance < 2.5) {

            stopWalking(client);

            return;
        }

        /*
         * Calculate yaw toward table.
         */
        float yaw =
            (float) Math.toDegrees(
                Math.atan2(-dx, dz)
            );

        client.player.setYaw(
            MathHelper.lerpAngleDegrees(
                0.35f,
                client.player.getYaw(),
                yaw
            )
        );

        /*
         * Hold W.
         */
        client.options.forwardKey.setPressed(true);
    }

    /*
     * Release movement keys.
     */
    private static void stopWalking(
            MinecraftClient client) {

        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
    }

    /*
     * Open the crafting table.
     */
    private static void openCraftingTable(
            MinecraftClient client) {

        if (craftingTable == null) {
            return;
        }

        BlockHitResult hitResult =
            new BlockHitResult(
                Vec3d.ofCenter(craftingTable),
                Direction.UP,
                craftingTable,
                false
            );

        client.interactionManager.interactBlock(
            client.player,
            Hand.MAIN_HAND,
            hitResult
        );
    }

    /*
     * Craft EXACTLY ONE spruce log.
     *
     * Crafting table slots:
     *
     * 0     = result
     * 1-9   = 3x3 crafting grid
     * 10-36 = inventory
     * 37-45 = hotbar
     */
    private static void craftOneLog(
            MinecraftClient client) {

        if (!(client.player.currentScreenHandler
                instanceof CraftingScreenHandler)) {
            return;
        }

        CraftingScreenHandler handler =
            (CraftingScreenHandler)
                client.player.currentScreenHandler;

        /*
         * Make sure the cursor isn't already holding
         * something before we start.
         */
        if (!client.player.currentScreenHandler
                .getCursorStack().isEmpty()) {
            return;
        }

        int inventorySlot =
            findLogInventorySlot(client);

        if (inventorySlot == -1) {

            enabled = false;

            client.player.closeHandledScreen();

            client.player.sendMessage(
                Text.literal(
                    "Spruce Fast: No spruce logs left."
                ),
                true
            );

            return;
        }

        /*
         * STEP 1
         *
         * Left-click the inventory stack.
         *
         * This picks up the whole stack onto the cursor.
         */
        client.interactionManager.clickSlot(
            handler.syncId,
            inventorySlot,
            0,
            SlotActionType.PICKUP,
            client.player
        );

        /*
         * STEP 2
         *
         * Right-click the crafting-grid slot.
         *
         * Right-click places EXACTLY ONE item
         * from the cursor into the slot.
         */
        client.interactionManager.clickSlot(
            handler.syncId,
            1,
            1,
            SlotActionType.PICKUP,
            client.player
        );

        /*
         * STEP 3
         *
         * Put the remaining logs back into the
         * original inventory slot.
         */
        client.interactionManager.clickSlot(
            handler.syncId,
            inventorySlot,
            0,
            SlotActionType.PICKUP,
            client.player
        );

        /*
         * STEP 4
         *
         * Take the 4 spruce planks from the
         * crafting result.
         */
        client.interactionManager.clickSlot(
            handler.syncId,
            0,
            0,
            SlotActionType.QUICK_MOVE,
            client.player
        );
    }

    /*
     * Find a spruce-log inventory slot.
     */
    private static int findLogInventorySlot(
            MinecraftClient client) {

        var mainStacks =
            client.player.getInventory()
                .getMainStacks();

        for (int i = 0;
             i < mainStacks.size();
             i++) {

            ItemStack stack =
                mainStacks.get(i);

            if (!stack.isOf(Items.SPRUCE_LOG)) {
                continue;
            }

            /*
             * Player hotbar:
             * inventory index 0-8
             *
             * Crafting-screen slot:
             * 37-45
             */
            if (i < 9) {
                return 37 + i;
            }

            /*
             * Main inventory:
             * inventory index 9-35
             *
             * Crafting-screen slot:
             * 10-36
             */
            return 10 + (i - 9);
        }

        return -1;
    }

    /*
     * Count all spruce logs.
     */
    private static int countLogs(
            MinecraftClient client) {

        int amount = 0;

        for (ItemStack stack :
                client.player.getInventory()
                    .getMainStacks()) {

            if (stack.isOf(Items.SPRUCE_LOG)) {
                amount += stack.getCount();
            }
        }

        ItemStack offhand =
            client.player.getOffHandStack();

        if (offhand.isOf(Items.SPRUCE_LOG)) {
            amount += offhand.getCount();
        }

        return amount;
    }
}
