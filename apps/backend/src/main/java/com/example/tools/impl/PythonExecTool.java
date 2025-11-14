package com.example.tools.impl;

import com.example.ai.tools.AiToolComponent;
import com.example.api.dto.ToolResult;
import com.example.storage.StorageService;
import com.example.tools.AiTool;
import com.example.tools.impl.docker.DockerEphemeralRunner;
import com.example.tools.impl.docker.PythonContainerPool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Python 执行工具（无外部显式文件上传参数）：
 * - per-user-container=true  → 每用户长驻容器
 * - per-user-container=false → 临时容器（每次 docker run --rm）
 *
 * 执行流程：
 * 1) 写入 main.py 与输入文件
 * 2) 对会话目录做 baseline 快照
 * 3) 运行 Python
 * 4) 扫描 after 快照，取新增文件 = after - before
 * 5) 逐个新增文件上传 MinIO；小文本按 return_files 内联
 * 6) 返回 {stdout, stderr, exit_code, files(内联), generated_files(含key/size/url)}
 */
@Slf4j
@AiToolComponent
@RequiredArgsConstructor
public class PythonExecTool implements AiTool {

    private final PythonContainerPool containerPool;
    private final StorageService storageService; // 注入你已有的 MinioStorageService

    @Value("${ai.tools.python.workspace-root:/var/javelin/workspaces/pyexec}")
    private String workspaceRoot;
    @Value("${ai.tools.python.docker-image:python:3.11-slim}")
    private String dockerImage;
    @Value("${ai.tools.python.docker.user:65534:65534}")
    private String dockerUser;
    @Value("${ai.tools.python.docker.read-only-root:true}")
    private boolean readOnlyRoot;
    @Value("${ai.tools.python.docker.cpus:1.0}")
    private String dockerCpus;
    @Value("${ai.tools.python.docker.memory:1g}")
    private String dockerMemory;
    @Value("${ai.tools.python.docker.extra-create-args:--pids-limit 256 --tmpfs /tmp --tmpfs /var/tmp --security-opt no-new-privileges}")
    private String extraCreateArgs;
    @Value("${ai.tools.python.deny-network-after-setup:true}")
    private boolean denyNetworkAfterSetup;
    @Value("${ai.tools.python.idle-ttl-minutes:60}")
    private int idleTtlMinutes;
    @Value("${ai.tools.python.allow-pip:true}")
    private boolean allowPip;
    @Value("${ai.tools.python.per-user-container:true}")
    private boolean perUserContainer;

    // 小文本内联阈值（1MB）
    private static final long INLINE_LIMIT = 1L * 1024 * 1024;

    @Override
    public String name() { return "python_exec"; }

