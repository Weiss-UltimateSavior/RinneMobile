#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <signal.h>
#include <unistd.h>

#include <atomic>
#include <cstring>
#include <mutex>
#include <pthread.h>

namespace {

constexpr const char* kTag = "KrkrExitGuard";
constexpr const char* kHookLibrary = "libkirikiroid3.so";
constexpr const char* kLibcLibrary = "libc.so";
constexpr const char* kDirectorEndSymbol = "_ZN7cocos2d8Director3endEv";
constexpr uintptr_t kGame139MutexDestroyPlt = 0x414320;
constexpr uintptr_t kGame139CondDestroyPlt = 0x404430;
constexpr uintptr_t kGame134MutexDestroyPlt = 0x3f5ef0;
constexpr uintptr_t kGame134CondDestroyPlt = 0x3e6f00;

// GlossHookByName(lib, symbol, replacement, original_out, callback).
using GlossHookByName = void* (*)(const char*, const char*, void*, void**, void*);
// GlossHookAddrByName(lib, relative_address, replacement, original_out,
// flags, hook_mode, callback). hook_mode=3 is required by GlossHook v1.9.6.
using GlossHookAddrByName = void* (*)(const char*, uintptr_t, void*, void**, bool, int, void*);
using DirectorEnd = void (*)(void*);
using MutexDestroy = int (*)(pthread_mutex_t*);
using ConditionDestroy = int (*)(pthread_cond_t*);

std::mutex gInstallMutex;
std::atomic<bool> gInstalled{false};
std::atomic<bool> gTeardownStarted{false};
std::atomic<unsigned int> gSuppressedTeardowns{0};
DirectorEnd gOriginalDirectorEnd = nullptr;
MutexDestroy gOriginalLibcMutexDestroy = nullptr;
ConditionDestroy gOriginalLibcConditionDestroy = nullptr;

[[noreturn]] void terminateKrkrProcess(void*) {
    // Director::end only sets a flag. On the next Cocos frame it starts a teardown
    // that races MIUI/HWUI worker threads and aborts on a destroyed pthread mutex.
    // KRKR runs in its own process, so ending that process here is isolated from the
    // Launcher and avoids the unsafe teardown path entirely.
    __android_log_print(ANDROID_LOG_WARN, kTag,
            "Director::end intercepted; terminating dedicated KRKR process");
    kill(getpid(), SIGKILL);
    _exit(0);
}

// The engine tears down native primitives while MIUI/HWUI/Audio worker threads
// are still using them. libgame's first exit destructor arms a libc-level guard
// for the remaining teardown. The KRKR process exits immediately afterward, so
// retaining those primitives for this short exit window is intentional.
void logSuppressed(const char* operation, const void* nativeObject) {
    const unsigned int count = gSuppressedTeardowns.fetch_add(1, std::memory_order_relaxed) + 1;
    if (count <= 4) {
        __android_log_print(ANDROID_LOG_WARN, kTag,
                "suppressed %s(%p), count=%u", operation, nativeObject, count);
    }
}

int keepMutexAlive(pthread_mutex_t* mutex) {
    gTeardownStarted.store(true, std::memory_order_release);
    logSuppressed("pthread_mutex_destroy", mutex);
    return 0;
}

int keepConditionAlive(pthread_cond_t* condition) {
    // This is the first known destructor in this title's onexit path.  It arms
    // the libc-level guard before AudioTrack/HWUI workers begin their teardown.
    gTeardownStarted.store(true, std::memory_order_release);
    logSuppressed("pthread_cond_destroy", condition);
    return 0;
}

int keepLibcMutexAliveDuringExit(pthread_mutex_t* mutex) {
    if (gTeardownStarted.load(std::memory_order_acquire)) {
        logSuppressed("libc pthread_mutex_destroy", mutex);
        return 0;
    }
    return gOriginalLibcMutexDestroy == nullptr ? 0 : gOriginalLibcMutexDestroy(mutex);
}

int keepLibcConditionAliveDuringExit(pthread_cond_t* condition) {
    if (gTeardownStarted.load(std::memory_order_acquire)) {
        logSuppressed("libc pthread_cond_destroy", condition);
        return 0;
    }
    return gOriginalLibcConditionDestroy == nullptr ? 0 : gOriginalLibcConditionDestroy(condition);
}

bool installLibcTeardownGuard(GlossHookByName hookByName) {
    void* originalMutexDestroy = nullptr;
    void* originalConditionDestroy = nullptr;
    void* mutexDestroyHook = hookByName(kLibcLibrary, "pthread_mutex_destroy",
            reinterpret_cast<void*>(keepLibcMutexAliveDuringExit), &originalMutexDestroy, nullptr);
    void* conditionDestroyHook = hookByName(kLibcLibrary, "pthread_cond_destroy",
            reinterpret_cast<void*>(keepLibcConditionAliveDuringExit), &originalConditionDestroy, nullptr);
    if (mutexDestroyHook == nullptr || conditionDestroyHook == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                "libc teardown guard incomplete mutex=%p cond=%p", mutexDestroyHook,
                conditionDestroyHook);
        return false;
    }
    gOriginalLibcMutexDestroy = reinterpret_cast<MutexDestroy>(originalMutexDestroy);
    gOriginalLibcConditionDestroy = reinterpret_cast<ConditionDestroy>(originalConditionDestroy);
    return true;
}

