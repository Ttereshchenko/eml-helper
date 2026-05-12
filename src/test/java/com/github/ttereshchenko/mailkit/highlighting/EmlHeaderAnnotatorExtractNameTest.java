package com.github.ttereshchenko.mailkit.highlighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class EmlHeaderAnnotatorExtractNameTest {

    // ===== Positive Tests =====

    @Test
    void testExtractSimpleHeaderName() {
        assertEquals("From", EmlHeaderAnnotator.extractNameFromColon("From: test@example.com"));
    }

    @Test
    void testExtractHeaderWithHyphen() {
        assertEquals("Content-Type", EmlHeaderAnnotator.extractNameFromColon("Content-Type: text/plain"));
    }

    @Test
    void testExtractHeaderTrimsWhitespace() {
        assertEquals("From", EmlHeaderAnnotator.extractNameFromColon("From : test"));
    }

    @Test
    void testMultipleColons() {
        assertEquals("Subject", EmlHeaderAnnotator.extractNameFromColon("Subject: Re: Hello"));
    }

    // ===== Negative Tests =====

    @Test
    void testNoColonReturnsNull() {
        assertNull(EmlHeaderAnnotator.extractNameFromColon("NoColonHere"));
    }

    @Test
    void testColonAtStartReturnsNull() {
        assertNull(EmlHeaderAnnotator.extractNameFromColon(":value"));
    }

    @Test
    void testEmptyBeforeColonReturnsNull() {
        assertNull(EmlHeaderAnnotator.extractNameFromColon(" : value"));
    }
}
