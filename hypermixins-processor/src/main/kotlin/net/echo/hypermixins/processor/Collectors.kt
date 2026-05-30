package net.echo.hypermixins.processor

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import javax.lang.model.element.Modifier
import net.echo.hypermixins.processor.JvmDescriptors.buildTargetDescriptor
import net.echo.hypermixins.processor.JvmDescriptors.descriptor

internal const val MIXIN_FQN       = "net.echo.hypermixins.annotations.Mixin"
internal const val OVERWRITE_FQN   = "net.echo.hypermixins.annotations.Overwrite"
internal const val ORIGINAL_FQN    = "net.echo.hypermixins.annotations.Original"
internal const val REDIRECT_FQN    = "net.echo.hypermixins.annotations.Redirect"
internal const val INJECT_FQN      = "net.echo.hypermixins.annotations.Inject"
internal const val CANCELLABLE_FQN = "net.echo.hypermixins.annotations.Cancellable"
internal const val SHADOW_FQN      = "net.echo.hypermixins.annotations.Shadow"
internal const val MODIFY_RV_FQN   = "net.echo.hypermixins.annotations.ModifyReturnValue"
internal const val ACCESSOR_FQN    = "net.echo.hypermixins.annotations.Accessor"
internal const val INVOKER_FQN     = "net.echo.hypermixins.annotations.Invoker"
internal const val MODIFY_CONST_FQN = "net.echo.hypermixins.annotations.ModifyConstant"
internal const val MODIFY_ARG_FQN   = "net.echo.hypermixins.annotations.ModifyArg"
internal const val MODIFY_EXPR_FQN  = "net.echo.hypermixins.annotations.ModifyExpressionValue"
internal const val MODIFY_ARGS_FQN  = "net.echo.hypermixins.annotations.ModifyArgs"
internal const val MODIFY_RECV_FQN  = "net.echo.hypermixins.annotations.ModifyReceiver"
internal const val AT_FQN           = "net.echo.hypermixins.annotations.At"
internal const val LOCAL_FQN        = "net.echo.hypermixins.annotations.Local"

/**
 * Per-annotation validators. One method per annotation, same shape: validate the handler
 * function shape, emit a [KSPLogger] error on misuse, append a record to {@code out} on
 * success. Held together as a single class because every method needs the same [KSPLogger]
 * and shares the same error-message style — splitting into 14 files would multiply boilerplate
 * without buying clarity.
 */
internal class Collectors(private val logger: KSPLogger) {

    fun overwrite(fn: KSFunctionDeclaration, targetClass: String, out: MutableList<OverwriteEntry>) {
        if (Modifier.STATIC in fn.javaModifiers()) {
            logger.error("@Overwrite method must not be static: ${fn.simpleName.asString()}", fn)
            return
        }
        val ann = fn.findAnnotation(OVERWRITE_FQN)!!
        val targetName = ann.arg("value") as? String ?: run {
            logger.error("@Overwrite#value() must not be empty on ${fn.simpleName.asString()}", fn)
            return
        }
        if (targetName.isBlank()) {
            logger.error("@Overwrite#value() must not be empty on ${fn.simpleName.asString()}", fn)
            return
        }
        val params = fn.parameters
        if (params.isEmpty()) {
            logger.error("@Overwrite method must have 'Object self' as first parameter: ${fn.simpleName.asString()}", fn)
            return
        }
        val firstType = params[0].type.resolve().declaration.qualifiedName?.asString()
        if (firstType != "kotlin.Any" && firstType != "java.lang.Object") {
            logger.error("@Overwrite first parameter must be Object/Any (found $firstType): ${fn.simpleName.asString()}", fn)
            return
        }
        params.forEach { p ->
            if (p.type.resolve().declaration.qualifiedName?.asString() == targetClass) {
                logger.error("@Overwrite parameters must not reference target class $targetClass directly; use Object self", fn)
                return
            }
        }
        val targetDesc = buildTargetDescriptor(fn)
        out += OverwriteEntry(targetName, targetDesc, fn.simpleName.asString(), descriptor(fn))
    }

