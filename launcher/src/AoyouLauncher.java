import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.concurrent.*;

/**
 * 傲游面板一体化启动器
 *
 * 工作流程：
 *   1. 启动时把内嵌的 Node.js runtime + index.js + node_modules 解压到工作目录的 .aoyou-runtime/
 *   2. 用解压出来的 node 二进制启动 index.js
 *   3. 转发 stdout/stderr
 *   4. 收到 SIGTERM/SIGINT 时优雅关闭 Node.js
 *
 * JAR 内部结构：
 *   aoyou-panel.jar
 *   ├── META-INF/MANIFEST.MF       (Main-Class: AoyouLauncher)
 *   ├── AoyouLauncher.class         (本文件编译后)
 *   ├── runtime/node-bin/           (Node.js 运行时，linux-x64)
 *   ├── runtime/node_modules/       (预装依赖)
 *   └── app/                        (应用代码)
 *       ├── index.js
 *       └── package.json
 */
public class AoyouLauncher {

    private static final String VERSION = "1.0.0";
    private static final String RUNTIME_DIR_NAME = ".aoyou-runtime";
    private static final String NODE_ENTRY = "runtime/node-bin/bin/node";
    private static final String APP_ENTRY = "app/index.js";

    public static void main(String[] args) throws Exception {
        System.out.println("[Launcher] 傲游面板启动器 v" + VERSION);
        System.out.println("[Launcher] Java: " + System.getProperty("java.version"));
        System.out.println("[Launcher] OS: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));

        // 1. 获取 JAR 自己的路径
        String jarPath = getJarPath();
        if (jarPath == null) {
            System.err.println("[Launcher] ❌ 无法定位 JAR 文件，请用 java -jar 启动");
            System.exit(1);
            return;
        }
        System.out.println("[Launcher] JAR: " + jarPath);

        // 2. 工作目录（解压目标）
        String workDir = System.getProperty("user.dir");
        String runtimeDir = workDir + File.separator + RUNTIME_DIR_NAME;
        Path runtimePath = Paths.get(runtimeDir);

        // 3. 检测是否需要重新解压（首次启动 / 版本变化）
        boolean needExtract = needsExtract(runtimePath);
        if (needExtract) {
            System.out.println("[Launcher] 📦 首次启动，解压运行时到 " + runtimeDir);
            long start = System.currentTimeMillis();
            extractRuntime(jarPath, runtimePath);
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("[Launcher] ✅ 解压完成 (" + elapsed + "ms)");
        } else {
            System.out.println("[Launcher] ✅ 运行时已就绪");
        }

        // 4. 检查 node 二进制
        String nodeBin = runtimeDir + File.separator + "node-bin/bin/node";
        if (isWindows()) nodeBin = runtimeDir + File.separator + "node-bin\\node.exe";
        if (!new File(nodeBin).exists()) {
            System.err.println("[Launcher] ❌ Node.js 二进制不存在: " + nodeBin);
            System.exit(1);
            return;
        }
        // 确保可执行
        new File(nodeBin).setExecutable(true);

        // 5. 检查 index.js
        String indexPath = runtimeDir + File.separator + "index.js";
        if (!new File(indexPath).exists()) {
            System.err.println("[Launcher] ❌ index.js 不存在: " + indexPath);
            System.exit(1);
            return;
        }

        // 6. 启动 Node.js
        System.out.println("[Launcher] 🚀 启动 Node.js...");
        List<String> cmd = new ArrayList<>();
        cmd.add(nodeBin);
        cmd.add("index.js");
        // 透传启动参数
        for (String a : args) cmd.add(a);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(runtimeDir));
        pb.redirectErrorStream(true);
        // 继承环境变量
        Map<String, String> env = pb.environment();
        // 确保 PATH 包含 node-bin（有些子进程需要）
        String path = env.getOrDefault("PATH", "");
        env.put("PATH", new File(nodeBin).getParent() + File.pathSeparator + path);

        Process node = pb.start();

        // 7. 转发 stdout
        Thread logThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(node.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println(line);
                }
            } catch (IOException e) {
                // 进程退出时流关闭，正常
            }
        });
        logThread.setDaemon(true);
        logThread.start();

        // 8. 转发 stdin（让用户能输入命令）
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

        // 9. 优雅关闭
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

        // 10. 等待退出
        int code = node.waitFor();
        System.out.println("[Launcher] Node.js 退出，代码: " + code);
        System.exit(code);
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

    /** 检查是否需要重新解压运行时 */
    private static boolean needsExtract(Path runtimePath) {
        if (!Files.exists(runtimePath)) return true;
        Path marker = runtimePath.resolve(".extracted");
        if (!Files.exists(marker)) return true;
        // 检查 node 二进制是否存在
        Path nodeBin = runtimePath.resolve(isWindows() ? "node-bin/node.exe" : "node-bin/bin/node");
        return !Files.exists(nodeBin);
    }

    /** 从 JAR 解压运行时到目标目录 */
    private static void extractRuntime(String jarPath, Path runtimePath) throws Exception {
        Files.createDirectories(runtimePath);
        try (JarFile jar = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jar.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                // 只解压 runtime/ 和 app/ 下的内容
                if (!name.startsWith("runtime/") && !name.startsWith("app/")) continue;
                if (entry.isDirectory()) continue;

                // 计算目标路径：去掉 "runtime/" 前缀，"app/index.js" → "index.js"
                String relPath;
                if (name.startsWith("runtime/")) {
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

                // 设置可执行位（node 二进制）
                if (relPath.equals("node-bin/bin/node") || relPath.equals("node-bin/node.exe")) {
                    target.toFile().setExecutable(true);
                }
            }
            // 写入标记文件
            Files.write(runtimePath.resolve(".extracted"),
                    ("v" + VERSION + "\n" + System.currentTimeMillis() + "\n").getBytes());
            System.out.println("[Launcher] 解压文件数: " + count);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
