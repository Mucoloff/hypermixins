package net.echo.hypermixins.processor

/**
 * Plain data carriers produced by per-annotation collectors and consumed by the descriptor
 * emitter. Kept at file level so future collector / probe / emitter splits can share them
 * without piercing [MixinSymbolProcessor]'s private nest.
 */

internal data class OverwriteEntry(val targetName: String, val targetDesc: String, val handlerName: String, val handlerDesc: String)
internal data class OriginalEntry(val handlerName: String, val handlerDesc: String, val targetName: String)
internal data class RedirectEntry(val targetMethod: String, val invokeDesc: String, val index: Int, val call: String, val handlerName: String, val handlerDesc: String)
internal data class InjectEntry(val targetMethod: String, val point: String, val atDesc: String, val atIndex: Int, val cancellable: Boolean, val returnable: Boolean, val handlerName: String, val handlerDesc: String, val shift: String)
internal data class ShadowEntry(val handlerName: String, val handlerDesc: String, val targetName: String)
internal data class ShadowFieldEntry(val mixinFieldName: String, val fieldDesc: String, val targetFieldName: String)
internal data class InjectLocalEntry(val handlerName: String, val handlerDesc: String, val paramIndex: Int, val slot: Int, val ordinal: Int, val argsOnly: Boolean)
internal data class ModifyReturnValueEntry(val targetMethod: String, val invokeDesc: String, val index: Int, val handlerName: String, val handlerDesc: String)
internal data class AccessorEntry(val handlerName: String, val handlerDesc: String, val kind: String, val targetField: String)
internal data class InvokerEntry(val handlerName: String, val handlerDesc: String, val targetName: String)
internal data class ModifyConstantEntry(val targetMethod: String, val type: String, val value: String, val index: Int, val handlerName: String, val handlerDesc: String)
internal data class ModifyArgEntry(val targetMethod: String, val invokeDesc: String, val argIndex: Int, val handlerName: String, val handlerDesc: String)
internal data class ModifyExpressionValueEntry(val targetMethod: String, val point: String, val atDesc: String, val index: Int, val handlerName: String, val handlerDesc: String)
internal data class ModifyArgsEntry(val targetMethod: String, val invokeDesc: String, val handlerName: String, val handlerDesc: String)
internal data class ModifyReceiverEntry(val targetMethod: String, val invokeDesc: String, val handlerName: String, val handlerDesc: String)
internal data class WrapConditionEntry(val targetMethod: String, val invokeDesc: String, val index: Int, val handlerName: String, val handlerDesc: String)
internal data class WrapOperationEntry(val targetMethod: String, val invokeDesc: String, val index: Int, val handlerName: String, val handlerDesc: String)
