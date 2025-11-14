package com.example.tools.impl.docker;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * 无长驻容器的临时执行器：
 * - 每次调用使用 `docker run --rm`
 * - 仍挂载宿主机用户目录: {workspaceRoot}/user-{uhash} → /ws
 * - venv 固定 /ws/.venv（持久在宿主机上）
 * - pip 时允许联网；执行时可选 --network none
 */
@Slf4j
public class DockerEphemeralRunner {

    public static final class Props {
        public String dockerImage = "python:3.11-slim";
        public String workspaceRoot;
        public String dockerUser = "65534:65534";
        public boolean readOnlyRoot = true;
        public String cpus = "1.0";
        public String memory = "1g";
        public String extraRunArgs = "--pids-limit 256 --tmpfs /tmp --tmpfs /var/tmp --security-opt no-new-privileges";
        public boolean denyNetworkAtExec = true;
    }

    public record ExecResult(int exitCode, String stdout, String stderr) {}

    private final Props props;

    public DockerEphemeralRunner(Props props) {
        this.props = props;
    }

    /** 确保 venv 存在（在宿主机 /ws 映射下创建） */
    public void ensureVenv(Path userRoot) {
        Path py = userRoot.resolve(".venv").resolve("bin").resolve("python");
        Path pyWin = userRoot.resolve(".venv").resolve("Scripts").resolve("python.exe");
        if (Files.exists(py) || Files.exists(pyWin)) return; // 已存在
        List<String> cmd = baseRun(userRoot, false); // pip/venv 阶段允许联网
        cmd.add(props.dockerImage);
        cmd.add("python"); cmd.add("-X"); cmd.add("utf8"); cmd.add("-m"); cmd.add("venv"); cmd.add("/ws/.venv");
        ExecResult r = runCapture(cmd, Duration.ofMinutes(2));
        if (r.exitCode() != 0) throw new RuntimeException("create venv failed: " + r.stderr());
    }

    /** 安装 pip 包（联网） */
    public void pipInstall(Path userRoot, Collection<String> pkgs) {
        if (pkgs == null || pkgs.isEmpty()) return;
        List<String> cmd = baseRun(userRoot, false); // 允许联网
        cmd.add(props.dockerImage);
        cmd.add("/ws/.venv/bin/python"); cmd.add("-X"); cmd.add("utf8");
        cmd.add("-m"); cmd.add("pip"); cmd.add("install"); cmd.add("--no-cache-dir");
        cmd.addAll(pkgs);
        ExecResult r = runCapture(cmd, Duration.ofMinutes(5));
        if (r.exitCode() != 0) throw new RuntimeException("pip install failed: " + r.stderr());
    }

    /** 执行 python（可禁网） */
    public ExecResult execPython(Path userRoot, Path convDir, long timeoutMs) {
        List<String> cmd = baseRun(userRoot, props.denyNetworkAtExec);
        cmd.add("-w"); cmd.add("/ws" + subPath(userRoot, convDir)); // 会话目录为工作目录
        cmd.add(props.dockerImage);
        cmd.add("/ws/.venv/bin/python"); cmd.add("-X"); cmd.add("utf8"); cmd.add("-u"); cmd.add("-B"); cmd.add("main.py");
        return runCapture(cmd, Duration.ofMillis(timeoutMs));
    }

    // ===== helpers =====

    private List<String> baseRun(Path userRoot, boolean denyNet) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker"); cmd.add("run"); cmd.add("--rm");
        cmd.add("-v"); cmd.add(userRoot.toAbsolutePath().toString() + ":/ws");
        cmd.add("--cpus"); cmd.add(props.cpus);
        cmd.add("--memory"); cmd.add(props.memory);
        if (props.readOnlyRoot) cmd.add("--read-only");
        if (props.dockerUser != null && !props.dockerUser.isBlank()) {
            cmd.add("-u"); cmd.add(props.dockerUser);
        }
        if (props.extraRunArgs != null && !props.extraRunArgs.isBlank()) {
            String[] extra = props.extraRunArgs.trim().split("\\s+");
            for (String t : extra) if (!t.isBlank()) cmd.add(t);
        }
        if (denyNet) { cmd.add("--network"); cmd.add("none"); }
        // 默认 -w 由调用方决定
        return cmd;
    }

    private static String subPath(Path base, Path sub) {
        Path rel = base.relativize(sub);
        String p = "/" + rel.toString().replace(File.separatorChar, '/');
        return p.startsWith("//") ? p.substring(1) : p;
    }

    private ExecResult runCapture(List<String> cmd, Duration timeout) {
        log.debug("docker$ {}", String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process p = null;
        StringBuilder outSb = new StringBuilder();
        StringBuilder errSb = new StringBuilder();
        try {
            p = pb.start();
            Process finalP = p;
            Thread tOut = new Thread(() -> readAll(finalP.getInputStream(), outSb), "docker-stdout");
            Process finalP1 = p;
            Thread tErr = new Thread(() -> readAll(finalP1.getErrorStream(), errSb), "docker-stderr");
            tOut.setDaemon(true); tErr.setDaemon(true);
            tOut.start(); tErr.start();

            boolean ok = p.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!ok) {
                p.destroyForcibly();
                try { tOut.join(200); } catch (InterruptedException ignore) {}
                try { tErr.join(200); } catch (InterruptedException ignore) {}
                return new ExecResult(124, outSb.toString(), "timeout");
            }
            try { tOut.join(200); } catch (InterruptedException ignore) {}
            try { tErr.join(200); } catch (InterruptedException ignore) {}
            return new ExecResult(p.exitValue(), outSb.toString(), errSb.toString());
        } catch (Exception e) {
            return new ExecResult(1, outSb.toString(), e.toString());
        } finally {
            if (p != null) p.destroyForcibly();
        }
    }

    private static void readAll(InputStream in, StringBuilder out) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) out.append(line).append('\n');
        } catch (IOException ignore) {}
    }
}
