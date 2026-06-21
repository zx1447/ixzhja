import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

/**
 * 傲游面板一体化启动器 v1.3.0
 *
 * v1.3 改动：
 *   - 从多个来源读端口（SERVER_PORT 环境变量 / server.properties / PTERODACTYL 变量 / 默认 25565）
 *   - 兼容 MC Egg（MC Egg 不一定设 SERVER_PORT，可能写在 server.properties）
 *   - 启动时打印详细端口诊断信息
 */
public class AoyouLauncher {

    private static final String VERSION = "1.3.0";
    private static final String RUNTIME_DIR_NAME = ".aoyou-runtime";
    private static final String NODE_VERSION = "v22.11.0";
    private static final String NODE_DOWNLOAD_URL = 
        "https://nodejs.org/dist/" + NODE_VERSION + "/node-" + NODE_VERSION + "-linux-x64.tar.gz";

    public static void main(String[] args) throws Exception {
        System.out.println("[Launcher] 傲游面板启动器 v" + VERSION);
        System.out.println("[Launcher] Java: " + System.getProperty("java.version"));
        System.out.println("[Launcher] OS: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));

        String workDir = System.getProperty("user.dir");
        String runtimeDir = workDir + File.separator + RUNTIME_DIR_NAME;
        Path runtimePath = Paths.get(runtimeDir);
        Files.createDirectories(runtimePath);

        // 1. 检查并获取 node 二进制
        System.out.println("[Launcher] 🔍 检查 Node.js...");
        String nodeBin = findOrDownloadNode(runtimeDir);
        if (nodeBin == null) {
            System.err.println("[Launcher] ❌ 无法获取 Node.js 运行时");
            System.exit(1);
            return;
        }
        System.out.println("[Launcher] ✅ Node.js: " + nodeBin);

        // 2. 从 JAR 解压 node_modules + index.js
        String jarPath = getJarPath();
        if (jarPath == null) {
            System.err.println("[Launcher] ❌ 无法定位 JAR 文件");
            System.exit(1);
            return;
        }

        boolean needExtract = needsExtract(runtimePath);
        if (needExtract) {
            System.out.println("[Launcher] 📦 首次启动，解压应用文件...");
            long start = System.currentTimeMillis();
            extractAppFiles(jarPath, runtimePath);
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("[Launcher] ✅ 解压完成 (" + elapsed + "ms)");
        }

        // 3. 检查 index.js
        String indexPath = runtimeDir + File.separator + "index.js";
        if (!new File(indexPath).exists()) {
            System.err.println("[Launcher] ❌ index.js 不存在: " + indexPath);
            System.exit(1);
            return;
        }

        // 4. 诊断端口配置
        System.out.println("[Launcher] 🔍 端口诊断...");
        int port = detectPort(workDir);
        System.out.println("[Launcher] 📡 最终使用端口: " + port);

        // 5. 启动 Node.js
        System.out.println("[Launcher] 🚀 启动 Node.js...");
        List<String> cmd = new ArrayList<>();
        cmd.add(nodeBin);
        cmd.add("index.js");
        for (String a : args) cmd.add(a);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(runtimeDir));
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        String path = env.getOrDefault("PATH", "");
        env.put("PATH", new File(nodeBin).getParent() + File.pathSeparator + path);

        // 强制设置 SERVER_PORT 给 node 子进程
        env.put("SERVER_PORT", String.valueOf(port));
        env.put("PORT", String.valueOf(port));
        System.out.println("[Launcher] 📡 已设置 SERVER_PORT=" + port + " 给 Node.js 进程");

        Process node = pb.start();

        // 6. 转发 stdout
        Thread logThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(node.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println(line);
                }
            } catch (IOException e) {}
        });
        logThread.setDaemon(true);
        logThread.start();

        // 7. 转发 stdin
        Thread stdinThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(System.in))) {
                String line;
                OutputStream os = node.getOutputStream();
                while ((line = r.readLine()) != null) {
                    os.write((line + "\n").getBytes());
                    os.flush();
                }
            } catch (IOException e) {}
        });
        stdinThread.setDaemon(true);
        stdinThread.start();

        // 8. 优雅关闭
        final Process nodeRef = node;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Launcher] 🛑 收到关闭信号，停止 Node.js...");
            try {
                nodeRef.destroy();
                if (!nodeRef.waitFor(10, TimeUnit.SECONDS)) {
                    System.out.println("[Launcher] ⚠️ Node.js 未在 10 秒内退出，强制终止");
                    nodeRef.destroyForcibly();
                }
            } catch (InterruptedException e) {}
        }, "shutdown-hook"));

        // 9. 等待退出
        int code = node.waitFor();
        System.out.println("[Launcher] Node.js 退出，代码: " + code);
        System.exit(code);
    }

    /**
     * 从多个来源检测端口
     * 优先级：
     *   1. SERVER_PORT 环境变量（翼龙标准）
     *   2. P_SERVER_PORT 环境变量
     *   3. PTERODACTYL_SERVER_PORT 环境变量
     *   4. server.properties 文件里的 server-port（MC Egg 兼容）
     *   5. 扫描所有 PORT/ALLOCATION 环境变量
     *   6. 默认 25565
     */
    private static int detectPort(String workDir) {
        // 列出所有环境变量（调试用）
        System.out.println("[Launcher] 环境变量:");
        Map<String, String> sysEnv = System.getenv();
        for (Map.Entry<String, String> entry : sysEnv.entrySet()) {
            String key = entry.getKey();
            if (key.toUpperCase().contains("PORT") || key.toUpperCase().contains("ALLOCATION")
                || key.toUpperCase().contains("PTERODACTYL") || key.toUpperCase().contains("SERVER")) {
                System.out.println("  " + key + " = " + entry.getValue());
            }
        }

        // 1. SERVER_PORT 环境变量
        String portStr = System.getenv("SERVER_PORT");
        if (portStr != null && !portStr.trim().isEmpty()) {
            try {
                int p = Integer.parseInt(portStr.trim());
                if (p > 0 && p < 65536) {
                    System.out.println("[Launcher] ✅ 从 SERVER_PORT 环境变量读取端口: " + p);
                    return p;
                }
            } catch (NumberFormatException e) {}
        }

        // 2. P_SERVER_PORT 环境变量
        portStr = System.getenv("P_SERVER_PORT");
        if (portStr != null && !portStr.trim().isEmpty()) {
            try {
                int p = Integer.parseInt(portStr.trim());
                if (p > 0 && p < 65536) {
                    System.out.println("[Launcher] ✅ 从 P_SERVER_PORT 环境变量读取端口: " + p);
                    return p;
                }
            } catch (NumberFormatException e) {}
        }

        // 3. PTERODACTYL_SERVER_PORT 环境变量
        portStr = System.getenv("PTERODACTYL_SERVER_PORT");
        if (portStr != null && !portStr.trim().isEmpty()) {
            try {
                int p = Integer.parseInt(portStr.trim());
                if (p > 0 && p < 65536) {
                    System.out.println("[Launcher] ✅ 从 PTERODACTYL_SERVER_PORT 环境变量读取端口: " + p);
                    return p;
                }
            } catch (NumberFormatException e) {}
        }

        // 4. 从 server.properties 文件读取（MC Egg 兼容）
        File serverProps = new File(workDir, "server.properties");
        if (serverProps.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(serverProps))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("server-port=")) {
                        portStr = line.substring("server-port=".length()).trim();
                        try {
                            int p = Integer.parseInt(portStr);
                            if (p > 0 && p < 65536) {
                                System.out.println("[Launcher] ✅ 从 server.properties 读取端口: " + p);
                                return p;
                            }
                        } catch (NumberFormatException e) {}
                    }
                }
            } catch (IOException e) {}
        }

        // 5. 扫描所有 PORT/ALLOCATION 环境变量
        for (Map.Entry<String, String> entry : sysEnv.entrySet()) {
            String key = entry.getKey().toUpperCase();
            if ((key.contains("PORT") || key.contains("ALLOCATION")) && entry.getValue() != null && !entry.getValue().isEmpty()) {
                try {
                    int p = Integer.parseInt(entry.getValue().trim());
                    if (p > 1024 && p < 65536) {
                        System.out.println("[Launcher] ✅ 从 " + entry.getKey() + " 环境变量读取端口: " + p);
                        return p;
                    }
                } catch (NumberFormatException e) {}
            }
        }

        // 6. 默认 25565（MC 默认端口，翼龙一定转发）
        System.out.println("[Launcher] ℹ️ 未检测到端口配置，使用 MC 默认端口 25565");
        return 25565;
    }

    /** 查找或下载 Node.js */
    private static String findOrDownloadNode(String runtimeDir) {
        // 步骤 1: 检查 PATH 里的 node
        String systemNode = findInPath("node");
        if (systemNode != null) {
            System.out.println("[Launcher] ✅ 系统已安装 Node.js: " + systemNode);
            try {
                Process p = new ProcessBuilder(systemNode, "--version").start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String ver = r.readLine();
                p.waitFor();
                System.out.println("[Launcher] Node.js 版本: " + ver);
            } catch (Exception e) {}
            return systemNode;
        }

        // 步骤 2: 检查 .aoyou-runtime/node/bin/node（之前下载过的）
        String cachedNode = runtimeDir + File.separator + "node/bin/node";
        if (new File(cachedNode).exists()) {
            System.out.println("[Launcher] ✅ 使用缓存的 Node.js: " + cachedNode);
            new File(cachedNode).setExecutable(true);
            return cachedNode;
        }

        // 步骤 3: 下载 Node.js
        System.out.println("[Launcher] 📥 系统未安装 Node.js，开始下载 " + NODE_VERSION + "...");
        System.out.println("[Launcher] 下载地址: " + NODE_DOWNLOAD_URL);
        try {
            Path nodeDir = Paths.get(runtimeDir, "node");
            Files.createDirectories(nodeDir);

            // 方式 1: 尝试用 curl + tar（系统命令）
            boolean success = false;
            if (commandExists("curl") && commandExists("tar")) {
                System.out.println("[Launcher] 尝试用 curl + tar 解压...");
                ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                    "curl -L '" + NODE_DOWNLOAD_URL + "' | tar xz --strip-components=1 -C '" + nodeDir.toString() + "'");
                pb.redirectErrorStream(true);
                Process p = pb.start();

                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        System.out.println("  [download] " + line);
                    }
                }
                int code = p.waitFor();
                success = (code == 0);
                if (!success) {
                    System.out.println("[Launcher] ⚠️ curl + tar 失败 (exit " + code + ")，尝试 Java 解压...");
                }
            }

            // 方式 2: 用 Java 自带的 GZIPInputStream + TarInputStream（纯 Java 实现）
            if (!success) {
                System.out.println("[Launcher] 用 Java 内置工具下载解压...");
                success = downloadAndExtractWithJava(NODE_DOWNLOAD_URL, nodeDir);
            }

            if (!success) {
                System.err.println("[Launcher] ❌ Node.js 下载失败");
                return null;
            }

            // 检查下载结果
            String nodeBin = nodeDir + "/bin/node";
            if (!new File(nodeBin).exists()) {
                System.err.println("[Launcher] ❌ 下载完成但找不到 node 二进制: " + nodeBin);
                try {
                    File[] files = nodeDir.toFile().listFiles();
                    if (files != null) {
                        System.out.println("[Launcher] " + nodeDir + " 内容:");
                        for (File f : files) {
                            System.out.println("  - " + f.getName() + (f.isDirectory() ? "/" : ""));
                        }
                    }
                } catch (Exception e) {}
                return null;
            }
            new File(nodeBin).setExecutable(true);
            System.out.println("[Launcher] ✅ Node.js 下载完成: " + nodeBin);

            // 验证版本
            try {
                Process vp = new ProcessBuilder(nodeBin, "--version").start();
                BufferedReader r = new BufferedReader(new InputStreamReader(vp.getInputStream()));
                String ver = r.readLine();
                vp.waitFor();
                System.out.println("[Launcher] Node.js 版本: " + ver);
            } catch (Exception e) {}

            return nodeBin;
        } catch (Exception e) {
            System.err.println("[Launcher] ❌ 下载 Node.js 异常: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /** 检查命令是否存在 */
    private static boolean commandExists(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", "which " + cmd + " 2>/dev/null").start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 在 PATH 里查找命令 */
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

    /** 获取当前 JAR 文件路径 */
    private static String getJarPath() {
        try {
            return AoyouLauncher.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI().getPath();
        } catch (Exception e) {
            return null;
        }
    }

    /** 检查是否需要重新解压 */
    private static boolean needsExtract(Path runtimePath) {
        Path marker = runtimePath.resolve(".app-extracted");
        if (!Files.exists(marker)) return true;
        Path indexJs = runtimePath.resolve("index.js");
        Path nodeModules = runtimePath.resolve("node_modules");
        return !Files.exists(indexJs) || !Files.exists(nodeModules);
    }

    /** 从 JAR 解压 app 文件 + node_modules */
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
            System.out.println("[Launcher] 解压文件数: " + count);
        }
    }

    /** 纯 Java 下载 + 解压 .tar.gz */
    private static boolean downloadAndExtractWithJava(String url, Path targetDir) {
        Path tarFile = targetDir.resolve("node.tar");
        Path gzFile = targetDir.resolve("node.tar.gz");
        try {
            System.out.println("[Launcher] 下载 .tar.gz 文件...");
            ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                "curl -L -o '" + gzFile.toString() + "' '" + url + "'");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println("  [curl] " + line);
                }
            }
            int code = p.waitFor();
            if (code != 0 || !Files.exists(gzFile)) {
                System.err.println("[Launcher] curl 下载失败 (exit " + code + ")");
                return false;
            }
            System.out.println("[Launcher] 下载完成: " + Files.size(gzFile) / 1024 / 1024 + " MB");

            System.out.println("[Launcher] 解压 gzip...");
            try (InputStream gis = new GZIPInputStream(Files.newInputStream(gzFile));
                 OutputStream os = Files.newOutputStream(tarFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = gis.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }

            System.out.println("[Launcher] 解压 tar...");
            try (InputStream is = Files.newInputStream(tarFile)) {
                extractTar(is, targetDir);
            }

            Files.deleteIfExists(gzFile);
            Files.deleteIfExists(tarFile);

            return true;
        } catch (Exception e) {
            System.err.println("[Launcher] Java 解压失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /** 简单的 tar 解析 */
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
}