    fun original(fn: KSFunctionDeclaration, out: MutableList<OriginalEntry>) {
        val ann = fn.findAnnotation(ORIGINAL_FQN)!!
        val targetName = ann.arg("value") as? String ?: ""
        if (targetName.isBlank()) {
            logger.error("@Original#value() must not be empty on ${fn.simpleName.asString()}", fn)
            return
        }
        if (fn.parameters.isEmpty()) {
            logger.error("@Original method must have at least 'Object self' as first parameter: ${fn.simpleName.asString()}", fn)
            return
        }
        out += OriginalEntry(fn.simpleName.asString(), descriptor(fn), targetName)
    }

    fun redirect(fn: KSFunctionDeclaration, out: MutableList<RedirectEntry>) {
        if (Modifier.STATIC !in fn.javaModifiers()) {
            logger.error("@Redirect method must be static: ${fn.simpleName.asString()}", fn)
            return
        }
        val ann = fn.findAnnotation(REDIRECT_FQN)!!
        val method = ann.arg("method") as? String ?: ""
        if (method.isBlank()) {
            logger.error("@Redirect#method() must not be empty on ${fn.simpleName.asString()}", fn)
            return
        }
        val atAnn = ann.arg("at") as? KSAnnotation
        val desc  = atAnn?.arg("desc") as? String ?: ""
        if (desc.isBlank()) {
            logger.error("@At#desc() must not be empty on @Redirect ${fn.simpleName.asString()}", fn)
            return
        }
        val index = (atAnn?.arg("index") as? Int) ?: 0
        val call  = readEnumArg(atAnn?.arg("call"), "INVOKEVIRTUAL")
        val handlerDesc = descriptor(fn)
        val isField = call == "GETFIELD" || call == "PUTFIELD" || call == "GETSTATIC" || call == "PUTSTATIC"
        if (isField) {
            val colon = desc.indexOf(':')
            if (colon < 0) {
                logger.error("@At#desc() for field redirect must be \"owner/Class.field:Ldesc;\" on ${fn.simpleName.asString()}", fn)
                return
            }
            val fieldDesc = desc.substring(colon + 1)
            val expected = when (call) {
                "GETFIELD"  -> "(Ljava/lang/Object;)$fieldDesc"
                "PUTFIELD"  -> "(Ljava/lang/Object;$fieldDesc)V"
                "GETSTATIC" -> "()$fieldDesc"
                else        -> "($fieldDesc)V"
            }
            if (handlerDesc != expected) {
                logger.error(
                    "@Redirect field handler signature mismatch on ${fn.simpleName.asString()}: expected $expected found $handlerDesc", fn
                )
            }
        } else {
            val parenIdx = desc.indexOf('(')
            if (parenIdx < 0) {
                logger.error("@At#desc() missing '(' in @Redirect on ${fn.simpleName.asString()}", fn)
                return
            }
            val invokeSignature = desc.substring(parenIdx)
            if (handlerDesc != invokeSignature) {
                logger.error(
                    "@Redirect handler signature mismatch on ${fn.simpleName.asString()}: expected $invokeSignature found $handlerDesc", fn
                )
            }
        }
        out += RedirectEntry(method, desc, index, call, fn.simpleName.asString(), handlerDesc)
    }

    fun modifyReceiver(fn: KSFunctionDeclaration, out: MutableList<ModifyReceiverEntry>) {
        val ann = fn.findAnnotation(MODIFY_RECV_FQN)!!
        val method = (ann.arg("method") as? String).orEmpty()
        if (method.isBlank()) {
            logger.error("@ModifyReceiver#method() empty on ${fn.simpleName.asString()}", fn)
            return
        }
        if (com.google.devtools.ksp.symbol.Modifier.JAVA_STATIC !in fn.modifiers) {
            logger.error("@ModifyReceiver must be static: ${fn.simpleName.asString()}", fn)
            return
        }
        val atAnn = ann.arg("at") as? KSAnnotation
        val desc = (atAnn?.arg("desc") as? String).orEmpty()
        if (desc.isBlank()) {
            logger.error("@At#desc() empty on @ModifyReceiver ${fn.simpleName.asString()}", fn)
            return
        }
        out += ModifyReceiverEntry(method, desc, fn.simpleName.asString(), descriptor(fn))
    }

