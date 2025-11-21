package com.example.controller;

import com.example.config.AiMultiModelProperties;
import com.example.config.EffectiveProps;
import com.example.runtime.ConfigStore;
import com.example.runtime.RuntimeConfig;
import com.example.runtime.RuntimeConfigService;
import com.example.tools.AiTool;
import com.example.tools.ToolRegistry;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final RuntimeConfigService cfgSvc;
    private final ConfigStore store;
    private final EffectiveProps effectiveProps;
    private final ToolRegistry toolRegistry;
    private final AiMultiModelProperties multiProps;

    @Operation(summary = "获取配置")
    @GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> get() {
        var rc = cfgSvc.view();

        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("profile", rc.getProfile());
        runtime.put("toolsMaxLoops", rc.getToolsMaxLoops());
        runtime.put("toolToggles", rc.getToolToggles());
        runtime.put("memoryMaxMessages", rc.getMemoryMaxMessages());

        Map<String, Object> effective = new LinkedHashMap<>();
        effective.put("profile", effectiveProps.profileOr(multiProps.getPrimaryModel()));
        effective.put("toolsMaxLoops", effectiveProps.toolsMaxLoops());
        effective.put("memoryMaxMessages", effectiveProps.memoryMaxMessages());

        List<String> serverToolNames = toolRegistry.allTools().stream().map(AiTool::name).toList();
        Set<String> available = new LinkedHashSet<>(serverToolNames);
        if (rc.getToolToggles() != null) {
            available.addAll(rc.getToolToggles().keySet());
        }

        log.debug("[ADMIN][GET]/config runtime: profile={} loops={} memWin={} togglesKeys={}",
                runtime.get("profile"), runtime.get("toolsMaxLoops"), runtime.get("memoryMaxMessages"), runtime.get("toolToggles"));
        log.debug("[ADMIN][GET]/config effective: profile={} loops={} memWin={} togglesKeys={}",
                effective.get("profile"), effective.get("toolsMaxLoops"), effective.get("memoryMaxMessages"), runtime.get("toolToggles"));

        return Map.of(
                "runtime", runtime,
                "effective", effective,
                "availableTools", List.copyOf(available)
        );
    }

    @Operation(summary = "修改配置（合并语义：只更新传入的字段）")
    @PutMapping(value = "/config", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> put(@RequestBody Map<String, Object> body) throws Exception {
        RuntimeConfig old = cfgSvc.view();

        boolean togglesPresent = body.containsKey("toolToggles");
        @SuppressWarnings("unchecked")
        Map<String, Object> rawToggles = togglesPresent ? (Map<String, Object>) body.get("toolToggles") : null;
        Map<String, Boolean> incomingToggles = togglesPresent ? safeToBoolMap(rawToggles) : null;

        Map<String, Boolean> mergedToggles = togglesPresent
                ? (incomingToggles == null ? Map.of() : incomingToggles)
                : (old.getToolToggles() == null ? Map.of() : old.getToolToggles());

        RuntimeConfig merged = RuntimeConfig.builder()
                .profile(coalesce(getString(body, "profile"), old.getProfile()))
                .toolsMaxLoops(coalesce(getInt(body, "toolsMaxLoops"), old.getToolsMaxLoops()))
                .memoryMaxMessages(coalesce(getInt(body, "memoryMaxMessages"), old.getMemoryMaxMessages()))
                .toolToggles(mergedToggles)
                .build();

        store.save(merged);
        cfgSvc.update(merged);
        return Map.of("ok", true);
    }

    @Operation(summary = "修改配置（全量替换：未传字段会被清空）")
    @PutMapping(value = "/config/replace", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> replace(@RequestBody RuntimeConfig cfg) throws Exception {
        RuntimeConfig before = cfgSvc.view();

        store.save(cfg);
        cfgSvc.update(cfg);

        logToggleDiff(before.getToolToggles(), cfg.getToolToggles());
        return Map.of("ok", true);
    }

    @Operation(summary = "重新加载")
    @PostMapping("/reload")
    public Map<String, Object> reload() {
        cfgSvc.update(cfgSvc.view());
        log.info("[ADMIN][POST]/reload triggered");
        return Map.of("ok", true);
    }

    // ===== helpers =====

    private static String getString(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static Integer getInt(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        return (v instanceof Number) ? ((Number) v).intValue() : Integer.valueOf(String.valueOf(v));
    }

    private static Map<String, Boolean> safeToBoolMap(Map<String, Object> src) {
        if (src == null || src.isEmpty()) return Map.of();
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (var e : src.entrySet()) {
            if (e.getKey() == null) continue;
            Object v = e.getValue();
            boolean b = (v instanceof Boolean) ? (Boolean) v : Boolean.parseBoolean(String.valueOf(v));
            out.put(e.getKey(), b);
        }
        return out;
    }

    private static <T> T coalesce(T v, T fallback) {
        return v != null ? v : fallback;
    }

    private void logToggleDiff(Map<String, Boolean> before, Map<String, Boolean> after) {
        Set<String> offBefore = (before == null) ? Set.of()
                : before.entrySet().stream()
                .filter(e -> Boolean.FALSE.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Set<String> offAfter = (after == null) ? Set.of()
                : after.entrySet().stream()
                .filter(e -> Boolean.FALSE.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Set<String> turnedOff = new LinkedHashSet<>(offAfter);
        turnedOff.removeAll(offBefore);

        Set<String> restoredOn = new LinkedHashSet<>(offBefore);
        restoredOn.removeAll(offAfter);

        log.info("[TOGGLE] disabled+= {} | disabled-= {} | nowDisabled={}", turnedOff, restoredOn, offAfter);
    }
}

