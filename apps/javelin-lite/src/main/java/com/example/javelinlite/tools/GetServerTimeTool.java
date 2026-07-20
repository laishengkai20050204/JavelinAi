package com.example.javelinlite.tools;

import com.example.javelinlite.api.ChatRequest;
import com.example.javelinlite.api.ToolCall;
import com.example.javelinlite.api.ToolResult;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public final class GetServerTimeTool implements AiTool {

    @Override
    public String name() {
        return "get_server_time";
    }

    @Override
    public String description() {
        return "Get the current server time in an optional time zone.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "zoneId", Map.of(
                                "type", "string",
                                "description", "IANA time zone, for example Asia/Shanghai"
                        ),
                        "format", Map.of(
                                "type", "string",
                                "description", "Optional Java DateTimeFormatter pattern"
                        )
                )
        );
    }

    @Override
    public Mono<ToolResult> execute(ToolCall call, ChatRequest request) {
        return Mono.fromSupplier(() -> {
            String zoneText = String.valueOf(
                    call.arguments().getOrDefault("zoneId", ZoneId.systemDefault().getId())
            );
            String format = String.valueOf(
                    call.arguments().getOrDefault("format", "yyyy-MM-dd HH:mm:ss VV")
            );

            ZoneId zoneId = ZoneId.of(zoneText);
            ZonedDateTime now = ZonedDateTime.now(zoneId);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("zoneId", zoneId.getId());
            data.put("iso", now.toString());
            data.put("formatted", now.format(DateTimeFormatter.ofPattern(format)));
            data.put("epochMillis", now.toInstant().toEpochMilli());
            return ToolResult.success(call, data);
        });
    }
}