    fun modifyArgs(fn: KSFunctionDeclaration, out: MutableList<ModifyArgsEntry>) {
        val ann = fn.findAnnotation(MODIFY_ARGS_FQN)!!
        val method = (ann.arg("method") as? String).orEmpty()
        if (method.isBlank()) {
            logger.error("@ModifyArgs#method() empty on ${fn.simpleName.asString()}", fn)
            return
        }
        if (com.google.devtools.ksp.symbol.Modifier.JAVA_STATIC !in fn.modifiers) {
            logger.error("@ModifyArgs must be static: ${fn.simpleName.asString()}", fn)
            return
        }
        val atAnn = ann.arg("at") as? KSAnnotation
        val desc = (atAnn?.arg("desc") as? String).orEmpty()
        if (desc.isBlank()) {
            logger.error("@At#desc() empty on @ModifyArgs ${fn.simpleName.asString()}", fn)
            return
        }
        val handlerName = fn.simpleName.asString()
        val handlerDesc = descriptor(fn)
        if (handlerDesc != "([Ljava/lang/Object;)V") {
            logger.error("@ModifyArgs handler must be (Object[]): void on $handlerName (got $handlerDesc)", fn)
            return
        }
        out += ModifyArgsEntry(method, desc, handlerName, handlerDesc)
    }

    fun modifyExpr(fn: KSFunctionDeclaration, out: MutableList<ModifyExpressionValueEntry>) {
        val ann = fn.findAnnotation(MODIFY_EXPR_FQN)!!
        val method = (ann.arg("method") as? String).orEmpty()
        if (method.isBlank()) {
            logger.error("@ModifyExpressionValue#method() empty on ${fn.simpleName.asString()}", fn)
            return
        }
        if (com.google.devtools.ksp.symbol.Modifier.JAVA_STATIC !in fn.modifiers) {
            logger.error("@ModifyExpressionValue must be static: ${fn.simpleName.asString()}", fn)
            return
        }
        val atAnn = ann.arg("at") as? KSAnnotation
        val point = readEnumArg(atAnn?.arg("point"), "INVOKE")
        val desc = (atAnn?.arg("desc") as? String).orEmpty()
        if (desc.isBlank()) {
            logger.error("@At#desc() empty on @ModifyExpressionValue ${fn.simpleName.asString()}", fn)
            return
        }
        val idx = (atAnn?.arg("index") as? Int) ?: 0
        out += ModifyExpressionValueEntry(method, point, desc, idx, fn.simpleName.asString(), descriptor(fn))
    }

    fun modifyArg(fn: KSFunctionDeclaration, out: MutableList<ModifyArgEntry>) {
        val ann = fn.findAnnotation(MODIFY_ARG_FQN)!!
        val method = (ann.arg("method") as? String).orEmpty()
        if (method.isBlank()) {
            logger.error("@ModifyArg#method() must not be empty on ${fn.simpleName.asString()}", fn)
            return
        }
        if (com.google.devtools.ksp.symbol.Modifier.JAVA_STATIC !in fn.modifiers) {
            logger.error("@ModifyArg method must be static: ${fn.simpleName.asString()}", fn)
            return
        }
        val atAnn = ann.arg("at") as? KSAnnotation
        val desc = (atAnn?.arg("desc") as? String).orEmpty()
        if (desc.isBlank()) {
            logger.error("@At#desc() must not be empty on @ModifyArg ${fn.simpleName.asString()}", fn)
            return
        }
        val idx = (ann.arg("index") as? Int) ?: 0
        out += ModifyArgEntry(method, desc, idx, fn.simpleName.asString(), descriptor(fn))
    }

