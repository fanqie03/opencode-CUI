package com.opencode.cui.skill.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SequenceTracker 三级间隙检测策略单元测试。
 */
class SequenceTrackerTest {

    private SequenceTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new SequenceTracker();
    }

    @Test
    @DisplayName("Normal sequence returns continue")
    void normalSequenceReturnsContinue() {
        assertEquals("continue", tracker.validateSequence("s1", 1L));
        assertEquals("continue", tracker.validateSequence("s1", 2L));
        assertEquals("continue", tracker.validateSequence("s1", 3L));
    }

    @Test
    @DisplayName("Null sequence number returns continue")
    void nullSequenceNumberReturnsContinue() {
        assertEquals("continue", tracker.validateSequence("s1", null));
    }

    @Test
    @DisplayName("Small gap (1-3) returns continue")
    void smallGapReturnsContinue() {
        tracker.validateSequence("s1", 1L);
        // gap = 4 - 2 = 2 �?<=3 �?continue
        String action = tracker.validateSequence("s1", 4L);
        assertEquals("continue", action);
    }

    @Test
    @DisplayName("Medium gap (4-10) returns request_recovery")
    void mediumGapReturnsRequestRecovery() {
        tracker.validateSequence("s1", 1L);
        // gap = 9 - 2 = 7 �?<=10 �?request_recovery
        String action = tracker.validateSequence("s1", 9L);
        assertEquals("request_recovery", action);
    }

    @Test
    @DisplayName("Large gap (>10) returns reconnect")
    void largeGapReturnsReconnect() {
        tracker.validateSequence("s1", 1L);
        // gap = 22 - 2 = 20 �?>10 �?reconnect
        String action = tracker.validateSequence("s1", 22L);
        assertEquals("reconnect", action);
    }

    @Test
    @DisplayName("Duplicate/out-of-order returns continue")
    void duplicateReturnsContinue() {
        tracker.validateSequence("s1", 5L);
        // Receiving sequence 3 after 5 (out of order / duplicate)
        String action = tracker.validateSequence("s1", 3L);
        assertEquals("continue", action);
    }

    @Test
    @DisplayName("Reset session clears tracking")
    void resetSessionClearsTracking() {
        tracker.validateSequence("s1", 10L);
        tracker.resetSession("s1");
        // After reset, sequence 1 is fine (not a gap from 10)
        assertEquals("continue", tracker.validateSequence("s1", 1L));
    }

    @Test
    @DisplayName("getLastSequence returns correct value")
    void getLastSequenceReturnsCorrectValue() {
        assertEquals(0L, tracker.getLastSequence("unknown"));
        tracker.validateSequence("s1", 5L);
        assertEquals(5L, tracker.getLastSequence("s1"));
    }

    @Test
    @DisplayName("Independent sessions don't interfere")
    void independentSessionsDontInterfere() {
        tracker.validateSequence("s1", 1L);
        tracker.validateSequence("s2", 100L);
        // s1 should go to 2 (continue), not compare against s2's 100
        assertEquals("continue", tracker.validateSequence("s1", 2L));
        assertEquals("continue", tracker.validateSequence("s2", 101L));
    }

    @Test
    @DisplayName("Exact boundary: gap of 3 is small")
    void gapOf3IsSmall() {
        tracker.validateSequence("s1", 1L);
        // gap = 5 - 2 = 3 �?<=3 �?continue
        assertEquals("continue", tracker.validateSequence("s1", 5L));
    }

    @Test
    @DisplayName("Exact boundary: gap of 4 is medium")
    void gapOf4IsMedium() {
        tracker.validateSequence("s1", 1L);
        // gap = 6 - 2 = 4 �?<=10 �?request_recovery
        assertEquals("request_recovery", tracker.validateSequence("s1", 6L));
    }

    @Test
    @DisplayName("Exact boundary: gap of 10 is medium")
    void gapOf10IsMedium() {
        tracker.validateSequence("s1", 1L);
        // gap = 12 - 2 = 10 �?<=10 �?request_recovery
        assertEquals("request_recovery", tracker.validateSequence("s1", 12L));
    }

    @Test
    @DisplayName("Exact boundary: gap of 11 is large")
    void gapOf11IsLarge() {
        tracker.validateSequence("s1", 1L);
        // gap = 13 - 2 = 11 �?>10 �?reconnect
        assertEquals("reconnect", tracker.validateSequence("s1", 13L));
    }
}
