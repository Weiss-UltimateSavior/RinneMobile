#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <errno.h>

#include <mutex>
#include <string>

#define LOG_TAG "KrkrSaveHookNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// bytehook minimal declarations. We link by dlsym at runtime so no header/lib is needed at build time.
typedef void bytehook_stub_t;
typedef void (*bytehook_hooked_t)(bytehook_stub_t *stub, int status_code, const char *caller_path_name, const char *sym_name, void *new_func, void *prev_func, void *arg);
typedef bytehook_stub_t *(*bytehook_hook_partial_t)(void *caller_path_name, const char *callee_path_name, const char *sym_name, void *new_func, bytehook_hooked_t hooked, void *arg);
typedef void *(*bytehook_get_prev_func_t)(void *func);
typedef void (*bytehook_pop_stack_t)(void *return_address);

static bytehook_hook_partial_t bh_hook_partial = nullptr;
static bytehook_get_prev_func_t bh_get_prev_func = nullptr;
static bytehook_pop_stack_t bh_pop_stack = nullptr;

static std::mutex g_lock;
static std::string g_original;
static std::string g_private;
static bool g_enabled = false;

static int (*real_open)(const char *, int, ...) = nullptr;
static int (*real_open64)(const char *, int, ...) = nullptr;
static int (*real___open_2)(const char *, int) = nullptr;
static int (*real_openat)(int, const char *, int, ...) = nullptr;
static FILE *(*real_fopen)(const char *, const char *) = nullptr;
static FILE *(*real_fopen64)(const char *, const char *) = nullptr;
static int (*real_mkdir)(const char *, mode_t) = nullptr;
static int (*real_rename)(const char *, const char *) = nullptr;
static int (*real_unlink)(const char *) = nullptr;
static int (*real_access)(const char *, int) = nullptr;
static int (*real_stat)(const char *, struct stat *) = nullptr;
static int (*real_stat64)(const char *, struct stat64 *) = nullptr;
static int (*real_lstat)(const char *, struct stat *) = nullptr;

static void init_real_funcs();

static std::string normalize_path(const char *path) {
    if (path == nullptr) return std::string();
    std::string p(path);
    const char *file = "file://";
    if (p.rfind(file, 0) == 0) p.erase(0, strlen(file));
    while (p.rfind("./", 0) == 0) p.erase(0, 2);
    if (p.rfind("storage/", 0) == 0) p.insert(0, "/");
    for (;;) {
        size_t pos = p.find("//");
        if (pos == std::string::npos) break;
        p.replace(pos, 2, "/");
    }
    return p;
}

static std::string trim_trailing_slash(std::string p) {
    while (p.size() > 1 && p.back() == '/') p.pop_back();
    return p;
}

static bool ensure_dir_real(const std::string &dir) {
    if (dir.empty()) return false;
    if (dir == "/") return true;
    struct stat st{};
    init_real_funcs();
    if (real_stat && real_stat(dir.c_str(), &st) == 0) return S_ISDIR(st.st_mode);
    size_t pos = 1;
    while (pos != std::string::npos) {
        pos = dir.find('/', pos);
        std::string part = pos == std::string::npos ? dir : dir.substr(0, pos);
        if (!part.empty()) {
            if (real_stat && real_stat(part.c_str(), &st) != 0) {
                if (real_mkdir) {
                    if (real_mkdir(part.c_str(), 0775) != 0 && errno != EEXIST) return false;
                } else {
                    if (::mkdir(part.c_str(), 0775) != 0 && errno != EEXIST) return false;
                }
            }
        }
        if (pos != std::string::npos) pos++;
    }
    return true;
}

static bool ensure_parent_real(const std::string &path) {
    size_t slash = path.rfind('/');
    if (slash == std::string::npos || slash == 0) return true;
    return ensure_dir_real(path.substr(0, slash));
}

