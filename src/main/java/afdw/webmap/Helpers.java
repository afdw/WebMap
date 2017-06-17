package afdw.webmap;

import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.Comparator;
import java.util.stream.Collectors;

public class Helpers {
    @SuppressWarnings("unchecked")
    public static <T extends Comparable<T>> String getPropertyName(IProperty<T> property, Comparable<?> entry) {
        return property.getName((T) entry);
    }

    public static String blockStateToString(IBlockState state) {
        ResourceLocation blockName = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (blockName == null) {
            throw new IllegalArgumentException();
        }
        return blockName.toString() + (state.getProperties().isEmpty() ? "" : state.getProperties().entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().getName()))
            .map(entry -> entry.getKey().getName() + "=" + getPropertyName(entry.getKey(), entry.getValue()))
            .collect(
                Collectors.joining(
                    ",",
                    "[",
                    "]"
                )
            ));
    }
}
