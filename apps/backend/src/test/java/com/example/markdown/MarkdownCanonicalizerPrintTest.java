package com.example.markdown;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;

public class MarkdownCanonicalizerPrintTest {

    private final MarkdownCanonicalizer md = new MarkdownCanonicalizer();
    private static String SOURCE = "";

    @Test
    void printFormatted() throws Exception {
        String input = loadInput();
        String output = md.canonicalize(input);

        System.out.println("[INFO] Markdown source = " + SOURCE);
        System.out.println("===== RAW (输入原文) =====");
        System.out.println(input);

        System.out.println("\n===== FORMATTED (规范化结果) =====");
        System.out.println(output);
    }

    private static String loadInput() throws Exception {
        // 1) -Dmd.file 覆盖
        String fileProp = System.getProperty("md.file");
        if (fileProp != null && !fileProp.isBlank()) {
            Path p = Path.of(fileProp).toAbsolutePath();
            SOURCE = "VM md.file -> " + p;
            return Files.readString(p, UTF_8);
        }
        // 2) -Dmd 覆盖
        String mdProp = System.getProperty("md");
        if (mdProp != null && !mdProp.isBlank()) {
            SOURCE = "VM md (inline text)";
            return mdProp;
        }
        // 3) classpath 资源：/md/sample.md
        try (InputStream is = MarkdownCanonicalizerPrintTest.class.getResourceAsStream("/md/sample.md")) {
            if (is != null) {
                SOURCE = "classpath:/md/sample.md";
                return new String(is.readAllBytes(), UTF_8);
            }
        }
        // 4) 兜底：直接从项目文件系统读取（防止 test resources 未被标记/编译）
        Path fs = Path.of("src/test/resources/md/sample.md");
        if (Files.exists(fs)) {
            SOURCE = "filesystem -> " + fs.toAbsolutePath();
            return Files.readString(fs, UTF_8);
        }
        // 5) 最终兜底：内置示例
        SOURCE = "built-in sample";
        return "#标题\n上文\n- item1\n- item2\n```js\nconsole.log(1)\n";
    }
}
