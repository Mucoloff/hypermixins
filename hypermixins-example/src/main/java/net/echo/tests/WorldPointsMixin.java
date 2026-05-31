package net.echo.tests;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Inject;
import net.echo.hypermixins.annotations.Mixin;

/**
 * KSP regression fixture: one {@code @Inject} per non-HEAD point + each {@code @At.Shift},
 * so the generated {@code $$Descriptor} round-trips every {@code @At} column through real
 * codegen. Guards the bug fixed in {@code a096a55} where {@code Collectors.inject} read
 * {@code @At} from a non-existent top-level annotation and silently defaulted every point to
 * {@code HEAD} and every shift to {@code BEFORE}.
 *
 * <p>Targets a method ({@code tick}) that does not exist on {@code World}, so the agent never
 * applies these injects at runtime — {@link WorldMixinDescriptorTest} asserts the descriptor
 * columns, not a transform. (Were this aimed at a real method lacking a CONSTANT / NEW site,
 * the agent's classpath auto-registration would abort with "found no matching site".)
 * Transform-level coverage for these points lives in {@code hypermixins-runtime}'s
 * reflection-fallback tests.
 */
@Mixin("net.echo.testworld.World")
public class WorldPointsMixin {

    @Inject(method = "tick", at = @At(point = At.Point.INVOKE,
        desc = "java/util/List.add(Ljava/lang/Object;)Z"))
    public void onInvoke(Object self) {}

    @Inject(method = "tick", at = @At(point = At.Point.FIELD,
        desc = "net/echo/testworld/World.players:Ljava/util/List;"))
    public void onField(Object self) {}

    @Inject(method = "tick", at = @At(point = At.Point.CONSTANT, desc = "I:0"))
    public void onConstant(Object self) {}

    @Inject(method = "tick", at = @At(point = At.Point.NEW, desc = "java/util/ArrayList"))
    public void onNew(Object self) {}

    @Inject(method = "tick", at = @At(point = At.Point.INVOKE,
        desc = "java/util/List.add(Ljava/lang/Object;)Z", shift = At.Shift.AFTER))
    public void onShiftAfter(Object self) {}

    @Inject(method = "tick", at = @At(point = At.Point.INVOKE,
        desc = "java/util/List.add(Ljava/lang/Object;)Z", shift = At.Shift.BY, by = 2))
    public void onShiftBy(Object self) {}
}
