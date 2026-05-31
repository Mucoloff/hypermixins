package net.echo.tests;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Inject;
import net.echo.hypermixins.annotations.Local;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Ref;
import net.echo.hypermixins.annotations.Share;
import net.echo.hypermixins.annotations.Surrogate;
import net.echo.hypermixins.annotations.Unique;

/**
 * 1.5 showcase mixin: exercises post-1.4 features against the same {@code World} target
 * {@link WorldMixin} already covers. Kept beside the original instead of editing it so the
 * baseline example stays minimal.
 *
 * <ul>
 *   <li>Instance {@code @Unique} helper — merged into the target as a static synthetic.</li>
 *   <li>{@code @Share} cell on an {@code @Inject} param — backing store for cross-handler
 *       state.</li>
 *   <li>{@code @Surrogate} fallback — the primary handler captures a non-existent local int
 *       and fails resolution at transform time; the surrogate's plain signature runs instead.</li>
 *   <li>{@code @Inject(require)} — pins the match count to detect future target refactors.</li>
 * </ul>
 */
@Mixin("net.echo.testworld.World")
public class WorldExtrasMixin {

    /** Instance @Unique helper — merged into the target as a public static synthetic. */
    @Unique
    public int increment(int seen) {
        return seen + 1;
    }

    @Inject(method = "addPlayer", at = @At(point = At.Point.HEAD), require = 1)
    public void onAddShare(Object self, @Share("addCount") Ref<Integer> count) {
        Integer prev = count.get();
        count.set(prev == null ? 1 : prev + 1);
    }

    @Inject(method = "addPlayer", at = @At(point = At.Point.HEAD))
    public void onAddPrimary(Object self, @Local int missingInt) {
        // Intentional mismatch: no int local lives on addPlayer's incoming params.
        // CaptureEmitter throws InjectSignatureMismatch → @Surrogate runs instead.
        System.out.println("[primary] " + missingInt);
    }

    @Surrogate
    @Inject(method = "addPlayer", at = @At(point = At.Point.HEAD))
    public void onAddSurrogate(Object self) {
        System.out.println("[surrogate] addPlayer intercepted");
    }
}