bool installDirectorEndGuard(const char* gameLibrary) {
    if (gameLibrary == nullptr || gameLibrary[0] == '\0') return false;

    std::lock_guard<std::mutex> lock(gInstallMutex);
    if (gInstalled.load(std::memory_order_acquire)) return true;

    void* hookLibrary = dlopen(kHookLibrary, RTLD_NOW | RTLD_LOCAL);
    if (hookLibrary == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "dlopen %s failed: %s", kHookLibrary, dlerror());
        return false;
    }
    auto hookByName = reinterpret_cast<GlossHookByName>(dlsym(hookLibrary, "GlossHookByName"));
    auto hookByAddress = reinterpret_cast<GlossHookAddrByName>(dlsym(hookLibrary, "GlossHookAddrByName"));
    if (hookByName == nullptr || hookByAddress == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                "GlossHook API unavailable name=%p address=%p error=%s", hookByName,
                hookByAddress, dlerror());
        return false;
    }

    void* original = nullptr;
    void* hook = hookByName(gameLibrary, kDirectorEndSymbol,
            reinterpret_cast<void*>(terminateKrkrProcess), &original, nullptr);
    if (hook == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "failed to hook %s in %s",
                kDirectorEndSymbol, gameLibrary);
        return false;
    }

    uintptr_t mutexDestroyPlt = 0;
    uintptr_t condDestroyPlt = 0;
    if (std::strcmp(gameLibrary, "libgame.so") == 0) {
        mutexDestroyPlt = kGame139MutexDestroyPlt;
        condDestroyPlt = kGame139CondDestroyPlt;
    } else if (std::strcmp(gameLibrary, "libgame134.so") == 0) {
        mutexDestroyPlt = kGame134MutexDestroyPlt;
        condDestroyPlt = kGame134CondDestroyPlt;
    } else {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "unsupported game library: %s", gameLibrary);
        return false;
    }

    // GlossHookByName resolves only symbols defined by a library. pthread is a
    // PLT import, so hook the two resolved PLT stubs by their libgame offsets.
    void* originalMutexDestroy = nullptr;
    void* originalCondDestroy = nullptr;
    void* mutexDestroyHook = hookByAddress(gameLibrary, mutexDestroyPlt,
            reinterpret_cast<void*>(keepMutexAlive), &originalMutexDestroy, false, 3, nullptr);
    void* condDestroyHook = hookByAddress(gameLibrary, condDestroyPlt,
            reinterpret_cast<void*>(keepConditionAlive), &originalCondDestroy, false, 3, nullptr);
    if (mutexDestroyHook == nullptr || condDestroyHook == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                "teardown guard incomplete mutex=%p cond=%p", mutexDestroyHook, condDestroyHook);
        return false;
    }
    if (!installLibcTeardownGuard(hookByName)) return false;

    gOriginalDirectorEnd = reinterpret_cast<DirectorEnd>(original);
    gInstalled.store(true, std::memory_order_release);
    __android_log_print(ANDROID_LOG_INFO, kTag,
            "guard installed for %s Director::end=%p game_mutex=%p game_cond=%p libc_mutex=%p libc_cond=%p",
            gameLibrary, reinterpret_cast<void*>(gOriginalDirectorEnd), mutexDestroyHook,
            condDestroyHook, reinterpret_cast<void*>(gOriginalLibcMutexDestroy),
            reinterpret_cast<void*>(gOriginalLibcConditionDestroy));
    return true;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_bridge_NativeBridge_installExitGuard(JNIEnv* env, jclass, jstring gameLibrary) {
    if (gameLibrary == nullptr) return JNI_FALSE;
    const char* library = env->GetStringUTFChars(gameLibrary, nullptr);
    if (library == nullptr) return JNI_FALSE;
    const bool installed = installDirectorEndGuard(library);
    env->ReleaseStringUTFChars(gameLibrary, library);
    return installed ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    // Do not depend on the Java bridge callback being reached. Cocos calls an
    // Activity's native-library hook while it is constructing the Activity, and
    // failures there can be swallowed by framework/engine code. At this point
    // Kirikiroid139/134 has already loaded game + kirikiroid3, so install here.
    __android_log_print(ANDROID_LOG_INFO, kTag,
            "JNI_OnLoad: installing Director::end guard");
    bool installed = installDirectorEndGuard("libgame.so");
    if (!installed) installed = installDirectorEndGuard("libgame134.so");
    __android_log_print(installed ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, kTag,
            "JNI_OnLoad: Director::end guard installed=%d", installed);
    return JNI_VERSION_1_6;
}
