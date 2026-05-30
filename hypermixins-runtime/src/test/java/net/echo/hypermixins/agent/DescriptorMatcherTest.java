package net.echo.hypermixins.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DescriptorMatcherTest {

    @Test
    void exactStringMatches() {
        assertTrue(DescriptorMatcher.matches("java/lang/String.valueOf(I)Ljava/lang/String;",
            "java/lang/String.valueOf(I)Ljava/lang/String;"));
        assertFalse(DescriptorMatcher.matches("java/lang/String.valueOf(I)Ljava/lang/String;",
            "java/lang/String.valueOf(J)Ljava/lang/String;"));
    }

    @Test
    void globMatchesWildcards() {
        assertTrue(DescriptorMatcher.matches("java/lang/String.valueOf*",
            "java/lang/String.valueOf(I)Ljava/lang/String;"));
        assertTrue(DescriptorMatcher.matches("java/lang/*.valueOf(I)Ljava/lang/String;",
            "java/lang/Integer.valueOf(I)Ljava/lang/String;"));
        assertFalse(DescriptorMatcher.matches("java/lang/String.parseInt*",
            "java/lang/String.valueOf(I)Ljava/lang/String;"));
    }

    @Test
    void globEscapesRegexMetacharacters() {
        // Dot is a regex metachar but a literal in glob form.
        assertTrue(DescriptorMatcher.matches("a.b.c*", "a.b.c.d"));
        assertFalse(DescriptorMatcher.matches("a.b.c*", "aXbXc.d"));
    }

    @Test
    void regexPrefixRunsRawPattern() {
        assertTrue(DescriptorMatcher.matches(
            "regex:java/lang/.*\\.(valueOf|toString)\\(I\\)Ljava/lang/String;",
            "java/lang/Integer.valueOf(I)Ljava/lang/String;"));
        assertTrue(DescriptorMatcher.matches(
            "regex:java/lang/.*\\.(valueOf|toString)\\(I\\)Ljava/lang/String;",
            "java/lang/String.toString(I)Ljava/lang/String;"));
        assertFalse(DescriptorMatcher.matches(
            "regex:java/lang/.*\\.(valueOf|toString)\\(I\\)Ljava/lang/String;",
            "java/util/Map.valueOf(I)Ljava/lang/String;"));
    }
}