    fun modifyConstant(fn: KSFunctionDeclaration, out: MutableList<ModifyConstantEntry>) {
        val ann = fn.findAnnotation(MODIFY_CONST_FQN)!!
        val method = (ann.arg("method") as? String).orEmpty()
        if (method.isBlank()) {
            logger.error("@ModifyConstant#method() must not be empty on ${fn.simpleName.asString()}", fn)
            return
        }
        if (com.google.devtools.ksp.symbol.Modifier.JAVA_STATIC !in fn.modifiers) {
            logger.error("@ModifyConstant method must be static: ${fn.simpleName.asString()}", fn)
            return
        }
        val constAnn = ann.arg("constant") as? KSAnnotation
        val intValue = (constAnn?.arg("intValue") as? Int) ?: Int.MIN_VALUE
        val longValue = (constAnn?.arg("longValue") as? Long) ?: Long.MIN_VALUE
        val floatValue = (constAnn?.arg("floatValue") as? Float) ?: Float.NaN
        val doubleValue = (constAnn?.arg("doubleValue") as? Double) ?: Double.NaN
        val stringValue = (constAnn?.arg("stringValue") as? String).orEmpty()
        val type: String
        val value: String
        when {
            intValue != Int.MIN_VALUE -> { type = "I"; value = intValue.toString() }
            longValue != Long.MIN_VALUE -> { type = "J"; value = longValue.toString() }
            !floatValue.isNaN() -> { type = "F"; value = floatValue.toString() }
            !doubleValue.isNaN() -> { type = "D"; value = doubleValue.toString() }
            stringValue.isNotEmpty() -> { type = "Ljava/lang/String;"; value = stringValue }
            else -> {
                logger.error("@Constant must specify exactly one value on ${fn.simpleName.asString()}", fn)
                return
            }
        }
        val index = (ann.arg("index") as? Int) ?: 0
        out += ModifyConstantEntry(method, type, value, index, fn.simpleName.asString(), descriptor(fn))
    }

    fun invoker(fn: KSFunctionDeclaration, out: MutableList<InvokerEntry>) {
        val ann = fn.findAnnotation(INVOKER_FQN)!!
        val explicit = (ann.arg("value") as? String).orEmpty()
        val handlerName = fn.simpleName.asString()
        val handlerDesc = descriptor(fn)
        val params = fn.parameters
        if (params.isEmpty()) {
            logger.error("@Invoker method must take Object self as first parameter: $handlerName", fn)
            return
        }
        val firstFqn = params[0].type.resolve().declaration.qualifiedName?.asString()
        if (firstFqn != "kotlin.Any" && firstFqn != "java.lang.Object") {
            logger.error("@Invoker first parameter must be Object/Any: $handlerName", fn)
            return
        }
        val targetName = if (explicit.isNotBlank()) explicit else deriveInvokerName(handlerName)
        if (targetName.isBlank()) {
            logger.error("@Invoker cannot derive target method from $handlerName — set value()", fn)
            return
        }
        out += InvokerEntry(handlerName, handlerDesc, targetName)
    }