    @Override
    public String description() {
        return "在 Docker 中执行 Python；自动检测本次新增文件并上传到 MinIO，返回文件下载信息。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> pathContent = new HashMap<>();
        pathContent.put("type", "object");
        pathContent.put("required", Collections.singletonList("path"));
        Map<String, Object> pcProps = new HashMap<>();
        pcProps.put("path", Map.of("type","string"));
        pcProps.put("content", Map.of("type","string"));
        pathContent.put("properties", pcProps);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("user_id", Map.of("type","string","description","用户ID"));
        props.put("conversation_id", Map.of("type","string","description","对话ID"));
        props.put("step_id", Map.of("type","string","description","可选：步骤ID"));
        props.put("code", Map.of("type","string","description","Python 源码"));
        props.put("pip", Map.of("type","array","items", Map.of("type","string"), "description","需要安装的 pip 包"));
        props.put("files", Map.of("type","array","items", pathContent, "description","执行前写入的文件"));
        props.put("return_files", Map.of("type","array","items", Map.of("type","string"), "description","执行后要内联回读的小文本相对路径"));
        props.put("timeout_ms", Map.of("type","integer","minimum",1,"description","超时（毫秒）"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type","object");
        schema.put("required", Collections.singletonList("code"));
        schema.put("properties", props);
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String userId = asString(
                args.get("user_id"),
                asString(args.get("userId"), "anonymous")
        );
        String convId = asString(
                args.get("conversation_id"),
                asString(args.get("conversationId"), UUID.randomUUID().toString())
        );

        String stepId = asString(args.get("step_id"), null);
        String code   = asString(args.get("code"), "");
        @SuppressWarnings("unchecked")
        List<String> pipPkgs = (List<String>) args.getOrDefault("pip", Collections.emptyList());
        int timeoutMs = ((Number) args.getOrDefault("timeout_ms", 30000)).intValue();
        @SuppressWarnings("unchecked")
        List<Map<String,String>> files = (List<Map<String,String>>) args.getOrDefault("files", Collections.emptyList());
        @SuppressWarnings("unchecked")
        List<String> returnFiles = (List<String>) args.getOrDefault("return_files", Collections.emptyList());

        if (stepId != null) MDC.put("stepId", stepId);
        MDC.put("userId", userId);
        MDC.put("conversationId", convId);

        try {
            // 目录结构：{workspaceRoot}/user-{uhash}/conv-{chash}
            String uhash = shortHash(userId);
            String chash = shortHash(convId);
            Path userRoot = Paths.get(workspaceRoot, "user-" + uhash);
            Path convDir = userRoot.resolve("conv-" + chash);
            Files.createDirectories(convDir);

            // 1) 写入 main.py 与输入文件
            Path main = convDir.resolve("main.py");
            Files.writeString(main, code, StandardCharsets.UTF_8);
            for (Map<String,String> f : files) {
                String rel = Objects.requireNonNull(f.get("path"), "file.path required");
                Path p = convDir.resolve(rel);
                if (p.getParent() != null) Files.createDirectories(p.getParent());
                Files.writeString(p, Objects.requireNonNullElse(f.get("content"), ""), StandardCharsets.UTF_8);
            }

            // 2) baseline 快照（写完输入之后）
            Set<String> beforeFiles = scanFilesSnapshot(convDir);

            // 3) 执行 Python
            String stdout, stderr;
            int exit;
            if (perUserContainer) {
                containerPool.applyConfig(
                        dockerImage, workspaceRoot, dockerUser, readOnlyRoot,
                        dockerCpus, dockerMemory, extraCreateArgs,
                        denyNetworkAfterSetup, idleTtlMinutes
                );
                if (!pipPkgs.isEmpty()) {
                    if (!allowPip) return ToolResult.error(null, name(), "pip disabled by server");
                    containerPool.ensurePip(userId, pipPkgs, Duration.ofMinutes(5));
                } else {
                    containerPool.ensureContainer(userId);
                }
                var r = containerPool.exec(
                        userId, convDir,
                        Arrays.asList("/ws/.venv/bin/python","-X","utf8","-u","-B","main.py"),
                        Duration.ofMillis(timeoutMs)
                );
                stdout = r.stdout(); stderr = r.stderr(); exit = r.exitCode();
            } else {
                DockerEphemeralRunner.Props ep = new DockerEphemeralRunner.Props();
                ep.dockerImage = dockerImage;
                ep.workspaceRoot = workspaceRoot;
                ep.dockerUser = dockerUser;
                ep.readOnlyRoot = readOnlyRoot;
                ep.cpus = dockerCpus;
                ep.memory = dockerMemory;
                ep.extraRunArgs = extraCreateArgs;
                ep.denyNetworkAtExec = denyNetworkAfterSetup;

                DockerEphemeralRunner runner = new DockerEphemeralRunner(ep);
                runner.ensureVenv(userRoot);
                if (!pipPkgs.isEmpty()) {
                    if (!allowPip) return ToolResult.error(null, name(), "pip disabled by server");
                    runner.pipInstall(userRoot, pipPkgs);
                }
                var r = runner.execPython(userRoot, convDir, timeoutMs);
                stdout = r.stdout(); stderr = r.stderr(); exit = r.exitCode();
            }

            // 4) after 快照 & 计算新增文件
            Set<String> afterFiles = scanFilesSnapshot(convDir);
            Set<String> newFiles = new HashSet<>(afterFiles);
            newFiles.removeAll(beforeFiles);

            // 5) 内联小文本（仅限 return_files），并上传新增文件到 MinIO
            Map<String, String> inlineFiles = new LinkedHashMap<>();
            for (String rp : returnFiles) {
                tryReadSmallText(convDir, rp, inlineFiles);
            }

            Map<String, Object> generatedFiles = new LinkedHashMap<>();
            if (!newFiles.isEmpty()) {
                String bucket = storageService.getDefaultBucket();
                // 确保桶存在（阻塞等一下即可）
                storageService.ensureBucket(bucket).block(Duration.ofSeconds(10));

                for (String rel : newFiles) {
                    Path p = convDir.resolve(rel);
                    if (!Files.exists(p) || !Files.isRegularFile(p)) continue;
                    long size = Files.size(p);

                    String objectKey = storageService.buildObjectKey(userId, convId, rel.replace('\\','/'));

                    try {
                        // 上传
                        storageService.uploadFile(bucket, objectKey, p).block(Duration.ofMinutes(2));
                        // 预签名 1 小时
                        String url = storageService.presignGet(bucket, objectKey, Duration.ofHours(1))
                                .block(Duration.ofSeconds(5));

                        Map<String, Object> meta = new LinkedHashMap<>();
                        meta.put("key", objectKey);
                        meta.put("size", size);
                        meta.put("url", url);
                        generatedFiles.put(rel, meta);
                    } catch (Exception e) {
                        log.warn("upload/presign failed for {}", objectKey, e);
                    }
                }
            }

            // 6) 组装并返回
            Map<String,Object> data = new LinkedHashMap<>();
            data.put("stdout", stdout);
            data.put("stderr", stderr);
            data.put("exit_code", exit);
            if (!inlineFiles.isEmpty()) data.put("files", inlineFiles);
            if (!generatedFiles.isEmpty()) data.put("generated_files", generatedFiles);

            return (exit == 0)
                    ? ToolResult.success(null, name(), false, data)
                    : ToolResult.error(null, name(), "python exit " + exit + "\n" + stderr);

        } catch (Exception e) {
            log.error("python_exec failed", e);
            return ToolResult.error(null, name(), e.getMessage());
        } finally {
            if (stepId != null) MDC.remove("stepId");
            MDC.remove("userId"); MDC.remove("conversationId");
        }
    }

    // ===== helpers =====

    private static void tryReadSmallText(Path base, String rel, Map<String,String> out) {
        try {
            Path p = base.resolve(rel);
            if (!Files.exists(p) || !Files.isRegularFile(p)) return;
            long size = Files.size(p);
            if (size <= INLINE_LIMIT) {
                out.put(rel, Files.readString(p));
            }
        } catch (Exception ignore) {}
    }

    private Set<String> scanFilesSnapshot(Path root) {
        Set<String> result = new HashSet<>();
        if (!Files.exists(root)) return result;
        try {
            Files.walk(root)
                    .filter(Files::isRegularFile)
                    .forEach(p -> {
                        String rel = root.relativize(p).toString().replace('\\','/');
                        result.add(rel);
                    });
        } catch (Exception e) {
            log.warn("scanFilesSnapshot failed for {}", root, e);
        }
        return result;
    }

    private static String asString(Object v, String def) { return v == null ? def : String.valueOf(v); }

    private static String shortHash(String s) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(Objects.hashCode(s));
        }
    }
}
