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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.hit.BlockHitResult;

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

        if (countLogs(client) <= 0) {

            stopWalking(client);

            enabled = false;

            client.player.sendMessage(
                Text.literal("Spruce Fast: Finished."),
                true
            );

            return;
        }

        /*
         * If the crafting table GUI is open,
         * craft spruce planks.
         */
        if (client.player.currentScreenHandler
                instanceof CraftingScreenHandler) {

            stopWalking(client);

            craft(client);

            return;
        }

        /*
         * Find a crafting table.
         */
        if (craftingTable == null ||
            !client.world.getBlockState(craftingTable)
                .isOf(Blocks.CRAFTING_TABLE)) {

            craftingTable = findCraftingTable(client);

            if (craftingTable == null) {

                stopWalking(client);

                client.player.sendMessage(
                    Text.literal(
                        "Spruce Fast: No crafting table within "
                        + SEARCH_RADIUS + " blocks."
                    ),
                    true
                );

                enabled = false;

                return;
            }
        }

        /*
         * getEntityPos() is the 1.21.11
         * player-position method.
         */
        double distance =
            client.player.getEntityPos()
                .distanceTo(
                    Vec3d.ofCenter(craftingTable)
                );

        /*
         * Walk toward crafting table.
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

        double horizontalDistance =
            Math.sqrt(dx * dx + dz * dz);

        if (horizontalDistance < 2.5) {

            stopWalking(client);

            return;
        }

        /*
         * Turn toward crafting table.
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
         * Hold forward.
         */
        client.options.forwardKey.setPressed(true);
    }

    private static void stopWalking(
            MinecraftClient client) {

        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
    }

    private static void openCraftingTable(
            MinecraftClient client) {

        if (craftingTable == null) {
            return;
        }

        Vec3d hitPos =
            Vec3d.ofCenter(craftingTable);

        BlockHitResult hitResult =
            new BlockHitResult(
                hitPos,
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

    private static void craft(
            MinecraftClient client) {

        if (!(client.player.currentScreenHandler
                instanceof CraftingScreenHandler)) {

            return;
        }

        CraftingScreenHandler handler =
            (CraftingScreenHandler)
                client.player.currentScreenHandler;

        int logSlot =
            findLogInventorySlot(client);

        if (logSlot == -1) {

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
         * Move spruce logs into the crafting grid.
         */
        client.interactionManager.clickSlot(
            handler.syncId,
            logSlot,
            0,
            SlotActionType.QUICK_MOVE,
            client.player
        );

        /*
         * Take the resulting spruce planks.
         */
        client.interactionManager.clickSlot(
            handler.syncId,
            0,
            0,
            SlotActionType.QUICK_MOVE,
            client.player
        );
    }

    private static int findLogInventorySlot(
            MinecraftClient client) {

        var inventory =
            client.player.getInventory();

        var mainStacks =
            inventory.getMainStacks();

        for (int i = 0;
             i < mainStacks.size();
             i++) {

            ItemStack stack =
                mainStacks.get(i);

            if (!stack.isOf(Items.SPRUCE_LOG)) {
                continue;
            }

            /*
             * Hotbar:
             * 37-45
             */
            if (i < 9) {
                return 37 + i;
            }

            /*
             * Main inventory:
             * 10-36
             */
            return 10 + (i - 9);
        }

        return -1;
    }

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
