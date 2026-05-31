package net.echo.hypermixins.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written recursive-descent parser for the v1 {@code @Expression} DSL. Accepts either a
 * bare identifier (field reference) or {@code IDENT(args)} where each argument is the literal
 * {@code ?} wildcard. Whitespace is permitted between tokens; anything else throws
 * {@link IllegalArgumentException} with the source and the position of the offending character.
 */
final class ExpressionParser {

    private final String src;
    private int pos;

    private ExpressionParser(String src) {
        this.src = src;
        this.pos = 0;
    }

    static ExpressionNode parse(String src) {
        ExpressionParser p = new ExpressionParser(src);
        ExpressionNode node = p.expression();
        p.skipWhitespace();
        if (p.pos != src.length()) {
            throw new IllegalArgumentException(
                "Unexpected trailing input at offset " + p.pos + " in expression: " + src);
        }
        return node;
    }

    private ExpressionNode expression() {
        skipWhitespace();
        String ident = readIdent();
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == '(') {
            return parseCall(ident);
        }
        return new ExpressionNode.FieldRef(ident);
    }

    private ExpressionNode parseCall(String defId) {
        expect('(');
        List<ExpressionNode.Arg> args = new ArrayList<>();
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == ')') {
            pos++;
            return new ExpressionNode.Call(defId, args);
        }
        args.add(parseArg());
        skipWhitespace();
        while (pos < src.length() && src.charAt(pos) == ',') {
            pos++;
            args.add(parseArg());
            skipWhitespace();
        }
        expect(')');
        return new ExpressionNode.Call(defId, args);
    }

    private ExpressionNode.Arg parseArg() {
        skipWhitespace();
        if (pos >= src.length()) {
            throw new IllegalArgumentException(
                "Expected argument at offset " + pos + " in expression: " + src);
        }
        char c = src.charAt(pos);
        if (c == '?') {
            pos++;
            return ExpressionNode.Wildcard.INSTANCE;
        }
        throw new IllegalArgumentException(
            "Unsupported argument at offset " + pos + " in expression: " + src
            + " — v1 only accepts '?' placeholders");
    }

    private String readIdent() {
        int start = pos;
        if (pos >= src.length() || !isIdentStart(src.charAt(pos))) {
            throw new IllegalArgumentException(
                "Expected identifier at offset " + pos + " in expression: " + src);
        }
        pos++;
        while (pos < src.length() && isIdentPart(src.charAt(pos))) pos++;
        return src.substring(start, pos);
    }

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw new IllegalArgumentException(
                "Expected '" + c + "' at offset " + pos + " in expression: " + src);
        }
        pos++;
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private static boolean isIdentStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    private static boolean isIdentPart(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }
}