    fun accessor(fn: KSFunctionDeclaration, out: MutableList<AccessorEntry>) {
        val ann = fn.findAnnotation(ACCESSOR_FQN)!!
        val explicit = (ann.arg("value") as? String).orEmpty()
        val handlerName = fn.simpleName.asString()
        val handlerDesc = descriptor(fn)
        val args = fn.parameters
        if (args.isEmpty()) {
            logger.error("@Accessor method must take Object self as first parameter: $handlerName", fn)
            return
        }
        val firstFqn = args[0].type.resolve().declaration.qualifiedName?.asString()
        if (firstFqn != "kotlin.Any" && firstFqn != "java.lang.Object") {
            logger.error("@Accessor first parameter must be Object/Any: $handlerName", fn)
            return
        }
        val returnsVoid = (fn.returnType?.resolve()?.declaration?.qualifiedName?.asString() ?: "") in listOf("kotlin.Unit", "java.lang.Void", "void")
        val isSetter = returnsVoid && args.size == 2
        val isGetter = !returnsVoid && args.size == 1
        if (!isGetter && !isSetter) {
            logger.error("@Accessor must be (Object): T (getter) or (Object, T): void (setter): $handlerName", fn)
            return
        }
        val targetField = if (explicit.isNotBlank()) explicit else deriveAccessorField(handlerName)
        if (targetField.isBlank()) {
            logger.error("@Accessor cannot derive target field name from $handlerName — set value()", fn)
            return
        }
        out += AccessorEntry(handlerName, handlerDesc, if (isGetter) "GET" else "SET", targetField)
    }

    fun modifyReturnValue(fn: KSFunctionDeclaration, out: MutableList<ModifyReturnValueEntry>) {
        val ann = fn.findAnnotation(MODIFY_RV_FQN)!!
        val method = ann.arg("method") as? String ?: ""
        if (method.isBlank()) {
            logger.error("@ModifyReturnValue#method() must not be empty on ${fn.simpleName.asString()}", fn)
            return
        }
        if (com.google.devtools.ksp.symbol.Modifier.JAVA_STATIC !in fn.modifiers) {
            logger.error("@ModifyReturnValue method must be static: ${fn.simpleName.asString()}", fn)
            return
        }
        val atAnn = ann.arg("at") as? KSAnnotation
        val desc = atAnn?.arg("desc") as? String ?: ""
        if (desc.isBlank()) {
            logger.error("@At#desc() must not be empty on @ModifyReturnValue ${fn.simpleName.asString()}", fn)
            return
        }
        val parenIdx = desc.indexOf('(')
        if (parenIdx < 0) {
            logger.error("@At#desc() must be the invoke form on @ModifyReturnValue ${fn.simpleName.asString()}", fn)
            return
        }
        val invokeSig = desc.substring(parenIdx)
        val handlerDesc = descriptor(fn)
        val invokeReturn = invokeSig.substring(invokeSig.indexOf(')') + 1)
        val expected = "($invokeReturn)$invokeReturn"
        if (handlerDesc != expected) {
            logger.error("@ModifyReturnValue handler signature must match (${invokeReturn})${invokeReturn} (got $handlerDesc): ${fn.simpleName.asString()}", fn)
            return
        }
        val index = (atAnn?.arg("index") as? Int) ?: 0
        out += ModifyReturnValueEntry(method, desc, index, fn.simpleName.asString(), handlerDesc)
    }

    fun shadow(fn: KSFunctionDeclaration, out: MutableList<ShadowEntry>) {
        val ann = fn.findAnnotation(SHADOW_FQN)!!
        val explicit = ann.arg("value") as? String ?: ""
        val prefix = (ann.arg("prefix") as? String).orEmpty()
        val simple = fn.simpleName.asString()
        val targetName = when {
            explicit.isNotBlank() -> explicit
            prefix.isNotEmpty() && simple.startsWith(prefix) -> simple.removePrefix(prefix)
            else -> simple
        }
        val params = fn.parameters
        if (params.isEmpty()) {
            logger.error("@Shadow method must have 'Object self' as first parameter: ${fn.simpleName.asString()}", fn)
            return
        }
        val firstType = params[0].type.resolve().declaration.qualifiedName?.asString()
        if (firstType != "kotlin.Any" && firstType != "java.lang.Object") {
            logger.error("@Shadow first parameter must be Object/Any (found $firstType): ${fn.simpleName.asString()}", fn)
            return
        }
        out += ShadowEntry(fn.simpleName.asString(), descriptor(fn), targetName)
    }

