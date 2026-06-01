package net.echo.hypermixins.agent;

/**
 * Public syntax-validation facade for the {@code @Expression} DSL. Lives in the same package as
 * the package-private {@link ExpressionParser} so tooling (the IntelliJ plugin) can surface parse
 * errors at edit time without reaching into the parser internals.
 *
 * <p>Syntax only: this validates that an expression string parses. Semantic checks
 * (definition-id resolution, exactly-one-of {@code method}/{@code field}/{@code type}) need the
 * handler {@code Method} + descriptor and run in {@link ExpressionMatcher#compile} at transform
 * time, not here.
 */
public final class ExpressionValidator {

    private ExpressionValidator() {}

    /**
     * Parses {@code expression}; returns {@code null} when it is syntactically valid, or the
     * parser's error message (with offset + source) when it is not.
     */
    public static String parseError(String expression) {
        if (expression == null) return "expression is null";
        try {
            ExpressionParser.parse(expression);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** Convenience inverse of {@link #parseError}. */
    public static boolean isValid(String expression) {
        return parseError(expression) == null;
    }
}
