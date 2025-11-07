package com.example.markdown;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MarkdownCanonicalizerTest {

    private final MarkdownCanonicalizer md = new MarkdownCanonicalizer();

    @Test
    void normalizeNewlinesAndHeadingSpace() {
        String in  = "#标题\r\n\r\n段落\r\n- a\r\n- b\r\n";
        String out = md.canonicalize(in);
        assertTrue(out.contains("# 标题"), "应在 # 后补空格");
        assertFalse(out.contains("\r"), "应统一为 \\n");
    }

    @Test
    void ensureBlankLineBeforeList() {
        String in  = "上文\n- item1\n- item2\n";
        String out = md.canonicalize(in);
        assertTrue(out.contains("上文\n\n- item1"), "列表前应有空行");
    }

    @Test
    void balanceFencedCode() {
        String in  = "```js\nconsole.log(1)\n"; // 少了闭合 fence
        String out = md.canonicalize(in);
        assertTrue(out.trim().endsWith("```"), "未闭合的围栏应被补齐");
    }

    @Test
    void idempotentOnSecondPass() {
        String in  = "#Title\n\n```js\nconsole.log(1)\n```\n";
        String once = md.canonicalize(in);
        String twice = md.canonicalize(once);
        assertEquals(once, twice, "规范化应具备幂等性");
    }
}
