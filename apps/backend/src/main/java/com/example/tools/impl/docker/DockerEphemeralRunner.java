package com.example.tools.impl.docker;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
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
        if (Files.exists(py) || Files.exists(pyWin)) return; // 已存在就不重建

        List<String> cmd = baseRun(userRoot, false); // pip/venv 阶段允许联网
        cmd.add(props.dockerImage);
        cmd.add("python"); cmd.add("-X"); cmd.add("utf8");
        cmd.add("-m"); cmd.add("venv");
        cmd.add("--system-site-packages");       // ✅ 加这一行
        cmd.add("/ws/.venv");

        ExecResult r = runCapture(cmd, Duration.ofMinutes(2));
        if (r.exitCode() != 0) throw new RuntimeException("create venv failed: " + r.stderr());
    }

    /** 安装 pip 包（联网，先检查是否已安装） */
    public void pipInstall(Path userRoot, Collection<String> pkgs) {
        if (pkgs == null || pkgs.isEmpty()) return;

        // 1) 先过滤出“缺失的包”
        List<String> toInstall = new ArrayList<>();
        for (String spec : pkgs) {
            if (spec == null || spec.isBlank()) continue;

            String name = extractPipName(spec);
            if (name == null || name.isBlank()) {
                // 解析不了名字就交给 pip 自己处理
                toInstall.add(spec);
                continue;
            }

            try {
                // 虽然 baseRun(false) 允许联网，但 pip show 其实不访问网络
                List<String> cmd = baseRun(userRoot, false);
                cmd.add(props.dockerImage);
                cmd.add("/ws/.venv/bin/python"); cmd.add("-X"); cmd.add("utf8");
                cmd.add("-m"); cmd.add("pip"); cmd.add("show"); cmd.add(name);

                ExecResult r = runCapture(cmd, Duration.ofSeconds(10));
                if (r.exitCode() != 0) {
                    toInstall.add(spec);
                } else {
                    log.debug("pip package {} already installed in venv under {}", spec, userRoot);
                }
            } catch (Exception e) {
                log.warn("pip show {} failed for {}, will install anyway", spec, userRoot, e);
                toInstall.add(spec);
            }
        }

        if (toInstall.isEmpty()) {
            return; // 都已经装过了
        }

        // 2) 只对缺失的包执行 pip install
        List<String> cmd = baseRun(userRoot, false); // 允许联网
        cmd.add(props.dockerImage);
        cmd.add("/ws/.venv/bin/python"); cmd.add("-X"); cmd.add("utf8");
        cmd.add("-m"); cmd.add("pip"); cmd.add("install"); cmd.add("--no-cache-dir");
        cmd.addAll(toInstall);
        ExecResult r = runCapture(cmd, Duration.ofMinutes(5));
        if (r.exitCode() != 0) throw new RuntimeException("pip install failed: " + r.stderr());
    }

    /** 与上面保持同样逻辑，可以复制一份 */
    private static String extractPipName(String spec) {
        if (spec == null) return null;
        String s = spec.trim();
        if (s.isEmpty()) return s;

        int cut = s.length();
        String seps = " <>=!~[";
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || seps.indexOf(c) >= 0) {
                cut = i;
                break;
            }
        }
        s = s.substring(0, cut);
        if (s.startsWith("'") || s.startsWith("\"")) s = s.substring(1);
        if (s.endsWith("'") || s.endsWith("\"")) s = s.substring(0, s.length() - 1);
        return s;
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

        String httpProxy = firstNonBlank(
                System.getenv("HTTP_PROXY"),
                System.getenv("http_proxy")
        );
        httpProxy = adaptProxyForDocker(httpProxy);
        if (httpProxy != null && !httpProxy.isBlank()) {
            cmd.add("-e"); cmd.add("HTTP_PROXY=" + httpProxy);
            cmd.add("-e"); cmd.add("http_proxy=" + httpProxy);
        }

        String httpsProxy = firstNonBlank(
                System.getenv("HTTPS_PROXY"),
                System.getenv("https_proxy")
        );
        httpsProxy = adaptProxyForDocker(httpsProxy);
        if (httpsProxy != null && !httpsProxy.isBlank()) {
            cmd.add("-e"); cmd.add("HTTPS_PROXY=" + httpsProxy);
            cmd.add("-e"); cmd.add("https_proxy=" + httpsProxy);
        }

        String noProxy = firstNonBlank(
                System.getenv("NO_PROXY"),
                System.getenv("no_proxy")
        );
        // no_proxy 里一般就是域名/IP 列表，不用改 host，直接传
        if (noProxy != null && !noProxy.isBlank()) {
            cmd.add("-e"); cmd.add("NO_PROXY=" + noProxy);
            cmd.add("-e"); cmd.add("no_proxy=" + noProxy);
        }


        if (denyNet) {
            cmd.add("--network");
            cmd.add("none");
        }
        // 默认 -w 由调用方决定
        return cmd;
    }

    // 小工具方法，放在类里即可
    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static String adaptProxyForDocker(String proxy) {
        if (proxy == null || proxy.isBlank()) return proxy;
        try {
            // 确保有 scheme，方便用 URI 解析
            String raw = proxy;
            String withScheme = raw.contains("://") ? raw : "http://" + raw;
            URI uri = new URI(withScheme);
            String host = uri.getHost();
            if (host == null) return proxy;

            if (!"127.0.0.1".equals(host) && !"localhost".equalsIgnoreCase(host)) {
                // 不是本机地址，就不改
                return proxy;
            }

            URI newUri = new URI(
                    uri.getScheme(),
                    uri.getUserInfo(),
                    "host.docker.internal",      // ✅ 替换为宿主机域名
                    uri.getPort(),
                    uri.getPath(),
                    uri.getQuery(),
                    uri.getFragment()
            );
            String result = newUri.toString();
            // 如果原来没有 scheme，就去掉我们临时加的 "http://"
            if (!raw.contains("://") && result.startsWith("http://")) {
                return result.substring("http://".length());
            }
            return result;
        } catch (URISyntaxException e) {
            // 解析失败就用简单替换兜底
            return proxy
                    .replace("127.0.0.1", "host.docker.internal")
                    .replace("localhost", "host.docker.internal");
        }
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
