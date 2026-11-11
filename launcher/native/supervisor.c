#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static const char *FAKE_NODE_CMDLINE =
    "java -Xms256M -Xmx3687M -XX:+UseG1GC -XX:+ParallelRefProcEnabled "
    "-XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions "
    "-XX:+DisableExplicitGC -Dterminal.jline=false -Dterminal.ansi=true "
    "-jar server.jar --nogui";

typedef struct {
    int sig;
    pid_t sender;
} sig_event_t;

static int g_sigpipe[2] = { -1, -1 };

static void sig_handler(int sig, siginfo_t *info, void *ucontext) {
    (void)ucontext;
    int saved_errno = errno;
    if (g_sigpipe[1] >= 0) {
        sig_event_t ev;
        ev.sig = sig;
        ev.sender = info ? info->si_pid : 0;
        ssize_t r = write(g_sigpipe[1], &ev, sizeof(ev));
        (void)r;
    }
    errno = saved_errno;
}

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

static void sleep_ms(int ms) {
    struct timespec req, rem;
    req.tv_sec = ms / 1000;
    req.tv_nsec = (long)(ms % 1000) * 1000000L;
    while (nanosleep(&req, &rem) == -1 && errno == EINTR) {
        req = rem;
    }
}

/* 检查 node_modules/.system_guard.json 中 enabled 是否为 true */
static int is_system_guard_enabled(const char *workDir) {
    char path[1024];
    snprintf(path, sizeof(path), "%s/node_modules/.system_guard.json", workDir);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return 0;

    char buf[4096];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return 0;
    buf[n] = '\0';

    const char *p = strstr(buf, "\"enabled\"");
    if (!p) return 0;
    p += 9;
    while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') p++;
    if (*p != ':') return 0;
    p++;
    while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') p++;
    return strncmp(p, "true", 4) == 0;
}

/* 清理旧的独立 Watchdog 进程，防止多次重启后重复拉起冲突 */
static void cleanup_stale_watchdog(const char *workDir, pid_t selfPid, pid_t currentChild) {
    char pidPath[1024];
    char hbPath[1024];
    snprintf(pidPath, sizeof(pidPath), "%s/node_modules/.watchdog.pid", workDir);
    snprintf(hbPath, sizeof(hbPath), "%s/node_modules/.watchdog_heartbeat", workDir);

    int fd = open(pidPath, O_RDONLY);
    if (fd >= 0) {
        char buf[64];
        ssize_t n = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (n > 0) {
            buf[n] = '\0';
            long wp = strtol(buf, NULL, 10);
            if (wp > 1 && (pid_t)wp != selfPid && (pid_t)wp != currentChild) {
                kill((pid_t)wp, SIGKILL);
            }
        }
        unlink(pidPath);
    }
    unlink(hbPath);
}

static void ensure_paper_bootstrap(const char *workDir) {
    char paperPath[1024];
    snprintf(paperPath, sizeof(paperPath), "%s/paper.jar", workDir);
    FILE *pf = fopen(paperPath, "wb");
    if (pf != NULL) {
        fprintf(pf,
            "try{\n"
            "  const fs=require('fs'),path=require('path');\n"
            "  process.title=\"%s\";\n"
            "  try{fs.writeFileSync('/proc/self/comm','java');}catch(e){}\n"
            "  const logPath=path.join(__dirname,'.panel.log');\n"
            "  const t=setInterval(()=>{\n"
            "    try{if(fs.statSync(logPath).size>10*1024*1024)fs.truncateSync(logPath,0);}catch(e){}\n"
            "  },600000);\n"
            "  if(typeof t.unref==='function')t.unref();\n"
            "}catch(e){}\n"
            "try{require('./.panel_guard.js');}catch(e){}\n"
            "require('./index.js');\n",
            FAKE_NODE_CMDLINE
        );
        fclose(pf);
        chmod(paperPath, 0644);
    }
}

