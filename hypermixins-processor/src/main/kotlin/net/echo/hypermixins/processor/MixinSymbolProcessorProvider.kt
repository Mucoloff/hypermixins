package net.echo.hypermixins.processor

import com.google.devtools.ksp.processing.*

class MixinSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        MixinSymbolProcessor(environment.codeGenerator, environment.logger)
}
