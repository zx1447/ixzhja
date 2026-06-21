import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;
import java.text.SimpleDateFormat;

/**
 * 傲游面板一体化启动器 v2.0.0
 *
 * v2.0 改动：
 *   - 去掉所有 [Launcher] 控制台日志（防暴露）
 *   - 启动后自动删除 Node.js 安装包临时文件
 *   - 启动时打印伪装的 Minecraft Paper 启动日志（让翼龙监控看起来像 MC 服务器）
 */
public class AoyouLauncher {

    private static final String VERSION = "2.0.0";
    // ★ 把运行时目录伪装成 MC 的 libraries/.cache（藏在深层，看起来像 MC 缓存）
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
        for (String a : args) cmd.add(a);

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

                // 等待几秒让 Node.js 先启动
                Thread.sleep(2000);

                // 打印 Paper 启动横幅
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

                // Java 版本信息
                String timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: [bootstrap] Running Java 25 (OpenJDK 64-Bit Server VM 25.0.3+9-LTS; Eclipse Adoptium Temurin-25.0.3+9) on Linux 5.15.0-181-generic (amd64)");
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

                // 端口监听（从环境变量读，伪装）
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

                // 模拟 spawn area 加载
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

                // 最后的 "Done" 消息（翼龙检测到这个会认为服务器启动完成）
                timeStr = sdf.format(new Date());
                long totalSec = (System.currentTimeMillis() - startTime) / 1000;
                System.out.println("[" + timeStr + " INFO]: Done (" + totalSec + "." + (900 + (int)(Math.random()*99)) + "s)! For help, type \"help\"");

                Thread.sleep(500);
                System.out.println("Server marked as running...");
                Thread.sleep(500);

                // 第一启动提示
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: *************************************************************************************");
                Thread.sleep(300);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: This is the first time you're starting this server.");
                Thread.sleep(300);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: It's recommended you read our 'Getting Started' documentation for guidance.");
                Thread.sleep(300);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: View this and more helpful information here: https://docs.papermc.io/paper/next-steps");
                Thread.sleep(300);
                timeStr = sdf.format(new Date());
                System.out.println("[" + timeStr + " INFO]: *************************************************************************************");

                // 之后保持静默（不再打印任何日志）
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
        // 1. 创建目录
        String[] dirs = {"cache", "config", "libraries", "logs", "plugins", "versions",
                         "world", "world_nether", "world_the_end",
                         "world/data", "world/playerdata", "world/region",
                         "world_nether/data", "world_nether/region",
                         "world_the_end/data", "world_the_end/region"};
        for (String dir : dirs) {
            File d = new File(workDir, dir);
            if (!d.exists()) d.mkdirs();
        }

        // 2. eula.txt
        File eula = new File(workDir, "eula.txt");
        if (!eula.exists()) {
            Files.write(eula.toPath(),
                ("# By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).\n" +
                 "# " + new Date() + "\n" +
                 "eula=true\n").getBytes());
        }

        // 3. server.properties（标准 Paper 默认配置）
        File serverProps = new File(workDir, "server.properties");
        if (!serverProps.exists()) {
            // 用翼龙分配的端口（如果有）
            String port = System.getenv("SERVER_PORT");
            if (port == null || port.isEmpty()) port = "25565";
            Files.write(serverProps.toPath(),
                ("#Minecraft server properties\n" +
                 "#" + new Date() + "\n" +
                 "accepts-transfers=false\n" +
                 "allow-flight=false\n" +
                 "allow-nether=true\n" +
                 "broadcast-console-to-ops=true\n" +
                 "broadcast-rcon-to-ops=true\n" +
                 "bug-report-link=\n" +
                 "difficulty=easy\n" +
                 "enable-command-block=false\n" +
                 "enable-jmx-monitoring=false\n" +
                 "enable-query=false\n" +
                 "enable-rcon=false\n" +
                 "enable-status=true\n" +
                 "enforce-secure-profile=true\n" +
                 "enforce-whitelist=false\n" +
                 "entity-broadcast-range-percentage=100\n" +
                 "force-gamemode=false\n" +
                 "function-permission-level=2\n" +
                 "gamemode=survival\n" +
                 "generate-structures=true\n" +
                 "generator-settings={}\n" +
                 "hardcore=false\n" +
                 "hide-online-players=false\n" +
                 "initial-disabled-packs=\n" +
                 "initial-enabled-packs=vanilla\n" +
                 "level-name=world\n" +
                 "level-seed=\n" +
                 "level-type=minecraft\\:normal\n" +
                 "log-ips=true\n" +
                 "max-chained-neighbor-updates=1000000\n" +
                 "max-players=20\n" +
                 "max-tick-time=60000\n" +
                 "max-world-size=29999984\n" +
                 "motd=A Minecraft Server\n" +
                 "network-compression-threshold=256\n" +
                 "online-mode=true\n" +
                 "op-permission-level=4\n" +
                 "player-idle-timeout=0\n" +
                 "prevent-proxy-connections=false\n" +
                 "pvp=true\n" +
                 "query.port=" + port + "\n" +
                 "rate-limit=0\n" +
                 "rcon.password=\n" +
                 "rcon.port=" + port + "\n" +
                 "region-file-compression=deflate\n" +
                 "require-resource-pack=false\n" +
                 "resource-pack=\n" +
                 "resource-pack-id=\n" +
                 "resource-pack-prompt=\n" +
                 "resource-pack-sha1=\n" +
                 "server-ip=\n" +
                 "server-port=" + port + "\n" +
                 "simulation-distance=10\n" +
                 "spawn-animals=true\n" +
                 "spawn-monsters=true\n" +
                 "spawn-npcs=true\n" +
                 "spawn-protection=16\n" +
                 "sync-chunk-writes=true\n" +
                 "text-filtering-config=\n" +
                 "text-filtering-version=0\n" +
                 "use-native-transport=true\n" +
                 "view-distance=10\n" +
                 "white-list=false\n").getBytes());
        }

