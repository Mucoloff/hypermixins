package net.echo.tests;

import net.echo.hypermixins.annotations.*;
import net.echo.testworld.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

@Mixin("net.echo.testworld.World")
public class WorldMixin {
    
    private final List<Player> injectedPlayers = new ArrayList<>();
    
    @Redirect(
        method = "run",
        at = @At(
            desc = "java/lang/Thread.sleep(J)V",
            index = 1 // takes the second Thread.sleep
                ,call = Call.INVOKESTATIC
        )
    )
    public static void sleepOverridden(long millis) {
        LockSupport.parkNanos((long) (millis * 1e6));
    }
    
    @Original("getPlayers")
    public native List<Player> getPlayersOrig(Object self);
    
    @Overwrite("getPlayers")
    public List<Player> getPlayers(Object self) {
        List<Player> original = getPlayersOrig(self);
        original.add(new Player("shelter"));
        return original;
    }
}
