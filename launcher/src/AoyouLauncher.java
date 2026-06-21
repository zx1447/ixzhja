import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;
import java.util.regex.Pattern;

/**
 * 傲游面板一体化启动器 v1.1
 *
 * 工作流程：
 *   1. 检查系统是否有 node 命令
 *      ├─ 有 → 直接用系统的 node
 *      └─ 没有 → 下载 Node.js 22 到 .aoyou-runtime/node/ 并解压
 *   2. 从 JAR 解压 node_modules + index.js + package.json（首次启动）
 *   3. 用 node 启动 index.js
 *   4. 转发 stdout/stderr/stdin
 *   5. 收到 SIGTERM/SIGINT 优雅关闭
 *
 * JAR 内部结构：
 *   aoyou-panel.jar (~30MB)
 *   ├── META-INF/MANIFEST.MF
 *   ├── AoyouLauncher.class
 *   ├── runtime/
 *   │   └── node_modules/        (预装依赖，约 28MB)
 *   └── app/
 *       ├── index.js              (主程序)
 *       └── package.json
 */
public class AoyouLauncher {

    private static final String VERSION = "1.1.0";
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

        // 4. 启动 Node.js
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

        Process node = pb.start();

        // 5. 转发 stdout
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

        // 6. 转发 stdin
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

        // 7. 优雅关闭
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

        // 8. 等待退出
        int code = node.waitFor();
        System.out.println("[Launcher] Node.js 退出，代码: " + code);
        System.exit(code);
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
                // 列出实际下载的文件，方便调试
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

    /** 纯 Java 下载 + 解压 .tar.gz（不依赖系统 tar/gzip） */
    private static boolean downloadAndExtractWithJava(String url, Path targetDir) {
        // 简化实现：用 curl 下载到本地文件，然后用 Java 解压
        Path tarFile = targetDir.resolve("node.tar");
        Path gzFile = targetDir.resolve("node.tar.gz");
        try {
            // 1. 用 curl 下载到文件
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

            // 2. 用 Java GZIPInputStream 解压 .gz 得到 .tar
            System.out.println("[Launcher] 解压 gzip...");
            try (InputStream gis = new GZIPInputStream(Files.newInputStream(gzFile));
                 OutputStream os = Files.newOutputStream(tarFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = gis.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }

            // 3. 解析 .tar 文件（纯 Java 实现）
            System.out.println("[Launcher] 解压 tar...");
            try (InputStream is = Files.newInputStream(tarFile)) {
                extractTar(is, targetDir);
            }

            // 4. 清理临时文件
            Files.deleteIfExists(gzFile);
            Files.deleteIfExists(tarFile);

            return true;
        } catch (Exception e) {
            System.err.println("[Launcher] Java 解压失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /** 简单的 tar 解析（POSIX tar 格式） */
    private static void extractTar(InputStream is, Path targetDir) throws IOException {
        byte[] header = new byte[512];
        int read = 0;
        while (read < 512) {
            int n = is.read(header, read, 512 - read);
            if (n < 0) break;
            read += n;
        }
        while (read == 512) {
            // 检查是否是空块（全 0）
            boolean empty = true;
            for (int i = 0; i < 512; i++) {
                if (header[i] != 0) { empty = false; break; }
            }
            if (empty) break;

            // 解析 header
            String name = new String(header, 0, 100).trim().replace("\0", "");
            if (name.isEmpty()) break;
            
            // 解析 size（八进制）
            String sizeStr = new String(header, 124, 12).trim().replace("\0", "");
            long size = 0;
            try { size = Long.parseLong(sizeStr, 8); } catch (Exception e) { break; }
            
            // 解析 typeflag（第 156 字节）
            char type = (char) header[156];

            // 去掉第一级目录名（node-v22.11.0-linux-x64/）
            int slashIdx = name.indexOf('/');
            String relName = (slashIdx >= 0) ? name.substring(slashIdx + 1) : name;
            if (relName.isEmpty()) {
                // 跳过这个 entry 的数据
                skipFully(is, ((size + 511) / 512) * 512);
                read = readFully(is, header, 512);
                continue;
            }

            Path target = targetDir.resolve(relName);

            if (type == '5' || name.endsWith("/")) {
                // 目录
                Files.createDirectories(target);
            } else if (type == '0' || type == '\0') {
                // 普通文件
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
                // 可执行位（bin/ 下的文件）
                if (relName.startsWith("bin/")) {
                    target.toFile().setExecutable(true);
                }
            }

            // 跳过 padding
            long padded = ((size + 511) / 512) * 512 - size;
            if (padded > 0) skipFully(is, (int) padded);

            // 读下一个 header
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
                // 只解压 runtime/node_modules/ 和 app/ 下的内容
                boolean isNodeModules = name.startsWith("runtime/node_modules/");
                boolean isApp = name.startsWith("app/");
                if (!isNodeModules && !isApp) continue;
                if (entry.isDirectory()) continue;

                // 计算目标路径
                String relPath;
                if (isNodeModules) {
                    relPath = name.substring("runtime/".length());  // 保留 node_modules/ 前缀
                } else {
                    relPath = name.substring("app/".length());  // index.js, package.json
                }
                Path target = runtimePath.resolve(relPath);
                Files.createDirectories(target.getParent());

                try (InputStream is = jar.getInputStream(entry)) {
                    Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                }
                count++;
            }
            // 写入标记
            Files.write(runtimePath.resolve(".app-extracted"),
                    ("v" + VERSION + "\n" + System.currentTimeMillis() + "\n").getBytes());
            System.out.println("[Launcher] 解压文件数: " + count);
        }
    }
}
