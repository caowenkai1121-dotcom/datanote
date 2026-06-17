package com.datanote.platform.ai.agent.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** DocIngestService.chunk 纯函数单测: 切块/重叠/边界/防死循环。 */
class DocIngestChunkTest {

    @Test
    void nullOrBlank_returnsEmpty() {
        assertTrue(DocIngestService.chunk(null, 800, 100).isEmpty());
        assertTrue(DocIngestService.chunk("", 800, 100).isEmpty());
        assertTrue(DocIngestService.chunk("   \n\t ", 800, 100).isEmpty());
    }

    @Test
    void shorterThanSize_singleChunk() {
        List<String> c = DocIngestService.chunk("hello world", 800, 100);
        assertEquals(1, c.size());
        assertEquals("hello world", c.get(0));
    }

    @Test
    void multiChunk_overlapContinuity() {
        // 10 字符, size=4, overlap=1 → step=3: abcd / defg / ghij / j
        List<String> c = DocIngestService.chunk("abcdefghij", 4, 1);
        assertEquals("abcd", c.get(0));
        assertEquals("defg", c.get(1));
        assertEquals("ghij", c.get(2));
        // 相邻块末尾字符 == 下一块首字符(重叠 1)
        assertEquals(c.get(0).charAt(3), c.get(1).charAt(0));
        assertEquals(c.get(1).charAt(3), c.get(2).charAt(0));
    }

    @Test
    void overlapGreaterEqualSize_noInfiniteLoop() {
        // overlap>=size 应被重置为 0, 正常推进而非死循环
        List<String> c = DocIngestService.chunk("abcdefgh", 4, 4);
        assertEquals(2, c.size());
        assertEquals("abcd", c.get(0));
        assertEquals("efgh", c.get(1));
    }

    @Test
    void coversWholeText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) sb.append((char) ('a' + i % 26));
        List<String> c = DocIngestService.chunk(sb.toString(), 800, 100);
        // 末块应含原文末字符
        assertTrue(c.get(c.size() - 1).endsWith(String.valueOf(sb.charAt(sb.length() - 1))));
        assertTrue(c.size() >= 3);
    }
}
