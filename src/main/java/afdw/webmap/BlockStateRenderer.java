package afdw.webmap;

import afdw.smallpng.SmallPNG;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.block.statemap.BlockStateMapper;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.init.Biomes;
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
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public enum BlockStateRenderer {
    INSTANCE;

    private final Set<IBlockState> allBlockStates = new TreeSet<>(
        Comparator.comparing(Helpers::blockStateToString)
    );
    private final Queue<IBlockState> blockStateQueue = new ArrayDeque<>();
    private final ThreadPoolExecutor writerExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors(),
        new ThreadFactoryBuilder()
            .setNameFormat("BlockState Writer %d")
            .setDaemon(true)
            .build()
    );

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public void onKeyboardInput(GuiScreenEvent.KeyboardInputEvent.Post event) {
        if (Keyboard.isKeyDown(Keyboard.KEY_MINUS)) {
            if (allBlockStates.isEmpty()) {
                allBlockStates.addAll(new BlockStateMapper().putAllStateModelLocations().keySet());
            }
            if (blockStateQueue.isEmpty()) {
                try {
                    Files.createDirectories(Paths.get("blockstates"));
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
        if (!blockStateQueue.isEmpty() || !writerExecutor.getQueue().isEmpty()) {
            GlStateManager.clearColor(0, 0, 0, 0);
            GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT);
            GlStateManager.viewport(0, 0, displayWidth, displayHeight);
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.loadIdentity();
            GlStateManager.ortho(0, displayWidth, displayHeight, 0, -Tile.SIZE, Tile.SIZE);
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.loadIdentity();
        }
        long time = System.currentTimeMillis();
        IBlockState lastState = Blocks.AIR.getDefaultState();
        while (!blockStateQueue.isEmpty() &&
            writerExecutor.getQueue().size() < 1000 &&
            (System.currentTimeMillis() - time) < 1000 / 60) {
            GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT);
            IBlockState state = blockStateQueue.poll();
            Tessellator.getInstance().getBuffer().begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
            try {
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

                    @SuppressWarnings("ConstantConditions")
                    @Override
                    public Biome getBiome(BlockPos pos) {
                        return Biomes.PLAINS;
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
                }, Tessellator.getInstance().getBuffer());
            } catch (Exception e) {
                Tessellator.getInstance().getBuffer().finishDrawing();
                Tessellator.getInstance().getBuffer().reset();
                e.printStackTrace();
                continue;
            }
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
            writerExecutor.submit(() -> {
                try (OutputStream outputStream = new FileOutputStream(
                    Paths.get("blockstates", Helpers.blockStateToString(state) + ".png").toFile()
                )) {
                    SmallPNG.write(outputStream, buffer, Tile.SIZE, Tile.SIZE);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            lastState = state;
        }
        GlStateManager.disableTexture2D();
        GlStateManager.color(1, 1, 1, 1);
        Tessellator.getInstance().getBuffer().begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        Tessellator.getInstance().getBuffer()
            .pos(
                0,
                displayHeight - 16,
                0
            )
            .endVertex();
        Tessellator.getInstance().getBuffer()
            .pos(
                0,
                displayHeight,
                0
            )
            .endVertex();
        Tessellator.getInstance().getBuffer()
            .pos(
                displayWidth - (double) displayWidth / allBlockStates.size() * blockStateQueue.size(),
                displayHeight,
                0
            )
            .endVertex();
        Tessellator.getInstance().getBuffer()
            .pos(
                displayWidth - (double) displayWidth / allBlockStates.size() * blockStateQueue.size(),
                displayHeight - 16,
                0
            )
            .endVertex();
        Tessellator.getInstance().draw();
        GlStateManager.enableTexture2D();
        Minecraft.getMinecraft().fontRendererObj.drawString(
            "Compressing queue size: " + writerExecutor.getQueue().size(),
            0,
            displayHeight - 48,
            0xFFFFFFFF
        );
        Minecraft.getMinecraft().fontRendererObj.drawString(
            "Last rendered block state: " + Helpers.blockStateToString(lastState),
            0,
            displayHeight - 32,
            0xFFFFFFFF
        );
    }
}
