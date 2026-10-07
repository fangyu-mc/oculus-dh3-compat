package net.coderbot.iris.mixin.compat.sndctrl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sound Control 4.0.5.0 calls exec() on the sound executor for a new source. The
 * client then joins that same executor to obtain the next channel, so the initial
 * environment ray traces can stall a frame. Existing sounds already calculate on
 * Sound Control's worker pool and apply their completed filters on the sound thread.
 *
 * Use that existing lifecycle for the first calculation too. No extra queue, future,
 * worker, world reference or OpenAL call is introduced. Until the worker completes,
 * the original neutral filter data is used; initial effects may therefore be late.
 */
@Pseudo
@Mixin(targets = "org.orecruncher.sndctrl.audio.handlers.SourceContext", remap = false)
public abstract class MixinDeferredInitialSoundEffects {
    // Written before Sound Control registers the context in its active source array.
    // Read/cleared only by its single background scheduling thread.
    @Unique
    private volatile boolean iris$initialSoundEffectsPending;

    @Inject(method = "exec()V", at = @At("HEAD"), cancellable = true, require = 1)
    private void iris$deferInitialSoundEffects(CallbackInfo ci) {
        iris$initialSoundEffectsPending = true;
        ci.cancel();
    }

    @Inject(method = "shouldExecute()Z", at = @At("RETURN"), cancellable = true, require = 1)
    private void iris$runInitialEffectsOnNextPass(CallbackInfoReturnable<Boolean> cir) {
        // Run the original scheduling code even on the first pass. Its randomized
        // periodic counter/frequency is preserved for every later pass.
        if (iris$initialSoundEffectsPending) {
            iris$initialSoundEffectsPending = false;
            cir.setReturnValue(true);
        }
    }
}