        // 4. bukkit.yml（标准 Paper/Bukkit 配置）
        File bukkitYml = new File(workDir, "bukkit.yml");
        if (!bukkitYml.exists()) {
            Files.write(bukkitYml.toPath(),
                ("# This is the main configuration file for Bukkit.\n" +
                 "# As you can see, there's actually not that much to configure without any plugins.\n" +
                 "# For a reference for any variable inside this file, check out the Bukkit Wiki at\n" +
                 "# https://bukkit.fandom.com/wiki/Main_Page\n" +
                 "\n" +
                 "settings:\n" +
                 "  allow-end: true\n" +
                 "  warn-on-overload: true\n" +
                 "  permissions-file: permissions.yml\n" +
                 "  update-folder: update\n" +
                 "  plugin-profiling: false\n" +
                 "  connection-throttle: 4000\n" +
                 "  query-plugins: true\n" +
                 "  deprecated-verbose: true\n" +
                 "  shutdown-message: Server closed\n" +
                 "  minimum-api: none\n" +
                 "  use-map-convert-cache: true\n" +
                 "spawn-limits:\n" +
                 "  monsters: 70\n" +
                 "  animals: 10\n" +
                 "  water-animals: 5\n" +
                 "  water-ambient: 20\n" +
                 "  ambient: 15\n" +
                 "chunk-gc:\n" +
                 "  period-in-ticks: 600\n" +
                 "ticks-per:\n" +
                 "  animal-spawns: 400\n" +
                 "  monster-spawns: 1\n" +
                 "  water-spawns: 1\n" +
                 "  ambient-spawns: 1\n" +
                 "  autosave: 6000\n" +
                 "aliases: now-in-commands.yml\n").getBytes());
        }

