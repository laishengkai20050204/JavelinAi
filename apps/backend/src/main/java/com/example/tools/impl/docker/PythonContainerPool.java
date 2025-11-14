package com.example.tools.impl.docker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * 每用户一个长驻容器的 Python 执行池：
 * - 宿主工作区：{workspaceRoot}/user-{hash}
 * - 容器挂载 /ws （-v），工作目录 -w /ws
 * - venv 固定 /ws/.venv
 * - denyNetworkAfterSetup=true 时，容器初始断网；pip 时临时接回 bridge
 *
 * 注意：本实现避免使用会触发“lambda 变量需 final”的写法，尽量使用显式类/显式更新。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PythonContainerPool {

    // ---- 配置 ----
    public static class Props {
        public String dockerImage = "python:3.11-slim";
        public String workspaceRoot;
        public String dockerUser = "65534:65534";
        public boolean readOnlyRoot = true;
        public String cpus = "1.0";
        public String memory = "1g";
        public String extraCreateArgs = "--pids-limit 256 --tmpfs /tmp --tmpfs /var/tmp --security-opt no-new-privileges";
        public boolean denyNetworkAfterSetup = true;
        public int idleTtlMinutes = 60;
    }

    public static final class UserContainer {
        private final String userId;
        private final String name;
        private final Path workspaceHost;
        private final Instant lastUsed;
        private final boolean networkDisconnected;

        public UserContainer(String userId, String name, Path workspaceHost, Instant lastUsed, boolean networkDisconnected) {
            this.userId = userId;
            this.name = name;
            this.workspaceHost = workspaceHost;
            this.lastUsed = lastUsed;
            this.networkDisconnected = networkDisconnected;
        }
        public String userId() { return userId; }
        public String name() { return name; }
        public Path workspaceHost() { return workspaceHost; }
        public Instant lastUsed() { return lastUsed; }
        public boolean networkDisconnected() { return networkDisconnected; }
    }

    // 使用 record（JDK 16+），公开 accessor，避免私有字段访问问题
    public record ExecResult(int exitCode, String stdout, String stderr) {}

    private final Map<String, UserContainer> pool = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService gc = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "py-container-gc");
            t.setDaemon(true);
            return t;
        }
    });

    private final Props props = new Props();

    {   // 定时清理
        gc.scheduleAtFixedRate(new Runnable() {
            @Override public void run() { gcIdle(); }
        }, 5, 5, TimeUnit.MINUTES);
    }

    /** 直接下发配置，避免 lambda */
    public void applyConfig(String dockerImage,
                            String workspaceRoot,
                            String dockerUser,
                            boolean readOnlyRoot,
                            String cpus,
                            String memory,
                            String extraCreateArgs,
                            boolean denyNetworkAfterSetup,
                            int idleTtlMinutes) {
        synchronized (props) {
            if (dockerImage != null) props.dockerImage = dockerImage;
            if (workspaceRoot != null) props.workspaceRoot = workspaceRoot;
            if (dockerUser != null) props.dockerUser = dockerUser;
            props.readOnlyRoot = readOnlyRoot;
            if (cpus != null) props.cpus = cpus;
            if (memory != null) props.memory = memory;
            if (extraCreateArgs != null) props.extraCreateArgs = extraCreateArgs;
            props.denyNetworkAfterSetup = denyNetworkAfterSetup;
            props.idleTtlMinutes = idleTtlMinutes;
        }
    }

    /** 确保容器存在且运行 */
    public UserContainer ensureContainer(String userId) {
        Objects.requireNonNull(userId, "userId");
        locks.putIfAbsent(userId, new Object());
        synchronized (locks.get(userId)) {
            UserContainer uc = pool.get(userId);
            if (uc != null && isRunning(uc.name())) {
                UserContainer updated = new UserContainer(uc.userId(), uc.name(), uc.workspaceHost(), Instant.now(), uc.networkDisconnected());
                pool.put(userId, updated);
                return updated;
            }
            String uhash = shortHash(userId);
            String name = "javelin-py-" + uhash;
            Path userWs = Paths.get(resolveWorkspaceRoot().toString(), "user-" + uhash);
            try { Files.createDirectories(userWs); } catch (IOException e) { throw new RuntimeException(e); }

            if (exists(name)) run(Arrays.asList("docker","rm","-f",name), Duration.ofSeconds(15));

            List<String> create = new ArrayList<>();
            create.addAll(Arrays.asList("docker","create",
                    "--name", name,
                    "-v", userWs.toAbsolutePath().toString() + ":/ws",
                    "-w", "/ws",
                    "--label","javelin=pyexec",
                    "--label","javelin.userId="+ safeLabel(userId),
                    "--cpus", props.cpus,
                    "--memory", props.memory
            ));
            if (props.readOnlyRoot) create.add("--read-only");
            if (props.dockerUser != null && !props.dockerUser.isBlank()) {
                create.addAll(Arrays.asList("-u", props.dockerUser));
            }
            if (props.extraCreateArgs != null && !props.extraCreateArgs.isBlank()) {
                String[] extra = props.extraCreateArgs.trim().split("\\s+");
                for (String t : extra) if (!t.isBlank()) create.add(t);
            }

// ✅ 从宿主机环境读取代理，并适配为 host.docker.internal
            String httpProxy = firstNonBlank(
                    System.getenv("HTTP_PROXY"),
                    System.getenv("http_proxy")
            );
            httpProxy = adaptProxyForDocker(httpProxy);
            if (httpProxy != null && !httpProxy.isBlank()) {
                create.add("-e"); create.add("HTTP_PROXY=" + httpProxy);
                create.add("-e"); create.add("http_proxy=" + httpProxy);
            }

            String httpsProxy = firstNonBlank(
                    System.getenv("HTTPS_PROXY"),
                    System.getenv("https_proxy")
            );
            httpsProxy = adaptProxyForDocker(httpsProxy);
            if (httpsProxy != null && !httpsProxy.isBlank()) {
                create.add("-e"); create.add("HTTPS_PROXY=" + httpsProxy);
                create.add("-e"); create.add("https_proxy=" + httpsProxy);
            }

            String noProxy = firstNonBlank(
                    System.getenv("NO_PROXY"),
                    System.getenv("no_proxy")
            );
            if (noProxy != null && !noProxy.isBlank()) {
                create.add("-e"); create.add("NO_PROXY=" + noProxy);
                create.add("-e"); create.add("no_proxy=" + noProxy);
            }


            create.add(props.dockerImage);
            create.add("sleep"); create.add("infinity");
            run(create, Duration.ofSeconds(30));

            run(Arrays.asList("docker","start",name), Duration.ofSeconds(20));
            run(Arrays.asList("docker","exec",name,"python","-X","utf8","-m","venv","/ws/.venv"), Duration.ofMinutes(2));

            boolean disconnected = false;
            if (props.denyNetworkAfterSetup) {
                disconnectBridge(name);
                disconnected = true;
            }
            UserContainer created = new UserContainer(userId, name, userWs, Instant.now(), disconnected);
            pool.put(userId, created);
            return created;
        }
    }


    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static String adaptProxyForDocker(String proxy) {
        if (proxy == null || proxy.isBlank()) return proxy;
        try {
            String raw = proxy;
            String withScheme = raw.contains("://") ? raw : "http://" + raw;
            java.net.URI uri = new java.net.URI(withScheme);
            String host = uri.getHost();
            if (host == null) return proxy;
            if (!"127.0.0.1".equals(host) && !"localhost".equalsIgnoreCase(host)) {
                return proxy;
            }
            java.net.URI newUri = new java.net.URI(
                    uri.getScheme(),
                    uri.getUserInfo(),
                    "host.docker.internal",
                    uri.getPort(),
                    uri.getPath(),
                    uri.getQuery(),
                    uri.getFragment()
            );
            String result = newUri.toString();
            if (!raw.contains("://") && result.startsWith("http://")) {
                return result.substring("http://".length());
            }
            return result;
        } catch (Exception e) {
            return proxy
                    .replace("127.0.0.1", "host.docker.internal")
                    .replace("localhost", "host.docker.internal");
        }
    }



    /** 在用户容器中执行命令（工作目录指向 host 子目录的容器内映射） */
    public ExecResult exec(String userId, Path workDirInHost, List<String> argv, Duration timeout) {
        UserContainer uc = ensureContainer(userId);
        try { Files.createDirectories(workDirInHost); } catch (IOException e) { throw new RuntimeException(e); }
        List<String> cmd = new ArrayList<>();
        cmd.addAll(Arrays.asList("docker","exec","-w","/ws"+hostSubPath(uc.workspaceHost(), workDirInHost), uc.name()));
        cmd.addAll(argv);
        ExecResult r = runCapture(cmd, timeout);
        touch(userId);
        return r;
    }

    /** 安装 pip 包（临时接回 bridge，完成后按策略断开） */
    public void ensurePip(String userId, Collection<String> pkgs, Duration timeout) {
        if (pkgs == null || pkgs.isEmpty()) return;
        UserContainer uc = ensureContainer(userId);
        boolean needReconnect = false;
        if (props.denyNetworkAfterSetup && uc.networkDisconnected()) {
            connectBridge(uc.name());
            needReconnect = true;
        }
        List<String> cmd = new ArrayList<>();
        cmd.addAll(Arrays.asList("docker","exec", uc.name(),
                "/ws/.venv/bin/python","-X","utf8","-m","pip","install","--no-cache-dir"));
        cmd.addAll(pkgs);
        ExecResult r = runCapture(cmd, timeout);
        if (r.exitCode() != 0) throw new RuntimeException("pip install failed: " + r.stderr());
        if (needReconnect) {
            disconnectBridge(uc.name());
            markDisconnected(userId, true);
        }
        touch(userId);
    }

    // ---- 内部工具 ----
    private void touch(String userId) {
        UserContainer uc = pool.get(userId);
        if (uc != null) {
            pool.put(userId, new UserContainer(uc.userId(), uc.name(), uc.workspaceHost(), Instant.now(), uc.networkDisconnected()));
        }
    }
    private void markDisconnected(String userId, boolean v) {
        UserContainer uc = pool.get(userId);
        if (uc != null) {
            pool.put(userId, new UserContainer(uc.userId(), uc.name(), uc.workspaceHost(), uc.lastUsed(), v));
        }
    }

    private Path resolveWorkspaceRoot() {
        Path root = (props.workspaceRoot == null || props.workspaceRoot.isBlank())
                ? Paths.get(System.getProperty("java.io.tmpdir"), "pyexec")
                : Paths.get(props.workspaceRoot);
        try { Files.createDirectories(root); } catch (IOException e) { throw new RuntimeException(e); }
        return root;
    }

    private String hostSubPath(Path userRoot, Path sub) {
        Path rel = userRoot.relativize(sub);
        String p = "/" + rel.toString().replace(File.separatorChar, '/');
        return p.startsWith("//") ? p.substring(1) : p;
    }

    private boolean exists(String name) {
        ExecResult r = runCapture(Arrays.asList("docker","ps","-a","--format","{{.Names}}"), Duration.ofSeconds(10));
        String[] lines = r.stdout().split("\\R");
        for (String s : lines) if (s.equals(name)) return true;
        return false;
    }

    private boolean isRunning(String name) {
        ExecResult r = runCapture(Arrays.asList("docker","ps","--format","{{.Names}}"), Duration.ofSeconds(10));
        String[] lines = r.stdout().split("\\R");
        for (String s : lines) if (s.equals(name)) return true;
        return false;
    }

    private void connectBridge(String name) {
        run(Arrays.asList("docker","network","connect","bridge",name), Duration.ofSeconds(10));
    }
    private void disconnectBridge(String name) {
        run(Arrays.asList("docker","network","disconnect","-f","bridge",name), Duration.ofSeconds(10));
    }

    private String safeLabel(String s) { return s.replace(" ", "_"); }

    private static String shortHash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(Objects.hashCode(s));
        }
    }

    private void run(List<String> cmd, Duration timeout) {
        ExecResult r = runCapture(cmd, timeout);
        if (r.exitCode() != 0) {
            throw new RuntimeException("cmd failed: " + String.join(" ", cmd) + " :: " + r.stderr());
        }
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
            Thread tOut = new Thread(new Runnable() {
                @Override public void run() { readAll(finalP.getInputStream(), outSb); }
            }, "docker-stdout");
            Process finalP1 = p;
            Thread tErr = new Thread(new Runnable() {
                @Override public void run() { readAll(finalP1.getErrorStream(), errSb); }
            }, "docker-stderr");
            tOut.setDaemon(true); tErr.setDaemon(true);
            tOut.start(); tErr.start();

            boolean ok = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!ok) {
                p.destroyForcibly();
                try { tOut.join(200); } catch (InterruptedException ignored) {}
                try { tErr.join(200); } catch (InterruptedException ignored) {}
                return new ExecResult(124, outSb.toString(), "timeout");
            }
            try { tOut.join(200); } catch (InterruptedException ignored) {}
            try { tErr.join(200); } catch (InterruptedException ignored) {}
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

    private void gcIdle() {
        try {
            Instant now = Instant.now();
            List<UserContainer> victims = new ArrayList<>();
            for (UserContainer uc : pool.values()) {
                long minutes = Duration.between(uc.lastUsed(), now).toMinutes();
                if (minutes >= props.idleTtlMinutes) victims.add(uc);
            }
            for (UserContainer uc : victims) {
                log.info("GC python container {} (idle)", uc.name());
                run(Arrays.asList("docker","rm","-f", uc.name()), Duration.ofSeconds(15));
                pool.remove(uc.userId());
                locks.remove(uc.userId);
            }
        } catch (Exception e) {
            log.warn("gc idle error", e);
        }
    }
}
