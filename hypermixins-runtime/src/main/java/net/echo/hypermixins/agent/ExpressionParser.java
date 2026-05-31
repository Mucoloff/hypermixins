package net.echo.hypermixins.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written recursive-descent parser for the {@code @Expression} DSL.
 *
 * <p>v2 grammar:
 * <pre>
 *   expression  := assignment ;
 *   assignment  := selector ("=" arg)? ;
 *   selector    := "this" ("." member)? | member ;
 *   member      := IDENT ("(" args? ")")? ;
 *   args        := arg ("," arg)* ;
 *   arg         := "?" | "this" | IDENT ;
 *   IDENT       := [_A-Za-z][_A-Za-z0-9]* ;
 * </pre>
 *
 * Whitespace is permitted between tokens. Any other character throws
 * {@link IllegalArgumentException} with the source and offending offset.
 */
final class ExpressionParser {

    private static final String THIS = "this";

    private final String src;
    private int pos;

    private ExpressionParser(String src) {
        this.src = src;
        this.pos = 0;
    }

    static ExpressionNode parse(String src) {
        ExpressionParser p = new ExpressionParser(src);
        ExpressionNode node = p.assignment();
        p.skipWhitespace();
        if (p.pos != src.length()) {
            throw new IllegalArgumentException(
                "Unexpected trailing input at offset " + p.pos + " in expression: " + src);
        }
        return node;
    }

