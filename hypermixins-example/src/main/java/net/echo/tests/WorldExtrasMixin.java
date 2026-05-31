package net.echo.tests;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Definition;
import net.echo.hypermixins.annotations.Expression;
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
 *   <li>{@code @Expression} DSL — a call-shape selector capturing the added player, and a
 *       field-shape selector on {@code players}. Exercises the @Expression → descriptor
 *       (schema v3) → matcher pipeline end-to-end in the example module.</li>
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

    /**
     * Call-shape @Expression: matches the {@code players.add(player)} INVOKEINTERFACE inside
     * addPlayer and captures the pushed argument into {@code added}.
     */
    @Definition(id = "listAdd", method = "java/util/List.add(Ljava/lang/Object;)Z")
    @Expression("listAdd(?)")
    @Inject(method = "addPlayer", at = @At(point = At.Point.EXPRESSION))
    public void onListAdd(Object self, Object added) {
        System.out.println("[expression] List.add captured " + added);
    }

    /**
     * Field-shape @Expression: matches the {@code GETFIELD World.players} access in addPlayer.
     */
    @Definition(id = "playersField", field = "net/echo/testworld/World.players:Ljava/util/List;")
    @Expression("playersField")
    @Inject(method = "addPlayer", at = @At(point = At.Point.EXPRESSION))
    public void onPlayersAccess(Object self) {
        System.out.println("[expression] players field accessed");
    }
}