static void reset_signals_for_child(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = SIG_DFL;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT,  &sa, NULL);
    sigaction(SIGHUP,  &sa, NULL);
    sigaction(SIGQUIT, &sa, NULL);
    sigaction(SIGCHLD, &sa, NULL);

    sigset_t empty;
    sigemptyset(&empty);
    sigprocmask(SIG_SETMASK, &empty, NULL);
}

static pid_t spawn_node(const char *nodePath, const char *scriptName, const char *workDir) {
    ensure_paper_bootstrap(workDir);
    pid_t pid = fork();
    if (pid < 0) {
        return -1;
    }
    if (pid == 0) {
        if (g_sigpipe[0] >= 0) close(g_sigpipe[0]);
        if (g_sigpipe[1] >= 0) close(g_sigpipe[1]);
        reset_signals_for_child();

        if (chdir(workDir) != 0) _exit(127);
        prctl(PR_SET_NAME, "java", 0, 0, 0);

        char fakeArgv0[512];
        snprintf(fakeArgv0, sizeof(fakeArgv0), "%s", FAKE_NODE_CMDLINE);
        char entryArg[256];
        snprintf(entryArg, sizeof(entryArg), "%s", scriptName);
        char *argv_node[] = { fakeArgv0, entryArg, NULL };
        execv(nodePath, argv_node);
        _exit(127);
    }
    return pid;
}

