package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillMessagePart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PartBufferServiceTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ListOperations<String, String> listOps;
    @Mock
    private ValueOperations<String, String> valueOps;

    private ObjectMapper objectMapper;
    private PartBufferService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        lenient().when(redis.opsForList()).thenReturn(listOps);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        service = new PartBufferService(redis, objectMapper);
    }

    @Test
    @DisplayName("bufferPart should RPUSH serialized part to Redis list and set TTL")
    void bufferPartRpushAndTtl() throws Exception {
        SkillMessagePart part = SkillMessagePart.builder()
                .id(1L).messageId(100L).sessionId(10L)
                .partId("part-1").seq(1).partType("text")
                .content("hello")
                .build();

        service.bufferPart(100L, part);

        verify(listOps).rightPush(eq("ss:part-buf:100"), anyString());
        verify(redis).expire(eq("ss:part-buf:100"), eq(1L), eq(TimeUnit.HOURS));
    }

    @Test
    @DisplayName("nextSeq should INCR Redis counter and set TTL on first call")
    void nextSeqIncrement() {
        when(valueOps.increment(eq("ss:part-seq:100"))).thenReturn(1L);

        int seq = service.nextSeq(100L);

        assertThat(seq).isEqualTo(1);
        verify(valueOps).increment("ss:part-seq:100");
        verify(redis).expire(eq("ss:part-seq:100"), eq(1L), eq(TimeUnit.HOURS));
    }

    @Test
    @DisplayName("flushParts should return all buffered parts and delete Redis keys")
    void flushPartsReturnAndCleanup() throws Exception {
        SkillMessagePart part1 = SkillMessagePart.builder()
                .id(1L).messageId(100L).sessionId(10L)
                .partId("p1").seq(1).partType("text").content("a")
                .build();
        SkillMessagePart part2 = SkillMessagePart.builder()
                .id(2L).messageId(100L).sessionId(10L)
                .partId("p2").seq(2).partType("tool").toolName("bash")
                .build();

        String json1 = objectMapper.writeValueAsString(part1);
        String json2 = objectMapper.writeValueAsString(part2);
        when(listOps.range("ss:part-buf:100", 0, -1)).thenReturn(List.of(json1, json2));

        List<SkillMessagePart> result = service.flushParts(100L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getPartId()).isEqualTo("p1");
        assertThat(result.get(1).getPartId()).isEqualTo("p2");
        verify(redis).delete("ss:part-buf:100");
        verify(redis).delete("ss:part-seq:100");
    }

    @Test
    @DisplayName("flushParts returns empty list when no buffered parts")
    void flushPartsEmptyBuffer() {
        when(listOps.range("ss:part-buf:100", 0, -1)).thenReturn(null);

        List<SkillMessagePart> result = service.flushParts(100L);

        assertThat(result).isEmpty();
        verify(redis).delete("ss:part-buf:100");
        verify(redis).delete("ss:part-seq:100");
    }

    @Test
    @DisplayName("findLatestPendingPermission finds permission part with no response from buffer")
    void findLatestPendingPermission() throws Exception {
        SkillMessagePart textPart = SkillMessagePart.builder()
                .id(1L).messageId(100L).sessionId(10L)
                .partId("p1").seq(1).partType("text").content("hi")
                .build();
        SkillMessagePart permPart = SkillMessagePart.builder()
                .id(2L).messageId(100L).sessionId(10L)
                .partId("perm-1").seq(2).partType("permission")
                .toolCallId("perm-1").toolName("Bash")
                .build();

        String json1 = objectMapper.writeValueAsString(textPart);
        String json2 = objectMapper.writeValueAsString(permPart);
        when(listOps.range("ss:part-buf:100", 0, -1)).thenReturn(List.of(json1, json2));

        SkillMessagePart result = service.findLatestPendingPermission(100L);

        assertThat(result).isNotNull();
        assertThat(result.getPartId()).isEqualTo("perm-1");
    }

    @Test
    @DisplayName("findLatestPendingPermission returns null if permission already has response")
    void findLatestPendingPermissionCompleted() throws Exception {
        SkillMessagePart permPart = SkillMessagePart.builder()
                .id(2L).messageId(100L).sessionId(10L)
                .partId("perm-1").seq(2).partType("permission")
                .toolCallId("perm-1").toolOutput("once")
                .build();

        String json = objectMapper.writeValueAsString(permPart);
        when(listOps.range("ss:part-buf:100", 0, -1)).thenReturn(List.of(json));

        SkillMessagePart result = service.findLatestPendingPermission(100L);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("updatePermissionReply finds and updates matching permission part")
    void updatePermissionReplyFindsAndUpdates() throws Exception {
        SkillMessagePart textPart = SkillMessagePart.builder()
                .id(1L).messageId(100L).sessionId(10L)
                .partId("p1").seq(1).partType("text").content("hi")
                .build();
        SkillMessagePart permPart = SkillMessagePart.builder()
                .id(2L).messageId(100L).sessionId(10L)
                .partId("perm-1").seq(2).partType("permission")
                .toolCallId("perm-id-1").toolName("Bash")
                .build();

        String json1 = objectMapper.writeValueAsString(textPart);
        String json2 = objectMapper.writeValueAsString(permPart);
        when(listOps.range("ss:part-buf:100", 0, -1)).thenReturn(List.of(json1, json2));

        boolean result = service.updatePermissionReply(100L, "perm-id-1", "completed", "approved");

        assertThat(result).isTrue();
        verify(listOps).set(eq("ss:part-buf:100"), eq(1L), argThat(updatedJson -> {
            try {
                SkillMessagePart updated = objectMapper.readValue(updatedJson, SkillMessagePart.class);
                return "completed".equals(updated.getToolStatus()) && "approved".equals(updated.getToolOutput());
            } catch (Exception e) {
                return false;
            }
        }));
    }

    @Test
    @DisplayName("updatePermissionReply returns false when no matching permission found")
    void updatePermissionReplyNotFound() throws Exception {
        SkillMessagePart textPart = SkillMessagePart.builder()
                .id(1L).messageId(100L).sessionId(10L)
                .partId("p1").seq(1).partType("text").content("hello")
                .build();

        String json1 = objectMapper.writeValueAsString(textPart);
        when(listOps.range("ss:part-buf:100", 0, -1)).thenReturn(List.of(json1));

        boolean result = service.updatePermissionReply(100L, "perm-id-1", "completed", "approved");

        assertThat(result).isFalse();
        verify(listOps, never()).set(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("updatePermissionReply returns false when buffer is empty")
    void updatePermissionReplyEmptyBuffer() {
        when(listOps.range("ss:part-buf:100", 0, -1)).thenReturn(null);

        boolean result = service.updatePermissionReply(100L, "perm-id-1", "completed", "approved");

        assertThat(result).isFalse();
        verify(listOps, never()).set(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("prepareFlush snapshots into temp key without dropping the buffer")
    void prepareFlushSnapshots() throws Exception {
        SkillMessagePart part = SkillMessagePart.builder()
                .id(1L).messageId(100L).sessionId(10L)
                .partId("part-1").seq(1).partType("text").content("hi")
                .build();
        String json = objectMapper.writeValueAsString(part);
        when(listOps.range(startsWith("ss:part-buf:100:flush:"), eq(0L), eq(-1L)))
                .thenReturn(List.of(json));

        PartBufferService.FlushBatch batch = service.prepareFlush(100L);

        verify(redis).rename(eq("ss:part-buf:100"), startsWith("ss:part-buf:100:flush:"));
        // Critical: prepare must NOT delete buffer or seq.
        verify(redis, never()).delete(startsWith("ss:part-buf:100"));
        assertThat(batch.parts()).hasSize(1);
        assertThat(batch.tempKey()).startsWith("ss:part-buf:100:flush:");
    }

    @Test
    @DisplayName("commitFlush deletes temp key and seq counter")
    void commitFlushDeletesTempAndSeq() {
        PartBufferService.FlushBatch batch = new PartBufferService.FlushBatch(
                100L, "ss:part-buf:100:flush:abc", List.of());

        service.commitFlush(batch);

        verify(redis).delete("ss:part-buf:100:flush:abc");
        verify(redis).delete("ss:part-seq:100");
    }

    @Test
    @DisplayName("rollbackFlush LPUSHes snapshot back into live buffer in original order")
    void rollbackFlushRestoresBuffer() throws Exception {
        SkillMessagePart p1 = SkillMessagePart.builder().id(1L).partId("part-1").content("a").build();
        SkillMessagePart p2 = SkillMessagePart.builder().id(2L).partId("part-2").content("b").build();
        String j1 = objectMapper.writeValueAsString(p1);
        String j2 = objectMapper.writeValueAsString(p2);
        when(listOps.range("ss:part-buf:100:flush:abc", 0, -1)).thenReturn(List.of(j1, j2));

        PartBufferService.FlushBatch batch = new PartBufferService.FlushBatch(
                100L, "ss:part-buf:100:flush:abc", List.of(p1, p2));

        service.rollbackFlush(batch);

        // LPUSH with reversed args = original order at head of buffer
        verify(listOps).leftPushAll(eq("ss:part-buf:100"), eq(j2), eq(j1));
        verify(redis).delete("ss:part-buf:100:flush:abc");
    }

    @Test
    @DisplayName("rollbackFlush is a no-op for an empty batch")
    void rollbackFlushNoopForEmptyBatch() {
        PartBufferService.FlushBatch empty = new PartBufferService.FlushBatch(100L, null, List.of());
        service.rollbackFlush(empty);
        verifyNoInteractions(listOps);
    }

    @Test
    @DisplayName("prepareFlush throws when RENAME succeeds but RANGE fails (fail-closed, snapshot preserved)")
    void prepareFlushThrowsOnRangeFailureAfterRename() {
        // RENAME succeeds; RANGE on temp key throws — must NOT silently return empty batch.
        when(listOps.range(startsWith("ss:part-buf:100:flush:"), eq(0L), eq(-1L)))
                .thenThrow(new RuntimeException("redis network blip"));

        try {
            service.prepareFlush(100L);
            org.assertj.core.api.Assertions.fail("expected FlushPrepareException");
        } catch (PartBufferService.FlushPrepareException e) {
            assertThat(e.getMessageDbId()).isEqualTo(100L);
            assertThat(e.getTempKey()).startsWith("ss:part-buf:100:flush:");
        }
        // Snapshot must be retained — no delete of the temp key.
        verify(redis, never()).delete(startsWith("ss:part-buf:100"));
    }

    @Test
    @DisplayName("prepareFlush returns empty batch when RENAME fails with 'no such key' (benign)")
    void prepareFlushReturnsEmptyOnMissingKey() {
        doThrow(new RuntimeException("ERR no such key"))
                .when(redis).rename(eq("ss:part-buf:100"), startsWith("ss:part-buf:100:flush:"));

        PartBufferService.FlushBatch batch = service.prepareFlush(100L);

        assertThat(batch.parts()).isEmpty();
        assertThat(batch.tempKey()).isNull();
        verify(redis, never()).delete(anyString());
    }

    @Test
    @DisplayName("prepareFlush throws when RENAME fails for a non-'no such key' reason (network/auth/timeout)")
    void prepareFlushThrowsOnRealRedisFailureDuringRename() {
        doThrow(new RuntimeException("Connection refused"))
                .when(redis).rename(eq("ss:part-buf:100"), startsWith("ss:part-buf:100:flush:"));

        try {
            service.prepareFlush(100L);
            org.assertj.core.api.Assertions.fail("expected FlushPrepareException for non-benign RENAME failure");
        } catch (PartBufferService.FlushPrepareException e) {
            assertThat(e.getMessageDbId()).isEqualTo(100L);
            // tempKey is null because RENAME never succeeded — the live buffer is still intact.
            assertThat(e.getTempKey()).isNull();
        }
        // Live buffer must be untouched so the next finalize can retry the same data.
        verify(redis, never()).delete(anyString());
    }

    @Test
    @DisplayName("prepareFlush also recognises 'no such key' wrapped in a Spring DataAccessException cause chain")
    void prepareFlushHandlesWrappedNoSuchKey() {
        Throwable inner = new RuntimeException("ERR no such key");
        Throwable outer = new RuntimeException("Spring wrapper", inner);
        doThrow(outer)
                .when(redis).rename(eq("ss:part-buf:100"), startsWith("ss:part-buf:100:flush:"));

        PartBufferService.FlushBatch batch = service.prepareFlush(100L);

        assertThat(batch.parts()).isEmpty();
        assertThat(batch.tempKey()).isNull();
    }

    @Test
    @DisplayName("rollbackFlush preserves temp key when LPUSH back fails (fail-closed for offline recovery)")
    void rollbackFlushPreservesTempKeyOnLpushFailure() throws Exception {
        SkillMessagePart p1 = SkillMessagePart.builder().id(1L).partId("part-1").content("a").build();
        String j1 = objectMapper.writeValueAsString(p1);
        when(listOps.range("ss:part-buf:100:flush:abc", 0, -1)).thenReturn(List.of(j1));
        doThrow(new RuntimeException("redis down"))
                .when(listOps).leftPushAll(eq("ss:part-buf:100"), any(String[].class));

        PartBufferService.FlushBatch batch = new PartBufferService.FlushBatch(
                100L, "ss:part-buf:100:flush:abc", List.of(p1));

        service.rollbackFlush(batch); // logs ERROR, does NOT rethrow

        // Critical: temp key must NOT be deleted so the snapshot survives for manual recovery.
        verify(redis, never()).delete("ss:part-buf:100:flush:abc");
    }
}