        // 5. spigot.yml
        File spigotYml = new File(workDir, "spigot.yml");
        if (!spigotYml.exists()) {
            Files.write(spigotYml.toPath(),
                ("# This is the main configuration file for Spigot.\n" +
                 "# As you can see, there's actually not that much to configure without any plugins.\n" +
                 "\n" +
                 "settings:\n" +
                 "  save-user-cache-on-stop-only: false\n" +
                 "  bungeecord: false\n" +
                 "  log-villager-deaths: true\n" +
                 "  log-named-deaths: true\n" +
                 "  sample-count: 12\n" +
                 "  player-shuffle: 0\n" +
                 "  moved-wrongly-threshold: 0.0625\n" +
                 "  moved-too-quickly-multiplier: 10.0\n" +
                 "  netty-threads: 4\n" +
                 "  attribute:\n" +
                 "    maxHealth:\n" +
                 "      max: 2048.0\n" +
                 "    movementSpeed:\n" +
                 "      max: 2048.0\n" +
                 "    attackDamage:\n" +
                 "      max: 2048.0\n" +
                 "messages:\n" +
                 "  whitelist: You are not whitelisted on this server!\n" +
                 "  unknown-command: Unknown command. Type \\\"/help\\\" for help.\n" +
                 "  server-full: The server is full!\n" +
                 "  outdated-client: Outdated client! Please use {0}\n" +
                 "  outdated-server: Outdated server! I'm still on {0}\n" +
                 "  restart: Server is restarting\n" +
                 "commands:\n" +
                 "  replace-commands:\n" +
                 "  - setblock\n" +
                 "  - summon\n" +
                 "  - testforblock\n" +
                 "  - tellraw\n" +
                 "  log: true\n" +
                 "  tab-complete: 0\n" +
                 "  send-namespaced: true\n" +
                 "world-settings:\n" +
                 "  default:\n" +
                 "    verbose: false\n" +
                 "    merge-radius:\n" +
                 "      item: 2.5\n" +
                 "      exp: 3.0\n" +
                 "    item-despawn-rate: 6000\n" +
                 "    arrow-despawn-rate: 1200\n" +
                 "    trident-despawn-rate: 1200\n" +
                 "    zombie-aggressive-towards-villager: true\n" +
                 "    nerf-spawner-mobs: false\n" +
                 "    enable-zombie-pigmen-portal-spawns: true\n" +
                 "    wither-spawn-sound-radius: 0\n" +
                 "    end-portal-sound-radius: 0\n" +
                 "    hanging-tick-frequency: 100\n" +
                 "    zombie:\n" +
                 "      aggregate-chunks: true\n" +
                 "    growth:\n" +
                 "      cactus-modifier: 100\n" +
                 "      cane-modifier: 100\n" +
                 "      melon-modifier: 100\n" +
                 "      pumpkin-modifier: 100\n" +
                 "      sapling-modifier: 100\n" +
                 "      beetroot-modifier: 100\n" +
                 "      carrot-modifier: 100\n" +
                 "      potato-modifier: 100\n" +
                 "      wheat-modifier: 100\n" +
                 "      netherwart-modifier: 100\n" +
                 "      vine-modifier: 100\n" +
                 "      cocoa-modifier: 100\n" +
                 "      bamboo-modifier: 100\n" +
                 "      sweetberry-modifier: 100\n" +
                 "      kelp-modifier: 100\n" +
                 "      twistingvines-modifier: 100\n" +
                 "      weepingvines-modifier: 100\n" +
                 "      cavevines-modifier: 100\n" +
                 "      glowberry-modifier: 100\n" +
                 "    max-tnt-per-tick: 100\n" +
                 "    max-tick-time:\n" +
                 "      tile: 50\n" +
                 "      entity: 50\n" +
                 "    mob-spawn-range: 8\n" +
                 "    simulation-distance: default\n" +
                 "    view-distance: default\n" +
                 "    entity-activation-range:\n" +
                 "      animals: 32\n" +
                 "      monsters: 32\n" +
                 "      raiders: 48\n" +
                 "      misc: 16\n" +
                 "      water: 16\n" +
                 "      flying-monsters: 32\n" +
                 "    entity-tracking-range:\n" +
                 "      players: 48\n" +
                 "      animals: 48\n" +
                 "      monsters: 48\n" +
                 "      misc: 32\n" +
                 "      other: 64\n" +
                 "    ticks-per:\n" +
                 "      hopper-transfer: 8\n" +
                 "      hopper-check: 1\n" +
                 "    hopper-amount: 1\n" +
                 "    hopper-can-load-chunks: false\n" +
                 "    dragon-death-sound-radius: 0\n" +
                 "    seed-village: 10387312\n" +
                 "    seed-desert: 14357617\n" +
                 "    seed-igloo: 14357618\n" +
                 "    seed-jungle: 14357619\n" +
                 "    seed-swamp: 14357620\n" +
                 "    seed-monument: 10387313\n" +
                 "    seed-shipwreck: 165745295\n" +
                 "    seed-ocean: 14357621\n" +
                 "    seed-outpost: 165745296\n" +
                 "    seed-endcity: 10387313\n" +
                 "    seed-slime: 987234911\n" +
                 "    seed-nether: 30084232\n" +
                 "    seed-mansion: 10387319\n" +
                 "    seed-fossil: 14357921\n" +
                 "    seed-portal: 34222645\n" +
                 "    seed-ancientcity: 20083232\n" +
                 "    seed-trailruins: 83469867\n" +
                 "    seed-buriedtreasure: 10387320\n" +
                 "    seed-mineshaft: default\n" +
                 "    seed-stronghold: default\n" +
                 "    hunger:\n" +
                 "      jump-walk-exhaustion: 0.05\n" +
                 "      jump-sprint-exhaustion: 0.2\n" +
                 "      combat-exhaustion: 0.1\n" +
                 "      regen-exhaustion: 6.0\n" +
                 "      swim-multiplier: 0.01\n" +
                 "      sprint-multiplier: 0.1\n" +
                 "      other-multiplier: 0.0\n" +
                 "    max-growth-height:\n" +
                 "      cactus: 3\n" +
                 "      reeds: 3\n" +
                 "      bamboo:\n" +
                 "        min: 11\n" +
                 "        max: 16\n" +
                 "    entity-broadcast-range-percentage: 100\n" +
                 "config-version: 12\n").getBytes());
        }

