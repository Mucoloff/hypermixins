package net.echo.hypermixins.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionParserTest {

    @Test
    void parsesBareIdentifierAsFieldRef() {
        ExpressionNode node = ExpressionParser.parse("counter");
        ExpressionNode.FieldRef f = assertInstanceOf(ExpressionNode.FieldRef.class, node);
        assertEquals("counter", f.defId());
    }

    @Test
    void parsesCallWithNoArgs() {
        ExpressionNode node = ExpressionParser.parse("tick()");
        ExpressionNode.Call c = assertInstanceOf(ExpressionNode.Call.class, node);
        assertEquals("tick", c.defId());
        assertEquals(List.of(), c.args());
    }

    @Test
    void parsesCallWithMultipleWildcards() {
        ExpressionNode node = ExpressionParser.parse("emit(?, ?, ?)");
        ExpressionNode.Call c = assertInstanceOf(ExpressionNode.Call.class, node);
        assertEquals("emit", c.defId());
        assertEquals(3, c.args().size());
        for (ExpressionNode.Arg a : c.args()) {
            assertInstanceOf(ExpressionNode.Wildcard.class, a);
        }
    }

    @Test
    void rejectsTrailingGarbage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> ExpressionParser.parse("tick() extra"));
        assertTrue(ex.getMessage().contains("Unexpected trailing"));
    }

    @Test
    void parsesIntLiteralArg() {
        ExpressionNode node = ExpressionParser.parse("emit(42)");
        ExpressionNode.Call c = assertInstanceOf(ExpressionNode.Call.class, node);
        ExpressionNode.LiteralArg lit = assertInstanceOf(ExpressionNode.LiteralArg.class, c.args().get(0));
        assertEquals(ExpressionNode.LiteralArg.Kind.INT, lit.kind());
        assertEquals(42, lit.value());
    }

    @Test
    void rejectsEmptyInput() {
        assertThrows(IllegalArgumentException.class,
            () -> ExpressionParser.parse(""));
    }

    @Test
    void rejectsUnclosedCall() {
        assertThrows(IllegalArgumentException.class,
            () -> ExpressionParser.parse("emit(?"));
    }

    @Test
    void parsesThisFieldAccess() {
        ExpressionNode node = ExpressionParser.parse("this.counter");
        ExpressionNode.Chained c = assertInstanceOf(ExpressionNode.Chained.class, node);
        assertInstanceOf(ExpressionNode.ThisRef.class, c.receiver());
        assertEquals("counter", c.member().defId());
        assertFalse(c.member().isCall());
    }

    @Test
    void parsesThisMethodCall() {
        ExpressionNode node = ExpressionParser.parse("this.tick()");
        ExpressionNode.Chained c = assertInstanceOf(ExpressionNode.Chained.class, node);
        assertInstanceOf(ExpressionNode.ThisRef.class, c.receiver());
        assertEquals("tick", c.member().defId());
        assertTrue(c.member().isCall());
        assertEquals(0, c.member().args().size());
    }

    @Test
    void parsesAssignmentToThisField() {
        ExpressionNode node = ExpressionParser.parse("this.counter = ?");
        ExpressionNode.Assign a = assertInstanceOf(ExpressionNode.Assign.class, node);
        assertInstanceOf(ExpressionNode.Chained.class, a.lhs());
        assertInstanceOf(ExpressionNode.Wildcard.class, a.rhs());
    }

    @Test
    void parsesBareAssignment() {
        ExpressionNode node = ExpressionParser.parse("counter = ?");
        ExpressionNode.Assign a = assertInstanceOf(ExpressionNode.Assign.class, node);
        assertInstanceOf(ExpressionNode.FieldRef.class, a.lhs());
    }

    @Test
    void parsesThisAsArg() {
        ExpressionNode node = ExpressionParser.parse("emit(this, ?)");
        ExpressionNode.Call c = assertInstanceOf(ExpressionNode.Call.class, node);
        assertEquals(2, c.args().size());
        assertInstanceOf(ExpressionNode.ThisRef.class, c.args().get(0));
        assertInstanceOf(ExpressionNode.Wildcard.class, c.args().get(1));
    }

    @Test
    void rejectsThisAlone() {
        // `this` by itself parses to ThisRef, but the matcher (compile-time validation) rejects.
        // Parser accepts; matcher tests cover the rejection.
        ExpressionNode node = ExpressionParser.parse("this");
        assertInstanceOf(ExpressionNode.ThisRef.class, node);
    }

    @Test
    void notFoldsComparisonToNegatedOperator() {
        ExpressionNode node = ExpressionParser.parse("!(? == ?)");
        ExpressionNode.Comparison c = assertInstanceOf(ExpressionNode.Comparison.class, node);
        assertEquals("!=", c.op());
    }

    @Test
    void notFoldsLessThanToGreaterEqual() {
        ExpressionNode node = ExpressionParser.parse("!(? < ?)");
        ExpressionNode.Comparison c = assertInstanceOf(ExpressionNode.Comparison.class, node);
        assertEquals(">=", c.op());
    }

    @Test
    void parsesParenthesisedComparison() {
        ExpressionNode node = ExpressionParser.parse("(? < ?)");
        ExpressionNode.Comparison c = assertInstanceOf(ExpressionNode.Comparison.class, node);
        assertEquals("<", c.op());
    }

    @Test
    void rejectsNotOverNonComparison() {
        assertThrows(IllegalArgumentException.class, () -> ExpressionParser.parse("!counter"));
    }

    @Test
    void parsesLogicalAnd() {
        ExpressionNode node = ExpressionParser.parse("? < ? && ? > ?");
        ExpressionNode.LogicalOp lo = assertInstanceOf(ExpressionNode.LogicalOp.class, node);
        assertEquals("&&", lo.op());
        assertInstanceOf(ExpressionNode.Comparison.class, lo.lhs());
        assertInstanceOf(ExpressionNode.Comparison.class, lo.rhs());
    }

    @Test
    void parsesLogicalOr() {
        ExpressionNode node = ExpressionParser.parse("? == ? || ? != ?");
        ExpressionNode.LogicalOp lo = assertInstanceOf(ExpressionNode.LogicalOp.class, node);
        assertEquals("||", lo.op());
    }

    @Test
    void rejectsLoneAmpersand() {
        assertThrows(IllegalArgumentException.class, () -> ExpressionParser.parse("? < ? & ? > ?"));
    }

    @Test
    void parsesNaryAndAsLeftAssocChain() {
        ExpressionNode node = ExpressionParser.parse("? < ? && ? < ? && ? < ?");
        ExpressionNode.LogicalOp outer = assertInstanceOf(ExpressionNode.LogicalOp.class, node);
        assertEquals("&&", outer.op());
        // Left-associative: ((a && b) && c) — lhs is itself a LogicalOp, rhs a Comparison.
        assertInstanceOf(ExpressionNode.LogicalOp.class, outer.lhs());
        assertInstanceOf(ExpressionNode.Comparison.class, outer.rhs());
    }
}