static std::string redirect_path_string(const char *raw) {
    if (raw == nullptr) return std::string();
    std::string p = normalize_path(raw);
    std::lock_guard<std::mutex> guard(g_lock);
    if (!g_enabled || g_original.empty() || g_private.empty()) return p;
    std::string original = g_original;
    std::string priv = g_private;
    if (p == original) return priv;
    if (p.size() > original.size() && p.compare(0, original.size(), original) == 0 && p[original.size()] == '/') {
        return priv + p.substr(original.size());
    }
    return p;
}

static bool changed_path(const char *raw, const std::string &mapped) {
    if (raw == nullptr) return false;
    return normalize_path(raw) != mapped;
}

static void log_redirect(const char *fn, const char *raw, const std::string &mapped) {
    if (changed_path(raw, mapped)) LOGI("%s redirect %s -> %s", fn, raw ? raw : "(null)", mapped.c_str());
}

static void init_real_funcs() {
    if (!real_open) real_open = (int (*)(const char *, int, ...)) dlsym(RTLD_NEXT, "open");
    if (!real_open64) real_open64 = (int (*)(const char *, int, ...)) dlsym(RTLD_NEXT, "open64");
    if (!real___open_2) real___open_2 = (int (*)(const char *, int)) dlsym(RTLD_NEXT, "__open_2");
    if (!real_openat) real_openat = (int (*)(int, const char *, int, ...)) dlsym(RTLD_NEXT, "openat");
    if (!real_fopen) real_fopen = (FILE *(*)(const char *, const char *)) dlsym(RTLD_NEXT, "fopen");
    if (!real_fopen64) real_fopen64 = (FILE *(*)(const char *, const char *)) dlsym(RTLD_NEXT, "fopen64");
    if (!real_mkdir) real_mkdir = (int (*)(const char *, mode_t)) dlsym(RTLD_NEXT, "mkdir");
    if (!real_rename) real_rename = (int (*)(const char *, const char *)) dlsym(RTLD_NEXT, "rename");
    if (!real_unlink) real_unlink = (int (*)(const char *)) dlsym(RTLD_NEXT, "unlink");
    if (!real_access) real_access = (int (*)(const char *, int)) dlsym(RTLD_NEXT, "access");
    if (!real_stat) real_stat = (int (*)(const char *, struct stat *)) dlsym(RTLD_NEXT, "stat");
    if (!real_stat64) real_stat64 = (int (*)(const char *, struct stat64 *)) dlsym(RTLD_NEXT, "stat64");
    if (!real_lstat) real_lstat = (int (*)(const char *, struct stat *)) dlsym(RTLD_NEXT, "lstat");
}

static int yh_open_common(const char *pathname, int flags, mode_t mode, bool has_mode, int variant) {
    init_real_funcs();
    std::string mapped = redirect_path_string(pathname);
    if ((flags & O_CREAT) || (flags & O_WRONLY) || (flags & O_RDWR)) ensure_parent_real(mapped);
    log_redirect(variant == 64 ? "open64" : "open", pathname, mapped);
    if (variant == 64 && real_open64) return has_mode ? real_open64(mapped.c_str(), flags, mode) : real_open64(mapped.c_str(), flags);
    if (real_open) return has_mode ? real_open(mapped.c_str(), flags, mode) : real_open(mapped.c_str(), flags);
    errno = ENOSYS;
    return -1;
}

extern "C" int open(const char *pathname, int flags, ...) {
    va_list ap;
    mode_t mode = 0;
    bool has_mode = (flags & O_CREAT) != 0;
    if (has_mode) { va_start(ap, flags); mode = (mode_t) va_arg(ap, int); va_end(ap); }
    return yh_open_common(pathname, flags, mode, has_mode, 0);
}

extern "C" int open64(const char *pathname, int flags, ...) {
    va_list ap;
    mode_t mode = 0;
    bool has_mode = (flags & O_CREAT) != 0;
    if (has_mode) { va_start(ap, flags); mode = (mode_t) va_arg(ap, int); va_end(ap); }
    return yh_open_common(pathname, flags, mode, has_mode, 64);
}

