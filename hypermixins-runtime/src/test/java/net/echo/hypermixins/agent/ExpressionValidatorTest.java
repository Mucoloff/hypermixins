package net.echo.hypermixins.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionValidatorTest {

    @Test
    void validExpressionsReturnNull() {
        assertNull(ExpressionValidator.parseError("greet(?)"));
        assertNull(ExpressionValidator.parseError("this.counter = ?"));
        assertNull(ExpressionValidator.parseError("? < ? && ? > ?"));
        assertNull(ExpressionValidator.parseError("(Str) ?"));
        assertTrue(ExpressionValidator.isValid("session().write(?)"));
    }

    @Test
    void malformedExpressionsReturnMessage() {
        assertNotNull(ExpressionValidator.parseError("emit("));
        assertNotNull(ExpressionValidator.parseError(""));
        assertNotNull(ExpressionValidator.parseError("emit(?"));
        assertFalse(ExpressionValidator.isValid("tick() trailing"));
    }

    @Test
    void nullExpressionReportsNullMessage() {
        assertNotNull(ExpressionValidator.parseError(null));
    }
}
