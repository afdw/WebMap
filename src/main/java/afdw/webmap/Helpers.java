package afdw.webmap;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.Comparator;
import java.util.stream.Collectors;

public class Helpers {
    public static String blockStateToString(IBlockState state) {
        ResourceLocation blockName = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (blockName == null) {
            throw new IllegalArgumentException();
        }
        return state.getProperties().entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().getName()))
            .map(entry -> entry.getKey().getName() + "=" + entry.getValue().toString())
            .collect(
                Collectors.joining(
                    ",",
                    blockName.toString() + "[",
                    "]"
                )
            );
    }
}