    fun inject(
        fn: KSFunctionDeclaration,
        out: MutableList<InjectEntry>,
        localsOut: MutableList<InjectLocalEntry>
    ) {
        val ann = fn.findAnnotation(INJECT_FQN)!!
        val method = ann.arg("method") as? String ?: ""
        if (method.isBlank()) {
            logger.error("@Inject#method() must not be empty on ${fn.simpleName.asString()}", fn)
            return
        }
        if (Modifier.STATIC in fn.javaModifiers()) {
            logger.error("@Inject method must not be static: ${fn.simpleName.asString()}", fn)
            return
        }
        val firstParam = fn.parameters.firstOrNull()
        if (firstParam == null) {
            logger.error("@Inject method must declare 'Object self' as first parameter: ${fn.simpleName.asString()}", fn)
            return
        }
        val firstFqn = firstParam.type.resolve().declaration.qualifiedName?.asString()
        if (firstFqn != "kotlin.Any" && firstFqn != "java.lang.Object") {
            logger.error("@Inject first parameter must be Object/Any (found $firstFqn): ${fn.simpleName.asString()}", fn)
            return
        }
        val cancellable = (ann.arg("cancellable") as? Boolean) == true
                || fn.hasAnnotation(CANCELLABLE_FQN)
        var returnable = false
        if (cancellable) {
            val lastParam = fn.parameters.lastOrNull()
            val lastFqn = lastParam?.type?.resolve()?.declaration?.qualifiedName?.asString() ?: ""
            if (!lastFqn.endsWith("CallbackInfo") && !lastFqn.endsWith("CallbackInfoReturnable")) {
                logger.error("@Inject with cancellable=true requires CallbackInfo or CallbackInfoReturnable as last parameter: ${fn.simpleName.asString()}", fn)
            }
            returnable = lastFqn.endsWith("CallbackInfoReturnable")
        }
        val atAnn = fn.findAnnotation(AT_FQN)
        val point = readEnumArg(atAnn?.arg("point"), "HEAD")
        val atDesc = (atAnn?.arg("desc") as? String) ?: ""
        val atIndex = (atAnn?.arg("index") as? Int) ?: 0
        val shift = readEnumArg(atAnn?.arg("shift"), "BEFORE")
        if ((point == "INVOKE" || point == "FIELD" || point == "CONSTANT" || point == "NEW") && atDesc.isBlank()) {
            logger.error("@Inject point $point requires @At#desc() on ${fn.simpleName.asString()}", fn)
            return
        }
        val handlerName = fn.simpleName.asString()
        val handlerDesc = descriptor(fn)
        out += InjectEntry(method, point, atDesc, atIndex, cancellable, returnable, handlerName, handlerDesc, shift)
        fn.parameters.forEachIndexed { i, p ->
            val localAnn = p.annotations.firstOrNull {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == LOCAL_FQN
            }
            if (localAnn != null) {
                val slot = (localAnn.arguments.firstOrNull { it.name?.asString() == "index" }?.value as? Int) ?: -1
                val ord = (localAnn.arguments.firstOrNull { it.name?.asString() == "ordinal" }?.value as? Int) ?: -1
                val argsOnly = (localAnn.arguments.firstOrNull { it.name?.asString() == "argsOnly" }?.value as? Boolean) == true
                localsOut += InjectLocalEntry(handlerName, handlerDesc, i, slot, ord, argsOnly)
            }
        }
    }

    private fun deriveInvokerName(method: String): String {
        for (prefix in listOf("invoke", "call")) {
            if (method.startsWith(prefix) && method.length > prefix.length
                && method[prefix.length].isUpperCase()
            ) {
                val tail = method.substring(prefix.length)
                return tail[0].lowercase() + tail.substring(1)
            }
        }
        return method
    }

    private fun deriveAccessorField(method: String): String {
        for (prefix in listOf("get", "set", "is")) {
            if (method.startsWith(prefix) && method.length > prefix.length
                && method[prefix.length].isUpperCase()
            ) {
                val tail = method.substring(prefix.length)
                return tail[0].lowercase() + tail.substring(1)
            }
        }
        return method
    }
}
