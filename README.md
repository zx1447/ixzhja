# 傲游面板 - 一体化 JAR 构建仓库

将 Node.js 应用打包成单一 JAR 文件，用 `java -jar` 启动。

## 📁 仓库结构

```
ixzhja/
├── .github/workflows/build.yml     # GitHub Actions 自动构建
├── launcher/
│   ├── src/AoyouLauncher.java      # Java 启动器源码
│   └── META-INF/MANIFEST.MF        # JAR 清单
├── app/
│   ├── index.js                    # 主程序
│   └── package.json                # 依赖声明
└── README.md                       # 本文件
```

## 🚀 工作流程

```
push 到 main
   ↓
GitHub Actions 触发
   ↓
1. 下载 Node.js 22 linux-x64 运行时
2. npm install 安装应用依赖
3. 编译 AoyouLauncher.java
4. 打包成 aoyou-panel.jar（含 Node.js + 依赖 + index.js）
   ↓
发布到 GitHub Release (tag: latest)
   ↓
用户在翼龙面板 curl 下载 JAR
   ↓
java -jar aoyou-panel.jar 启动
   ↓
Java 启动器解压内嵌运行时到 .aoyou-runtime/
   ↓
用内嵌的 node 跑 index.js
```

## 📥 部署到翼龙面板

### 1. Egg 配置

| 字段 | 值 |
|------|-----|
| **Name** | 傲游面板 |
| **Docker Image** | `ghcr.io/pterodactyl/yolks:java_21` |
| **Startup Command** | `java -Xms128M -Xmx512M -jar aoyou-panel.jar` |

### 2. Install Script

```bash
#!/bin/bash
# 下载最新 JAR
curl -L -o aoyou-panel.jar \
    https://github.com/zx1447/ixzhja/releases/latest/download/aoyou-panel.jar

echo "下载完成"
ls -lh aoyou-panel.jar
```

### 3. 启动后访问

- 地址: `http://服务器IP:4237`
- 默认密码: `1715`
- **首次登录后立即改密码！**

## 📊 资源占用

| 项 | 值 |
|----|-----|
| JAR 文件大小 | ~80 MB |
| 首次启动内存 | ~350 MB (JVM + Node.js) |
| 进程数 | 2 (java + node) |
| 首次启动耗时 | ~10s（解压运行时） |
| 后续启动耗时 | ~3s |

## 🔧 更新代码

```bash
# 1. 替换 app/index.js
cp /path/to/new-index.js app/index.js

# 2. 如果依赖变了，更新 app/package.json

# 3. commit + push
git add app/
git commit -m "更新主程序"
git push

# 4. GitHub Actions 自动构建，几分钟后 Release 里就是新 JAR
```

## 🐛 故障排查

### Q: 启动报错 "Node.js 二进制不存在"

```bash
# 检查 .aoyou-runtime 目录
ls -la .aoyou-runtime/node-bin/bin/node

# 如果不存在，删除 .aoyou-runtime 让它重新解压
rm -rf .aoyou-runtime
java -jar aoyou-panel.jar
```

### Q: 翼龙面板进程数超限

JVM 自己占 1 个进程。如果翼龙限制 < 5 个进程，建议用纯 Node.js Egg 而不是 JAR 方案。

### Q: 内存超限

修改 Startup Command 限制 JVM 内存：
```
java -Xms64M -Xmx256M -jar aoyou-panel.jar
```

### Q: 想跨平台（Windows / macOS）

当前 JAR 只内嵌 Linux x64 的 Node.js。要支持其他平台：
1. 修改 `build.yml`，下载对应平台的 Node.js
2. 修改 `AoyouLauncher.java`，根据 `os.arch` 选择不同二进制
3. JAR 体积会变大（每个平台 +40MB）

## ⚠️ 注意事项

1. **首次启动慢**：需要把 80MB 的运行时解压到磁盘
2. **磁盘空间**：JAR 本身 80MB + 解压后 200MB = 280MB
3. **系统守护功能**：建议在面板里关闭系统守护，否则容器无法正常停止
4. **代理功能**：需要 NET_ADMIN 权限，翼龙面板可能不支持

## 📝 构建产物

每次 push 到 main 分支后，GitHub Actions 会自动：
1. 构建 JAR
2. 上传到 GitHub Actions Artifact（保留 30 天）
3. 发布到 Release（tag: `latest`，永久保留）

下载地址：`https://github.com/zx1447/ixzhja/releases/latest/download/aoyou-panel.jar`
