package afdw.webmap;

import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Paths;

public class DisplayTile {
    public static void main(String[] args) throws IOException {
        Tile tile = new Tile(Paths.get(args[0]));
        BufferedImage image = new BufferedImage(Tile.SIZE, Tile.SIZE, BufferedImage.TYPE_INT_ARGB);
        int[] rgbArray = new int[Tile.SIZE * Tile.SIZE];
        for (int i = 0; i < Tile.SIZE * Tile.SIZE; i++) {
            int p = i * 4;
            rgbArray[i] = (tile.buffer.get(p + 3) & 0xFF) << 24 |
                (tile.buffer.get(p) & 0xFF) << 16 |
                (tile.buffer.get(p + 1) & 0xFF) << 8 |
                tile.buffer.get(p + 2) & 0xFF;
        }
        image.setRGB(0, 0, Tile.SIZE, Tile.SIZE, rgbArray, 0, Tile.SIZE);
        JDialog dialog = new JDialog();
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.add(new JLabel(new ImageIcon(image)));
        dialog.pack();
        dialog.setVisible(true);
    }
}