extern "C" int __open_2(const char *pathname, int flags) {
    init_real_funcs();
    std::string mapped = redirect_path_string(pathname);
    if ((flags & O_WRONLY) || (flags & O_RDWR)) ensure_parent_real(mapped);
    log_redirect("__open_2", pathname, mapped);
    if (real___open_2) return real___open_2(mapped.c_str(), flags);
    if (real_open) return real_open(mapped.c_str(), flags);
    errno = ENOSYS;
    return -1;
}

extern "C" int openat(int dirfd, const char *pathname, int flags, ...) {
    va_list ap;
    mode_t mode = 0;
    bool has_mode = (flags & O_CREAT) != 0;
    if (has_mode) { va_start(ap, flags); mode = (mode_t) va_arg(ap, int); va_end(ap); }
    init_real_funcs();
    std::string mapped = redirect_path_string(pathname);
    if ((flags & O_CREAT) || (flags & O_WRONLY) || (flags & O_RDWR)) ensure_parent_real(mapped);
    log_redirect("openat", pathname, mapped);
    if (real_openat) return has_mode ? real_openat(dirfd, mapped.c_str(), flags, mode) : real_openat(dirfd, mapped.c_str(), flags);
    errno = ENOSYS;
    return -1;
}

extern "C" FILE *fopen(const char *pathname, const char *mode) {
    init_real_funcs();
    std::string mapped = redirect_path_string(pathname);
    if (mode && (strchr(mode, 'w') || strchr(mode, 'a') || strchr(mode, '+'))) ensure_parent_real(mapped);
    log_redirect("fopen", pathname, mapped);
    return real_fopen ? real_fopen(mapped.c_str(), mode) : nullptr;
}

extern "C" FILE *fopen64(const char *pathname, const char *mode) {
    init_real_funcs();
    std::string mapped = redirect_path_string(pathname);
    if (mode && (strchr(mode, 'w') || strchr(mode, 'a') || strchr(mode, '+'))) ensure_parent_real(mapped);
    log_redirect("fopen64", pathname, mapped);
    if (real_fopen64) return real_fopen64(mapped.c_str(), mode);
    return real_fopen ? real_fopen(mapped.c_str(), mode) : nullptr;
}

extern "C" int mkdir(const char *pathname, mode_t mode) {
    init_real_funcs();
    std::string mapped = redirect_path_string(pathname);
    ensure_parent_real(mapped);
    log_redirect("mkdir", pathname, mapped);
    if (real_mkdir) return real_mkdir(mapped.c_str(), mode);
    errno = ENOSYS;
    return -1;
}

extern "C" int rename(const char *oldpath, const char *newpath) {
    init_real_funcs();
    std::string oldMapped = redirect_path_string(oldpath);
    std::string newMapped = redirect_path_string(newpath);
    ensure_parent_real(newMapped);
    log_redirect("rename.old", oldpath, oldMapped);
    log_redirect("rename.new", newpath, newMapped);
    if (real_rename) return real_rename(oldMapped.c_str(), newMapped.c_str());
    errno = ENOSYS;
    return -1;
}

extern "C" int unlink(const char *pathname) {
    init_real_funcs();
    std::string mapped = redirect_path_string(pathname);
    log_redirect("unlink", pathname, mapped);
    if (real_unlink) return real_unlink(mapped.c_str());
    errno = ENOSYS;
    return -1;
}

extern "C" int access(const char *pathname, int mode) {
    init_real_funcs();
    std::string mapped = redirect_path_string(pathname);
    log_redirect("access", pathname, mapped);
    if (real_access) return real_access(mapped.c_str(), mode);
    errno = ENOSYS;
    return -1;
}

