package net.echo.tests;

import net.echo.hypermixins.HyperMixins;

import java.lang.instrument.Instrumentation;

public class MixinTest {
    
    public static void premain(String args, Instrumentation inst) throws HyperMixins.MixinRegistrationException {
        HyperMixins.register(inst, WorldMixin.class);
    }
}
