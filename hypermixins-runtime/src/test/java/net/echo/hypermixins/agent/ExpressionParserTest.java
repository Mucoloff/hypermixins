package net.echo.hypermixins.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquals(true, ex.getMessage().contains("Unexpected trailing"));
    }

    @Test
    void rejectsNonWildcardArgument() {
        assertThrows(IllegalArgumentException.class,
            () -> ExpressionParser.parse("emit(42)"));
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
}
