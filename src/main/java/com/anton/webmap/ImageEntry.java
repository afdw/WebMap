package com.anton.webmap;

import net.minecraft.block.state.IBlockState;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

public class ImageEntry {
    public final IBlockState state;
    public final File file;
    public final BufferedImage image;
    public final boolean hasNoTransparentPixels;

    public ImageEntry(IBlockState state) throws IOException {
        this.state = state;
        file = new File("blockstates/" + state.toString() + ".png");
        image = ImageIO.read(file);
        hasNoTransparentPixels = Arrays.stream(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()))
            .map(color -> (color >> 24) & 0x000000FF)
            .allMatch(alpha -> alpha == 0xFF);
//        int[] alphas = Arrays.stream(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()))
//                .map(color -> (color >> 24) & 0x000000FF)
//                .toArray();
    }
}
