package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.Call;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;

public record RedirectMapping(String targetMethod, String invokeDesc, int index, Call call, Method handler) {

    /** Pre-computed handler descriptor — avoids recomputing per instruction. */
    public String handlerDesc() {
        return Type.getMethodDescriptor(handler);
    }
}
