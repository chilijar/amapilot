/* Android Auto DHU 2.0 时偏移 LD_PRELOAD。
 *
 * 背景：手机端（AA 应用）GAL 开发证书 2014-07-04 ~ 2023-03-22 有效，DHU 2.0
 * （2022-03 构建，SDK extras;google;auto 最新即此版）在当前日期做 TLS 客户端
 * 校验时因证书过期而 abort（"Shutting down connection due to auth failure",
 * Unrecoverable error -24）。
 *
 * 方案：把 DHU 进程的时间整体平移回 2022-06-01（证书有效窗口内）。
 * 相对时间增量不变（SDL ticker/定时器不受影响），仅绝对时间回退，
 * BoringSSL 的 X509 有效期校验（time()/gettimeofday/CLOCK_REALTIME）通过。
 *
 * 构建：gcc -shared -fPIC -o build/dhu-faketime.so scripts/dhu-faketime.c -ldl -lpthread
 * 使用：见 scripts/dhu-connect.sh
 */
#define _GNU_SOURCE
#include <time.h>
#include <stdint.h>
#include <sys/time.h>
#include <pthread.h>
#include <dlfcn.h>

/* 目标假"当前时间": 2022-06-01T00:00:00Z = 1654041600 */
#define TARGET_NOW_S 1654041600L

static int64_t offset_ns = -1;
static int64_t offset_us = -1;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

static void init_offset(void) {
    if (offset_ns >= 0) return;
    struct timespec now;
    typedef int (*gt_fn)(clockid_t, struct timespec *);
    static gt_fn real_gt;
    if (!real_gt) real_gt = (gt_fn)dlsym(RTLD_NEXT, "clock_gettime");
    real_gt(CLOCK_REALTIME, &now);
    int64_t delta_s = (int64_t)now.tv_sec - TARGET_NOW_S;
    offset_ns = delta_s * 1000000000L;
    offset_us = delta_s * 1000000L;
}

typedef int (*gt_fn)(clockid_t, struct timespec *);

int clock_gettime(clockid_t clk, struct timespec *ts) {
    static gt_fn real;
    if (!real) real = (gt_fn)dlsym(RTLD_NEXT, "clock_gettime");
    int r = real(clk, ts);
    pthread_mutex_lock(&lock);
    init_offset();
    pthread_mutex_unlock(&lock);
    if (r == 0 && ts && (clk == CLOCK_REALTIME || clk == CLOCK_REALTIME_COARSE)) {
        int64_t ns = (int64_t)ts->tv_sec * 1000000000L + ts->tv_nsec - offset_ns;
        ts->tv_sec  = (time_t)(ns / 1000000000L);
        ts->tv_nsec = (long)(ns % 1000000000L);
    }
    return r;
}

int gettimeofday(struct timeval *tv, void *tz) {
    typedef int (*fnt)(struct timeval *, void *);
    static fnt real;
    if (!real) real = (fnt)dlsym(RTLD_NEXT, "gettimeofday");
    int r = real(tv, tz);
    pthread_mutex_lock(&lock);
    init_offset();
    pthread_mutex_unlock(&lock);
    if (r == 0 && tv) {
        int64_t us = (int64_t)tv->tv_sec * 1000000L + tv->tv_usec - offset_us;
        tv->tv_sec  = (time_t)(us / 1000000L);
        tv->tv_usec = (suseconds_t)(us % 1000000L);
    }
    return r;
}

time_t time(time_t *t) {
    typedef time_t (*fnt)(time_t *);
    static fnt real;
    if (!real) real = (fnt)dlsym(RTLD_NEXT, "time");
    time_t r = real(t);
    pthread_mutex_lock(&lock);
    init_offset();
    pthread_mutex_unlock(&lock);
    time_t fake = r - (time_t)(offset_us / 1000000L);
    if (t) *t = fake;
    return fake;
}