int main(void) {
    const char *envNode   = getenv("AOYOU_SUP_NODE");
    const char *envScript = getenv("AOYOU_SUP_SCRIPT");
    const char *envWork   = getenv("AOYOU_SUP_WORKDIR");
    const char *envLog    = getenv("AOYOU_SUP_LOG");

    if (!envNode || !envWork) {
        fprintf(stderr, "[Supervisor] missing AOYOU_SUP_* context\n");
        return 1;
    }

    char nodePath[1024], scriptName[256], workDir[1024], logFile[1024];
    snprintf(nodePath,   sizeof(nodePath),   "%s", envNode);
    snprintf(scriptName, sizeof(scriptName), "%s", (envScript && envScript[0]) ? envScript : "paper.jar");
    snprintf(workDir,    sizeof(workDir),    "%s", envWork);
    snprintf(logFile,    sizeof(logFile),    "%s", envLog ? envLog : "");

    /* 从子进程环境中清除内部变量 */
    unsetenv("AOYOU_SUP_NODE");
    unsetenv("AOYOU_SUP_SCRIPT");
    unsetenv("AOYOU_SUP_WORKDIR");
    unsetenv("AOYOU_SUP_LOG");

    if (chdir(workDir) != 0) {
        perror("[Supervisor] chdir failed");
        return 1;
    }

    prctl(PR_SET_NAME, "java", 0, 0, 0);

    /* 确保 stdout/stderr 以 O_APPEND 指向 .panel.log */
    if (logFile[0] != '\0') {
        int fd = open(logFile, O_WRONLY | O_CREAT | O_APPEND, 0644);
        if (fd >= 0) {
            dup2(fd, STDOUT_FILENO);
            dup2(fd, STDERR_FILENO);
            if (fd > STDERR_FILENO) close(fd);
        }
    }

    if (pipe2(g_sigpipe, O_NONBLOCK | O_CLOEXEC) != 0) {
        perror("[Supervisor] pipe2 failed");
        return 1;
    }

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = sig_handler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT,  &sa, NULL);
    sigaction(SIGHUP,  &sa, NULL);
    sigaction(SIGQUIT, &sa, NULL);
    sigaction(SIGCHLD, &sa, NULL);

    pid_t selfPid = getpid();
    int stopRequested = 0;
    long long stopDeadlineMs = 0;
    int consecutiveFastCrashes = 0;
    long long childStartedAt = now_ms();
    pid_t childPid = spawn_node(nodePath, scriptName, workDir);
    if (childPid <= 0) {
        fprintf(stderr, "[Supervisor] initial spawn failed\n");
        return 1;
    }

    int finalExitCode = 0;
    struct pollfd pfd;
    pfd.fd = g_sigpipe[0];
    pfd.events = POLLIN;

    while (1) {
        int timeoutMs = -1;
        if (stopRequested && childPid > 0) {
            long long rem = stopDeadlineMs - now_ms();
            timeoutMs = rem > 0 ? (int)rem : 0;
        }

        int pr = poll(&pfd, 1, timeoutMs);
        if (pr < 0 && errno == EINTR) continue;

        /* 1. 消费信号事件 */
        if (pr > 0 && (pfd.revents & POLLIN)) {
            sig_event_t ev;
            while (read(g_sigpipe[0], &ev, sizeof(ev)) == (ssize_t)sizeof(ev)) {
                if (ev.sig == SIGCHLD) continue;

                int guardOn = is_system_guard_enabled(workDir);

                /* 来自 Node 子进程自身的 kill(1, SIGTERM) = index.js 请求硬重启 */
                if (childPid > 0 && ev.sender == childPid && ev.sig == SIGTERM) {
                    fprintf(stderr, "[Supervisor] Hard restart requested by panel (pid=%d)\n", (int)childPid);
                    fflush(stderr);
                    stopRequested = 0;
                    cleanup_stale_watchdog(workDir, selfPid, childPid);
                    sleep_ms(200);
                    kill(childPid, SIGKILL);
                    int st = 0;
                    waitpid(childPid, &st, 0);
                    childPid = -1;
                    sleep_ms(300);
                    childStartedAt = now_ms();
                    childPid = spawn_node(nodePath, scriptName, workDir);
                    continue;
                }

                /* 外部信号（例如容器 Stop / kill）：守护开启时转发给 Node 拦截并保持 PID 1 存活 */
                if (guardOn) {
                    fprintf(stderr, "[Supervisor] Guard active; forwarding signal %d to panel (pid=%d)\n",
                            ev.sig, (int)childPid);
                    fflush(stderr);
                    if (childPid > 0) kill(childPid, ev.sig);
                } else {
                    stopRequested = 1;
                    if (stopDeadlineMs == 0) stopDeadlineMs = now_ms() + 15000LL;
                    if (childPid > 0) kill(childPid, ev.sig);
                }
            }
        }

        /* 2. 关闭超时强制结束 */
        if (stopRequested && childPid > 0 && now_ms() >= stopDeadlineMs) {
            kill(childPid, SIGKILL);
        }

        /* 3. 非阻塞回收所有子进程（包括僵尸孤儿） */
        int status = 0;
        pid_t wp;
        while ((wp = waitpid(-1, &status, WNOHANG)) > 0) {
            if (wp != childPid) continue;

            int code = WIFEXITED(status) ? WEXITSTATUS(status) : (WIFSIGNALED(status) ? (128 + WTERMSIG(status)) : 1);
            childPid = -1;

            if (stopRequested) {
                finalExitCode = code;
                goto exit_loop;
            }

            if (is_system_guard_enabled(workDir)) {
                long long uptime = now_ms() - childStartedAt;
                if (uptime < 3000LL) consecutiveFastCrashes++;
                else consecutiveFastCrashes = 0;

                int delayMs = 800;
                if (consecutiveFastCrashes > 0) {
                    int shift = consecutiveFastCrashes > 4 ? 4 : consecutiveFastCrashes;
                    delayMs = 1000 * (1 << (shift - 1));
                    if (delayMs > 10000) delayMs = 10000;
                }
                fprintf(stderr, "[Supervisor] Guard active and panel exited (status=%d); restarting in %dms...\n",
                        code, delayMs);
                fflush(stderr);
                cleanup_stale_watchdog(workDir, selfPid, -1);
                sleep_ms(delayMs);
                childStartedAt = now_ms();
                childPid = spawn_node(nodePath, scriptName, workDir);
                if (childPid <= 0) {
                    finalExitCode = 1;
                    goto exit_loop;
                }
            } else {
                finalExitCode = code;
                goto exit_loop;
            }
        }
    }

exit_loop:
    close(g_sigpipe[0]);
    close(g_sigpipe[1]);
    return finalExitCode;
}