extern "C" int stat(const char *pathname, struct stat *buf) {
    init_real_funcs();
    std::string mapped = redirect_path_string(pathname);
    log_redirect("stat", pathname, mapped);
    if (real_stat) return real_stat(mapped.c_str(), buf);
    errno = ENOSYS;
    return -1;
}

extern "C" int stat64(const char *pathname, struct stat64 *buf) {
    init_real_funcs();
    std::string mapped = redirect_path_string(pathname);
    log_redirect("stat64", pathname, mapped);
    if (real_stat64) return real_stat64(mapped.c_str(), buf);
    if (real_stat) return real_stat(mapped.c_str(), reinterpret_cast<struct stat *>(buf));
    errno = ENOSYS;
    return -1;
}

extern "C" int lstat(const char *pathname, struct stat *buf) {
    init_real_funcs();
    std::string mapped = redirect_path_string(pathname);
    log_redirect("lstat", pathname, mapped);
    if (real_lstat) return real_lstat(mapped.c_str(), buf);
    errno = ENOSYS;
    return -1;
}

static void hook_one(const char *sym, void *func) {
    if (!bh_hook_partial) return;
    bytehook_stub_t *stub = bh_hook_partial(nullptr, nullptr, sym, func, nullptr, nullptr);
    LOGI("hook %s stub=%p", sym, stub);
}

static bool install_hooks() {
    void *handle = dlopen("libbytehook.so", RTLD_NOW);
    if (!handle) handle = dlopen("bytehook", RTLD_NOW);
    if (!handle) {
        LOGE("dlopen libbytehook.so failed: %s", dlerror());
        return false;
    }
    bh_hook_partial = (bytehook_hook_partial_t) dlsym(handle, "bytehook_hook_partial");
    bh_get_prev_func = (bytehook_get_prev_func_t) dlsym(handle, "bytehook_get_prev_func");
    bh_pop_stack = (bytehook_pop_stack_t) dlsym(handle, "bytehook_pop_stack");
    if (!bh_hook_partial) {
        LOGE("bytehook_hook_partial not found");
        return false;
    }
    hook_one("open", (void *) open);
    hook_one("open64", (void *) open64);
    hook_one("__open_2", (void *) __open_2);
    hook_one("openat", (void *) openat);
    hook_one("fopen", (void *) fopen);
    hook_one("fopen64", (void *) fopen64);
    hook_one("mkdir", (void *) mkdir);
    hook_one("rename", (void *) rename);
    hook_one("unlink", (void *) unlink);
    hook_one("access", (void *) access);
    hook_one("stat", (void *) stat);
    hook_one("stat64", (void *) stat64);
    hook_one("lstat", (void *) lstat);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yuki_yukihub_krkr_KrkrSaveHook_nativeEnable(JNIEnv *env, jclass, jstring originalDir, jstring privateDir) {
    const char *o = env->GetStringUTFChars(originalDir, nullptr);
    const char *p = env->GetStringUTFChars(privateDir, nullptr);
    std::string original = trim_trailing_slash(normalize_path(o));
    std::string priv = trim_trailing_slash(normalize_path(p));
    env->ReleaseStringUTFChars(originalDir, o);
    env->ReleaseStringUTFChars(privateDir, p);
    if (original.empty() || priv.empty()) return JNI_FALSE;
    ensure_dir_real(priv);
    {
        std::lock_guard<std::mutex> guard(g_lock);
        g_original = original;
        g_private = priv;
        g_enabled = true;
    }
    init_real_funcs();
    bool ok = install_hooks();
    LOGI("enabled original=%s private=%s hooks=%d", original.c_str(), priv.c_str(), ok ? 1 : 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yuki_yukihub_krkr_KrkrSaveHook_nativeRedirect(JNIEnv *env, jclass, jstring path) {
    if (!path) return nullptr;
    const char *raw = env->GetStringUTFChars(path, nullptr);
    std::string mapped = redirect_path_string(raw);
    env->ReleaseStringUTFChars(path, raw);
    return env->NewStringUTF(mapped.c_str());
}
