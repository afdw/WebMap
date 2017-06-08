package com.anton.webmap;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.VertexBuffer;
import net.minecraft.client.renderer.block.statemap.BlockStateMapper;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.commons.io.FileUtils;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.*;

public enum BlockStateRenderer {
    INSTANCE;

    private final Set<IBlockState> allBlockStates = new TreeSet<>(
        Comparator.comparing(Helpers::blockStateToString)
    );
    private static final Queue<IBlockState> blockStateQueue = new ArrayDeque<>();

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public void onKeyboardInput(GuiScreenEvent.KeyboardInputEvent.Post event) {
        if (Keyboard.isKeyDown(Keyboard.KEY_MINUS)) {
            if (allBlockStates.isEmpty()) {
                allBlockStates.addAll(new BlockStateMapper().putAllStateModelLocations().keySet());
            }
            if (blockStateQueue.isEmpty()) {
                try {
                    if (!new File("blockstates").mkdirs()) {
                        throw new IOException();
                    }
                    FileUtils.cleanDirectory(new File("blockstates"));
                } catch (IOException e) {
                    e.printStackTrace();
                }
                blockStateQueue.addAll(allBlockStates);
            }
//            blockStateQueue.add(Blocks.CAULDRON.getDefaultState());
        }
    }

    // FIXME: fix using this event
    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public void onGuiScreen(GuiScreenEvent.DrawScreenEvent.Post event) {
        int displayWidth = Minecraft.getMinecraft().displayWidth;
        int displayHeight = Minecraft.getMinecraft().displayHeight;
        long time = System.currentTimeMillis();
        IBlockState lastState = Blocks.AIR.getDefaultState();
        while (!blockStateQueue.isEmpty() && (System.currentTimeMillis() - time) < 1000 / 60) {
            IBlockState state = blockStateQueue.poll();
            GlStateManager.clearColor(0, 0, 0, 0);
            GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT);
            GlStateManager.viewport(0, 0, displayWidth, displayHeight);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0, displayWidth, displayHeight, 0, -Tile.SIZE, Tile.SIZE);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            VertexBuffer vb = Tessellator.getInstance().getBuffer();
            vb.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
            Minecraft.getMinecraft().getBlockRendererDispatcher().renderBlock(state, BlockPos.ORIGIN, new IBlockAccess() {
                @Nullable
                @Override
                public TileEntity getTileEntity(BlockPos pos) {
                    return null;
                }

                @Override
                public int getCombinedLight(BlockPos pos, int lightValue) {
                    return 15 << 20 | 15 << 4;
                }

                @Override
                public IBlockState getBlockState(BlockPos pos) {
                    return pos.equals(BlockPos.ORIGIN) ? state : Blocks.AIR.getDefaultState();
                }

                @Override
                public boolean isAirBlock(BlockPos pos) {
                    return !pos.equals(BlockPos.ORIGIN);
                }

                @Override
                public Biome getBiome(BlockPos pos) {
                    return Biome.getBiome(1);
                }

                @Override
                public int getStrongPower(BlockPos pos, EnumFacing direction) {
                    return 0;
                }

                @Override
                public WorldType getWorldType() {
                    return WorldType.DEBUG_WORLD;
                }

                @Override
                public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean _default) {
                    return _default;
                }
            }, vb);
            Minecraft.getMinecraft().renderEngine.bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            GlStateManager.pushMatrix();
            GlStateManager.scale(Tile.SIZE, Tile.SIZE, Tile.SIZE);
            GlStateManager.translate(0.5, 0.5, 0.5);
            GlStateManager.rotate(270, 1, 0, 0);
            GlStateManager.translate(-0.5, -0.5, -0.5);
            Tessellator.getInstance().draw();
            GlStateManager.popMatrix();
            ByteBuffer buffer = BufferUtils.createByteBuffer(Tile.SIZE * Tile.SIZE * 4);
            GL11.glReadPixels(
                0,
                displayHeight - Tile.SIZE,
                Tile.SIZE,
                Tile.SIZE,
                GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE,
                buffer
            );
            try {
                Tile tile = new Tile(Paths.get("blockstates", state.toString() + Tile.EXTENSION));
                tile.buffer.put(buffer);
                tile.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            lastState = state;
        }
        GlStateManager.disableTexture2D();
        GlStateManager.color(1, 1, 1, 1);
        GlStateManager.glBegin(GL11.GL_QUADS);
        double posX = displayWidth - (double) displayWidth / allBlockStates.size() * blockStateQueue.size();
        GL11.glVertex2d(0, displayHeight - 16);
        GL11.glVertex2d(0, displayHeight);
        GL11.glVertex2d(posX, displayHeight);
        GL11.glVertex2d(posX, displayHeight - 16);
        GlStateManager.glEnd();
        GlStateManager.enableTexture2D();
        Minecraft.getMinecraft().fontRendererObj.drawString(lastState.toString(), 0, displayHeight - 32, 0xFFFFFFFF);
    }
}
