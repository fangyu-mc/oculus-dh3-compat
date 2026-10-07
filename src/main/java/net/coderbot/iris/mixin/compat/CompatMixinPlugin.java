package net.coderbot.iris.mixin.compat;

import net.coderbot.iris.compat.embeddium.BiomeColorCallbackOptimizer;
import net.coderbot.iris.compat.illuminations.FireflyQuadOptimizer;
import net.coderbot.iris.compat.tes.TesEntityTypeCacheOptimizer;
import net.coderbot.iris.diagnostics.FrameTimeDetailInstrumentation;
import net.coderbot.iris.math.ScalarMatrixScaleOptimizer;
import net.coderbot.iris.math.SkyRgbSamplerOptimizer;
import net.minecraftforge.fml.loading.FMLLoader;
import org.apache.logging.log4j.LogManager;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class CompatMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {

    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        int startIndex = mixinClassName.indexOf("compat.") + "compat.".length();
        int endIndex = mixinClassName.indexOf(".", startIndex);
        String modid = mixinClassName.substring(startIndex, endIndex);
		if (modid.equals("minecraft")) { return true; }
        return FMLLoader.getLoadingModList().getModFileById(modid) != null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {

    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (mixinClassName.equals("net.coderbot.iris.mixin.compat.minecraft.MixinSkyRgbSampler")) {
            int changed = SkyRgbSamplerOptimizer.optimize(targetClass);
            LogManager.getLogger("Oculus").info("Sky RGB sampling: optimized {} packed-color fetchers (unknown implementations retained)", changed);
        }
        if (mixinClassName.equals("net.coderbot.iris.mixin.compat.minecraft.MixinMatrix4fScale")
                || mixinClassName.equals("net.coderbot.iris.mixin.compat.minecraft.MixinMatrix3fScale")
                || mixinClassName.equals("net.coderbot.iris.mixin.compat.minecraft.MixinPoseStackScale")) {
            int changed = ScalarMatrixScaleOptimizer.optimize(targetClass);
            LogManager.getLogger("Oculus").info("Scalar matrix scale: {} optimized {} operations (unrecognized implementations retain vanilla fallback)", targetClassName, changed);
        }
        if (mixinClassName.equals("net.coderbot.iris.mixin.compat.tslatentitystatus.MixinTesEntityTypeCache")) {
            int changed = TesEntityTypeCacheOptimizer.optimize(targetClass);
            if (changed == 1) {
                LogManager.getLogger("Oculus").info("TES entity-type cache: removed capturing lambda, original cache and classifier retained");
            } else {
                LogManager.getLogger("Oculus").warn("TES entity-type cache: unknown lookup method; retaining original code");
            }
        }
        if (mixinClassName.equals("net.coderbot.iris.mixin.compat.xaeroworldmap.MixinFrameTimeDetails")
				|| mixinClassName.equals("net.coderbot.iris.mixin.compat.minecraft.MixinFrameTimeDetails")
				|| mixinClassName.equals("net.coderbot.iris.mixin.compat.environs.MixinFrameTimeDetails")) {
            FrameTimeDetailInstrumentation.apply(targetClass);
        }
        if (mixinClassName.equals("net.coderbot.iris.mixin.compat.embeddiumextension.MixinBiomeColors")) {
            int changed = BiomeColorCallbackOptimizer.optimize(targetClass);
            LogManager.getLogger("Oculus").info("Embeddium Extension color callbacks: optimized {} return sites", changed);
        }
        if (mixinClassName.equals("net.coderbot.iris.mixin.compat.illuminations.MixinFireflyParticle")) {
            int changed = FireflyQuadOptimizer.optimize(targetClass);
            if (changed == 1) {
                LogManager.getLogger("Oculus").info("Illuminations firefly quad: scalarized render temporaries");
            } else {
                LogManager.getLogger("Oculus").warn("Illuminations firefly quad: unknown render method; retaining original code");
            }
        }
    }
}
