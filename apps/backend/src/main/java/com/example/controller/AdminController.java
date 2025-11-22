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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
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

    @Operation(summary = "查看运行时配置（runtime + effective）")
    @GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> get() {
        RuntimeConfig rc = cfgSvc.view();

        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("profile", rc.getProfile());
        runtime.put("toolsMaxLoops", rc.getToolsMaxLoops());
        runtime.put("toolToggles", rc.getToolToggles());
        runtime.put("memoryMaxMessages", rc.getMemoryMaxMessages());
        runtime.put("profiles", rc.getProfiles());

        Map<String, Object> effective = new LinkedHashMap<>();
        effective.put("profile", effectiveProps.profileOr(multiProps.getPrimaryModel()));
        effective.put("toolsMaxLoops", effectiveProps.toolsMaxLoops());
        effective.put("memoryMaxMessages", effectiveProps.memoryMaxMessages());

        List<String> serverToolNames = toolRegistry.allTools().stream().map(AiTool::name).toList();
        Set<String> availableTools = new LinkedHashSet<>(serverToolNames);
        if (rc.getToolToggles() != null) {
            availableTools.addAll(rc.getToolToggles().keySet());
        }

        // 静态 + 运行时 profile 列表，primary 置顶
        List<String> availableProfiles = new LinkedList<>(multiProps.getModels().keySet());
        Map<String, RuntimeConfig.ModelProfileDto> runtimeProfiles = rc.getProfiles() != null ? rc.getProfiles() : Map.of();
        runtimeProfiles.keySet().forEach(p -> { if (!availableProfiles.contains(p)) availableProfiles.add(p); });
        String primary = multiProps.getPrimaryModel();
        if (primary != null && availableProfiles.remove(primary)) {
            availableProfiles.add(0, primary);
        }

        log.debug("[ADMIN][GET]/config runtime profile={} loops={} memWin={} togglesKeys={}",
                runtime.get("profile"), runtime.get("toolsMaxLoops"), runtime.get("memoryMaxMessages"), runtime.get("toolToggles"));
        log.debug("[ADMIN][GET]/config effective profile={} loops={} memWin={} togglesKeys={}",
                effective.get("profile"), effective.get("toolsMaxLoops"), effective.get("memoryMaxMessages"), runtime.get("toolToggles"));

        return Map.of(
                "runtime", runtime,
                "effective", effective,
                "availableTools", List.copyOf(availableTools),
                "availableProfiles", List.copyOf(availableProfiles)
        );
    }

    @Operation(summary = "修改配置（合并语义：仅更新传入字段）")
    @PutMapping(value = "/config", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> put(@RequestBody Map<String, Object> body) throws Exception {
        RuntimeConfig old = cfgSvc.view();

        boolean togglesPresent = body.containsKey("toolToggles");
        Map<String, Boolean> incomingToggles = togglesPresent
                ? safeToBoolMap(asMap(body.get("toolToggles")))
                : null;
        Map<String, RuntimeConfig.ModelProfileDto> incomingProfiles = null;
        if (body.containsKey("profiles")) {
            incomingProfiles = toProfileMap(asMap(body.get("profiles")));
        }

        Map<String, Boolean> mergedToggles = togglesPresent
                ? (incomingToggles == null ? Map.of() : incomingToggles)
                : (old.getToolToggles() == null ? Map.of() : old.getToolToggles());

        Map<String, RuntimeConfig.ModelProfileDto> mergedProfiles =
                incomingProfiles != null ? incomingProfiles : old.getProfiles();

        // profile: 如果请求中显式包含 key，则使用该值（空/空串视为 null）；否则沿用旧值
        String profile = body.containsKey("profile") ? normalizeString(body.get("profile")) : old.getProfile();
        Integer loops = body.containsKey("toolsMaxLoops") ? getInt(body, "toolsMaxLoops") : old.getToolsMaxLoops();
        Integer memWin = body.containsKey("memoryMaxMessages") ? getInt(body, "memoryMaxMessages") : old.getMemoryMaxMessages();

        RuntimeConfig merged = RuntimeConfig.builder()
                .profile(profile)
                .toolsMaxLoops(loops)
                .memoryMaxMessages(memWin)
                .toolToggles(mergedToggles)
                .profiles(mergedProfiles)
                .build();

        store.save(merged);
        cfgSvc.update(merged);
        logToggleDiff(old.getToolToggles(), merged.getToolToggles());
        return Map.of("ok", true);
    }

    @Operation(summary = "新增/更新运行时模型 profile")
    @PutMapping(value = "/profile/{name}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> upsertProfile(@PathVariable("name") String name,
                                             @RequestBody RuntimeConfig.ModelProfileDto profile) throws Exception {
        RuntimeConfig current = cfgSvc.view();
        Map<String, RuntimeConfig.ModelProfileDto> nextProfiles = new LinkedHashMap<>(
                current.getProfiles() != null ? current.getProfiles() : Map.of());
        nextProfiles.put(name, profile);

        RuntimeConfig merged = RuntimeConfig.builder()
                .profile(current.getProfile())
                .toolsMaxLoops(current.getToolsMaxLoops())
                .memoryMaxMessages(current.getMemoryMaxMessages())
                .toolToggles(current.getToolToggles())
                .profiles(nextProfiles)
                .build();

        store.save(merged);
        cfgSvc.update(merged);
        log.info("[ADMIN][PUT]/profile name={} provider={} modelId={} (runtime)", name, profile.getProvider(), profile.getModelId());
        return Map.of("ok", true);
    }

    @Operation(summary = "删除运行时模型 profile")
    @DeleteMapping("/profile/{name}")
    public Map<String, Object> deleteProfile(@PathVariable("name") String name) throws Exception {
        RuntimeConfig current = cfgSvc.view();
        Map<String, RuntimeConfig.ModelProfileDto> nextProfiles = new LinkedHashMap<>(
                current.getProfiles() != null ? current.getProfiles() : Map.of());
        RuntimeConfig.ModelProfileDto removed = nextProfiles.remove(name);
        RuntimeConfig merged = current.toBuilder().profiles(nextProfiles).build();
        store.save(merged);
        cfgSvc.update(merged);
        log.info("[ADMIN][DELETE]/profile name={} removed?={}", name, removed != null);
        return Map.of("ok", true, "removed", removed != null);
    }

    @Operation(summary = "全量替换配置（未传字段会被清空）")
    @PutMapping(value = "/config/replace", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> replace(@RequestBody RuntimeConfig cfg) throws Exception {
        RuntimeConfig before = cfgSvc.view();

        store.save(cfg);
        cfgSvc.update(cfg);

        logToggleDiff(before.getToolToggles(), cfg.getToolToggles());
        return Map.of("ok", true);
    }

    @Operation(summary = "重新加载配置（广播 RuntimeConfigReloadedEvent）")
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

    private static Double getDouble(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        return (v instanceof Number) ? ((Number) v).doubleValue() : Double.valueOf(String.valueOf(v));
    }

    private static String normalizeString(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v);
        return s == null || s.isBlank() ? null : s;
    }

    private static Boolean getBool(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private static Map<String, RuntimeConfig.ModelProfileDto> toProfileMap(Map<String, Object> src) {
        if (src == null || src.isEmpty()) return Map.of();
        Map<String, RuntimeConfig.ModelProfileDto> out = new LinkedHashMap<>();
        for (var e : src.entrySet()) {
            String key = e.getKey();
            if (key == null || e.getValue() == null) continue;
            Map<String, Object> v = asMap(e.getValue());
            if (v == null) continue;
            RuntimeConfig.ModelProfileDto dto = RuntimeConfig.ModelProfileDto.builder()
                    .provider(getString(v, "provider"))
                    .baseUrl(getString(v, "baseUrl"))
                    .apiKey(getString(v, "apiKey"))
                    .modelId(getString(v, "modelId"))
                    .temperature(getDouble(v, "temperature"))
                    .maxTokens(getInt(v, "maxTokens"))
                    .timeoutMs(getInt(v, "timeoutMs"))
                    .toolsEnabled(getBool(v, "toolsEnabled"))
                    .toolContextRenderMode(getString(v, "toolContextRenderMode"))
                    .build();
            out.put(key, dto);
        }
        return out;
    }

    private static Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(k == null ? null : k.toString(), v));
            return out;
        }
        return null;
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
