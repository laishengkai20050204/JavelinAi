package com.example.markdown;

import com.vladsch.flexmark.formatter.Formatter;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Arrays;

public final class MarkdownCanonicalizer {
    private final Parser parser;
    private final Formatter formatter;
    private static final Pattern FENCE_LINE = Pattern.compile("^\\s*([`~]{3,})(.*)$");

    public MarkdownCanonicalizer() {
        MutableDataSet opts = new MutableDataSet()
                // 需要 GFM 特性的话可按需启用扩展；先保持最小化，稳定输出
                // .set(Parser.EXTENSIONS, Arrays.asList(TablesExtension.create(), ...))
                ;
        this.parser = Parser.builder(opts).build();
        this.formatter = Formatter.builder(opts).build();
    }

    public String canonicalize(String raw) {
        if (raw == null) return "";
        String s = raw.replace("\r\n", "\n").replace('\r', '\n'); // 统一换行

        // ✅ 语言无关的围栏粘连修复（最重要的一步）
        s = fixStuckFenceOpeners(s);

        // 其余轻量规范化（保持之前逻辑）
        s = s.replaceAll("(?m)^(#{1,6})([^ \\t#\\n])", "$1 $2");                          // # 后补空格
        s = s.replaceAll("(?m)([^\\n])\\n(- |\\* |\\d+\\. |\\|)", "$1\n\n$2");            // 列表/表格前空行
        s = s.replaceAll("\\n{3,}", "\n\n");                                              // 折叠 3+ 空行

        // 围栏奇数补闭合（用默认 ```；更精细可记录最后一次 open 的 marker）
        int fences = (s.startsWith("```") ? 1 : 0) + countOccurrences(s, "\n```")
                + (s.startsWith("~~~") ? 1 : 0) + countOccurrences(s, "\n~~~");
        if ((fences & 1) == 1) s += (s.endsWith("\n") ? "" : "\n") + "```";

        // 解析 → 格式化
        Node doc = parser.parse(s);
        return formatter.render(doc);
    }


    /** 逐行扫描：把“开 fence 行 + 语言 + 代码”拆成两行；关闭 fence 行去掉多余文字。 */
    private static String fixStuckFenceOpeners(String input) {
        String[] lines = input.split("\n", -1); // 保留末尾空行
        StringBuilder out = new StringBuilder(input.length() + 64);
        boolean inFence = false;
        String lastOpenMarker = "```"; // 记录最近一次开 fence 的 marker，备用

        for (String line : lines) {
            Matcher m = FENCE_LINE.matcher(line);
            if (m.matches()) {
                String marker = m.group(1);       // ```、````、~~~ ...
                String tail   = m.group(2);       // 语言、空格、以及可能被粘上的代码

                // 去掉行尾空白，避免误判
                String tailTrim = tail.replaceFirst("\\s+$", "");

                if (!inFence) {
                    // —— 开 fence ——：抽出“语言标记（可为空）”与被粘上的“剩余代码”
                    int i = 0;
                    // 跳过 fence 后的若干空白
                    while (i < tailTrim.length() && Character.isWhitespace(tailTrim.charAt(i))) i++;
                    int langStart = i;
                    while (i < tailTrim.length() && !Character.isWhitespace(tailTrim.charAt(i))) i++;
                    String lang = tailTrim.substring(langStart, i); // 可能为空（没写语言）
                    while (i < tailTrim.length() && Character.isWhitespace(tailTrim.charAt(i))) i++;
                    String rest = tailTrim.substring(i); // 若非空，说明“代码被粘在同一行”

                    // 输出：干净的 fence 行（marker + 语言），然后再输出被粘的代码（换到下一行）
                    out.append(marker).append(lang.isEmpty() ? "" : lang).append('\n');
                    if (!rest.isEmpty()) out.append(rest).append('\n');

                    inFence = true;
                    lastOpenMarker = marker; // 记下用于潜在的自动补闭合
                    continue;
                } else {
                    // —— 关 fence ——：把多余文字丢掉，只保留 marker
                    out.append(marker).append('\n');
                    inFence = false;
                    continue;
                }
            }

            // 普通行：原样抄
            out.append(line).append('\n');
        }

        // 若到文件末尾仍在 fence 中，补一个与 open 对应长度/符号的闭合
        if (inFence) out.append(lastOpenMarker).append('\n');

        return out.toString();
    }

    private static int countOccurrences(String s, String sub) {
        int c = 0, idx = 0;
        while ((idx = s.indexOf(sub, idx)) >= 0) { c++; idx += sub.length(); }
        return c;
    }
}