    private ExpressionNode assignment() {
        ExpressionNode lhs = comparison();
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == '='
            && !(pos + 1 < src.length() && src.charAt(pos + 1) == '=')) {
            pos++;
            ExpressionNode.Arg rhs = parseArg();
            return new ExpressionNode.Assign(lhs, rhs);
        }
        return lhs;
    }

    private ExpressionNode comparison() {
        ExpressionNode lhs = additive();
        skipWhitespace();
        if (peekKeyword("instanceof")) {
            pos += "instanceof".length();
            skipWhitespace();
            String typeId = readIdent();
            return new ExpressionNode.InstanceOf(lhs, typeId);
        }
        String op = peekComparisonOp();
        if (op == null) return lhs;
        pos += op.length();
        ExpressionNode rhs = additive();
        return new ExpressionNode.Comparison(op, lhs, rhs);
    }

    private String peekComparisonOp() {
        if (pos >= src.length()) return null;
        char c0 = src.charAt(pos);
        char c1 = pos + 1 < src.length() ? src.charAt(pos + 1) : '\0';
        if (c0 == '=' && c1 == '=') return "==";
        if (c0 == '!' && c1 == '=') return "!=";
        if (c0 == '<' && c1 == '=') return "<=";
        if (c0 == '>' && c1 == '=') return ">=";
        if (c0 == '<') return "<";
        if (c0 == '>') return ">";
        return null;
    }

    private ExpressionNode additive() {
        ExpressionNode left = multiplicative();
        skipWhitespace();
        while (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
            char op = src.charAt(pos++);
            ExpressionNode right = multiplicative();
            left = new ExpressionNode.BinaryOp(op, left, right);
            skipWhitespace();
        }
        return left;
    }

    private ExpressionNode multiplicative() {
        ExpressionNode left = unary();
        skipWhitespace();
        while (pos < src.length() && (src.charAt(pos) == '*' || src.charAt(pos) == '/')) {
            char op = src.charAt(pos++);
            ExpressionNode right = unary();
            left = new ExpressionNode.BinaryOp(op, left, right);
            skipWhitespace();
        }
        return left;
    }

    private ExpressionNode unary() {
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == '?') {
            pos++;
            return ExpressionNode.Wildcard.INSTANCE;
        }
        if (pos < src.length() && src.charAt(pos) == '(') {
            int save = pos;
            pos++;
            skipWhitespace();
            if (pos < src.length() && isIdentStart(src.charAt(pos))) {
                int identStart = pos;
                String ident = readIdent();
                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ')') {
                    pos++;
                    ExpressionNode operand = unary();
                    return new ExpressionNode.Cast(ident, operand);
                }
                pos = identStart;
            }
            pos = save;
        }
        return selector();
    }

    private ExpressionNode selector() {
        skipWhitespace();
        ExpressionNode head;
        if (peekKeyword(THIS)) {
            pos += THIS.length();
            head = ExpressionNode.ThisRef.INSTANCE;
        } else {
            ExpressionNode.Member m = member();
            head = m.isCall()
                ? new ExpressionNode.Call(m.defId(), m.args())
                : new ExpressionNode.FieldRef(m.defId());
        }
        skipWhitespace();
        while (pos < src.length() && src.charAt(pos) == '.') {
            pos++;
            ExpressionNode.Member m = member();
            head = new ExpressionNode.Chained(head, m);
            skipWhitespace();
        }
        return head;
    }

    private ExpressionNode.Member member() {
        skipWhitespace();
        String ident = readIdent();
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == '(') {
            return parseCallTail(ident);
        }
        return new ExpressionNode.Member(ident, List.of(), false);
    }

    private ExpressionNode.Member parseCallTail(String defId) {
        expect('(');
        List<ExpressionNode.Arg> args = new ArrayList<>();
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == ')') {
            pos++;
            return new ExpressionNode.Member(defId, args, true);
        }
        args.add(parseArg());
        skipWhitespace();
        while (pos < src.length() && src.charAt(pos) == ',') {
            pos++;
            args.add(parseArg());
            skipWhitespace();
        }
        expect(')');
        return new ExpressionNode.Member(defId, args, true);
    }

    private ExpressionNode.Arg parseArg() {
        skipWhitespace();
        if (pos >= src.length()) {
            throw new IllegalArgumentException(
                "Expected argument at offset " + pos + " in expression: " + src);
        }
        char c = src.charAt(pos);
        if (c == '?') { pos++; return ExpressionNode.Wildcard.INSTANCE; }
        if (peekKeyword(THIS)) { pos += THIS.length(); return ExpressionNode.ThisRef.INSTANCE; }
        if (peekKeyword("true"))  { pos += 4; return new ExpressionNode.LiteralArg(ExpressionNode.LiteralArg.Kind.BOOL, Boolean.TRUE); }
        if (peekKeyword("false")) { pos += 5; return new ExpressionNode.LiteralArg(ExpressionNode.LiteralArg.Kind.BOOL, Boolean.FALSE); }
        if (peekKeyword("null"))  { pos += 4; return new ExpressionNode.LiteralArg(ExpressionNode.LiteralArg.Kind.NULL, null); }
        if (c == '"') return readStringLiteral();
        if (c == '-' || isDigit(c)) return readIntLiteral();
        if (isIdentStart(c)) return new ExpressionNode.NamedArg(readIdent());
        throw new IllegalArgumentException(
            "Unsupported argument at offset " + pos + " in expression: " + src);
    }

    private ExpressionNode.LiteralArg readIntLiteral() {
        int start = pos;
        if (src.charAt(pos) == '-') pos++;
        while (pos < src.length() && isDigit(src.charAt(pos))) pos++;
        String txt = src.substring(start, pos);
        if (txt.equals("-") || txt.isEmpty()) {
            throw new IllegalArgumentException(
                "Malformed integer literal at offset " + start + " in expression: " + src);
        }
        try {
            return new ExpressionNode.LiteralArg(ExpressionNode.LiteralArg.Kind.INT, Integer.parseInt(txt));
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException(
                "Integer literal '" + txt + "' out of range at offset " + start + " in expression: " + src);
        }
    }

    private ExpressionNode.LiteralArg readStringLiteral() {
        int start = pos;
        pos++;
        int contentStart = pos;
        while (pos < src.length() && src.charAt(pos) != '"') pos++;
        if (pos >= src.length()) {
            throw new IllegalArgumentException(
                "Unterminated string literal at offset " + start + " in expression: " + src);
        }
        String value = src.substring(contentStart, pos);
        pos++;
        return new ExpressionNode.LiteralArg(ExpressionNode.LiteralArg.Kind.STRING, value);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
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

    private boolean peekKeyword(String kw) {
        if (pos + kw.length() > src.length()) return false;
        if (!src.regionMatches(pos, kw, 0, kw.length())) return false;
        int after = pos + kw.length();
        if (after < src.length() && isIdentPart(src.charAt(after))) return false;
        return true;
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