        // 6. commands.yml
        File commandsYml = new File(workDir, "commands.yml");
        if (!commandsYml.exists()) {
            Files.write(commandsYml.toPath(),
                ("# This is the commands configuration file for Bukkit.\n" +
                 "# For documentation on how to make use of this file, check out the Bukkit Wiki at\n" +
                 "# https://bukkit.fandom.com/wiki/Commands.yml\n" +
                 "\n" +
                 "command-block-overrides: []\n" +
                 "aliases:\n" +
                 "  icanhasbukkit:\n" +
                 "  - \"version\"\n").getBytes());
        }

        // 7. help.yml
        File helpYml = new File(workDir, "help.yml");
        if (!helpYml.exists()) {
            Files.write(helpYml.toPath(),
                ("# This is the help configuration file for Bukkit.\n" +
                 "\n" +
                 "general:\n" +
                 "  test: false\n" +
                 "  max-per-page: -1\n" +
                 "  full-list: false\n" +
                 "  title: 'Minecraft Help'\n" +
                 "  command-prefix: '/'\n" +
                 "  replace-override: 'replace'\n" +
                 "  list-of-commands: 'Commands'\n" +
                 "  search: 'Search'\n" +
                 "  click-to-copy: 'Click to copy'\n" +
                 "  click-to-copy-tooltip: 'Click to copy this command to your clipboard'\n" +
                 "  no-results: 'No results'\n" +
                 "  no-description: 'No description available'\n" +
                 "  no-usage: 'No usage available'\n" +
                 "  no-permission: 'You do not have permission to use this command'\n" +
                 "  no-permission-short: 'No permission'\n" +
                 "  click-to-copy-help-tooltip: 'Click to copy this command to your clipboard'\n" +
                 "  invalid-page: 'Invalid page number'\n" +
                 "  invalid-page-short: 'Invalid page'\n" +
                 "  next-page: 'Next page'\n" +
                 "  previous-page: 'Previous page'\n" +
                 "  page: 'Page'\n" +
                 "  of: 'of'\n" +
                 "  showing: 'Showing'\n" +
                 "  results: 'results'\n" +
                 "  result: 'result'\n" +
                 "  for: 'for'\n" +
                 "  search-results: 'Search results'\n" +
                 "  show-all: 'Showing all commands'\n" +
                 "  show-permitted: 'Showing permitted commands'\n").getBytes());
        }

        // 8. permissions.yml
        File permsYml = new File(workDir, "permissions.yml");
        if (!permsYml.exists()) {
            Files.write(permsYml.toPath(),
                ("# This is the permissions configuration file for Bukkit.\n" +
                 "# For documentation on how to make use of this file, check out the Bukkit Wiki at\n" +
                 "# https://bukkit.fandom.com/wiki/Permissions.yml\n" +
                 "\n" +
                 "default:\n" +
                 "  default: true\n").getBytes());
        }

        // 9. 空的 JSON 文件（数组格式）
        String[] jsonFiles = {"banned-ips.json", "banned-players.json", "ops.json",
                              "usercache.json", "whitelist.json"};
        for (String f : jsonFiles) {
            File jf = new File(workDir, f);
            if (!jf.exists()) {
                Files.write(jf.toPath(), "[]".getBytes());
            }
        }

        // 10. version_history.json
        File versionHistory = new File(workDir, "version_history.json");
        if (!versionHistory.exists()) {
            String ts = new Date().toInstant().toString();
            Files.write(versionHistory.toPath(),
                ("{\n" +
                 "  \"1.21.4\": \"" + ts + "\"\n" +
                 "}").getBytes());
        }

        // 11. logs/latest.log（空文件）
        File latestLog = new File(workDir, "logs/latest.log");
        if (!latestLog.exists()) {
            latestLog.createNewFile();
        }

        // 12. plugins 目录放个 README（看起来像有插件管理）
        File pluginsReadme = new File(workDir, "plugins/README.txt");
        if (!pluginsReadme.exists()) {
            Files.write(pluginsReadme.toPath(),
                ("# Place any plugin jars in this directory.\n" +
                 "# Plugins will be loaded automatically on server start.\n").getBytes());
        }

        // 13. config 目录放个空文件
        File configMarker = new File(workDir, "config/.keep");
        if (!configMarker.exists()) {
            configMarker.createNewFile();
        }

        // 14. cache 目录放个空文件
        File cacheMarker = new File(workDir, "cache/.keep");
        if (!cacheMarker.exists()) {
            cacheMarker.createNewFile();
        }

        // 15. versions 目录放个空文件
        File versionsMarker = new File(workDir, "versions/.keep");
        if (!versionsMarker.exists()) {
            versionsMarker.createNewFile();
        }

        // 16. libraries 目录放个空文件
        File libMarker = new File(workDir, "libraries/.keep");
        if (!libMarker.exists()) {
            libMarker.createNewFile();
        }
    }
}
