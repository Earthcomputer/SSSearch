package net.earthcomputer.sssearch;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SSSearch {
    public static boolean setupMode = false;
    private static final List<GlobalPos> positionsToHighlight = new ArrayList<>();
    private static long displayStartTime;
    private static final long TIME_TO_HIGHLIGHT = 30L * 1000 * 1000 * 1000; // 30 seconds

    public static void setupContainer(GlobalPos pos, List<ItemStack> items) {
        Set<Item> itemTypes = items.stream()
                .flatMap(stack -> {
                    if (stack.getItem() instanceof BlockItem && ((BlockItem) stack.getItem()).getBlock() instanceof ShulkerBoxBlock) {
                        CompoundTag blockEntityData = BlockItem.getBlockEntityData(stack);
                        if (blockEntityData != null && blockEntityData.contains("Items", Tag.TAG_LIST)) {
                            NonNullList<ItemStack> contents = NonNullList.withSize(27, ItemStack.EMPTY);
                            ContainerHelper.loadAllItems(blockEntityData, contents);
                            return contents.stream();
                        }
                        return Stream.empty();
                    }
                    return Stream.of(stack);
                })
                .filter(stack -> !stack.isEmpty())
                .map(ItemStack::getItem)
                .collect(Collectors.toSet());

        if (itemTypes.size() != 1) {
            Minecraft.getInstance().gui.getChat().addMessage(Component.translatable("sssearch.multipleItemTypes").withStyle(ChatFormatting.DARK_RED));
            return;
        }

        Item item = itemTypes.iterator().next();
        SSSearchData searchData = SSSearchData.forCurrentWorld();
        searchData.addItem(pos, item);
        searchData.save();
        Minecraft.getInstance().gui.getChat().addMessage(Component.translatable("sssearch.add.success", new ItemStack(item).getDisplayName()));
    }

    public static void setPositionsToHighlight(Iterable<GlobalPos> positions) {
        positionsToHighlight.clear();
        for (GlobalPos position : positions) {
            positionsToHighlight.add(position);
        }
        displayStartTime = System.nanoTime();
    }

    public static void render(PoseStack poseStack, Camera camera, MultiBufferSource.BufferSource consumers) {
        if (positionsToHighlight.isEmpty()) {
            return;
        }

        long elapsedTime = System.nanoTime() - displayStartTime;
        if (elapsedTime > TIME_TO_HIGHLIGHT) {
            positionsToHighlight.clear();
            return;
        }

        poseStack.pushPose();
        Vec3 cameraPos = camera.getPosition();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        for (GlobalPos pos : positionsToHighlight) {
            if (pos.dimension() == Minecraft.getInstance().level.dimension()) {
                renderBlockHighlight(poseStack, consumers, pos.pos(), elapsedTime);
            }
        }
        poseStack.popPose();
    }

    private static void renderBlockHighlight(PoseStack matrices, MultiBufferSource.BufferSource consumers, BlockPos blockPos, long elapsedTime) {
        AABB boundingBox = new AABB(blockPos);

        RenderSystem.setShader(GameRenderer::getPositionTexColorNormalShader);
        VertexConsumer vertexConsumer = consumers.getBuffer(NO_DEPTH_LAYER.apply(null));

        int rgb = Mth.hsvToRgb((float) (elapsedTime / 2000000000.0), 0.7f, 0.6f);
        float alpha = 1;
        float red = ((rgb >> 16) & 0xff) / 255f;
        float green = ((rgb >> 8) & 0xff) / 255f;
        float blue = ((rgb) & 0xff) / 255f;

        LevelRenderer.renderLineBox(matrices, vertexConsumer, boundingBox, red, green, blue, alpha);
    }

    private static final Function<Void, RenderType> NO_DEPTH_LAYER = Util.memoize($ -> {
        RenderType.CompositeState multiPhaseParameters = RenderType.CompositeState.builder()
                .setShaderState(RenderType.RENDERTYPE_LINES_SHADER)
                .setTransparencyState(new RenderType.TransparencyStateShard("translucent_transparency", () -> {
                    RenderSystem.enableBlend();
                    RenderSystem.defaultBlendFunc();
                }, () -> {
                    RenderSystem.disableBlend();
                    RenderSystem.defaultBlendFunc();
                }))
                .setWriteMaskState(RenderType.COLOR_WRITE)
                .setCullState(RenderType.NO_CULL)
                .setDepthTestState(RenderType.NO_DEPTH_TEST)
                .setLayeringState(RenderType.VIEW_OFFSET_Z_LAYERING)
                .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(2)))
                .createCompositeState(true);
        return RenderType.create("custom_translucent", DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 256, true, true, multiPhaseParameters);
    });
}
