#include <jni.h>
#include <sys/types.h>
#include <stdbool.h>
#include <unistd.h>
#include <pthread.h>
#include <stdio.h>
#include <fcntl.h>
#include <string.h>
#include <errno.h>
#include <stdlib.h>
#include <time.h>
#include <environ/environ.h>

#include "stdio_is.h"

//
// Created by maks on 17.02.21.
//

static volatile jobject exitTrap_ctx;
static volatile jclass exitTrap_exitClass;
static volatile jmethodID exitTrap_exitMethod;
static JavaVM *exitTrap_jvm;

static int pfd[2];
static pthread_t logger;
static jmethodID logger_onEventLogged;
static volatile jobject logListener = NULL;
static int latestlog_fd = -1;

// F1-6: batch fdatasync() instead of syncing on every log line.
//
// fdatasync() is only needed to survive power loss / a kernel panic. For a
// process crash (the common case, and the one this file is read after) the
// bytes we write() are already in the page cache and remain readable without
// any sync. So delaying the sync risks losing at most the last <200ms or
// <64KB of log on power loss, while cutting the number of syncs (0.2-2ms on
// UFS, vs ~1-3us for write()) by orders of magnitude. Explicit flush points
// below (log re-init, logger start failure, JVM exit) still sync immediately.
#define LOG_SYNC_BYTES_THRESHOLD (64 * 1024)
#define LOG_SYNC_INTERVAL_NS (200LL * 1000LL * 1000LL)

// Guards the batching bookkeeping only. recordBuffer() can be called from the
// logger thread and from Java threads via LoggerBridge.append(), so the
// counters need a lock; the write() itself intentionally stays outside it (the
// writer is not restructured, only the sync decisions are serialized).
static pthread_mutex_t logSyncLock = PTHREAD_MUTEX_INITIALIZER;
static size_t unsyncedBytes = 0;
static struct timespec lastSyncTime;

// Called after every successful write(): sync only once enough data or enough
// time has accumulated since the last sync.
static void logSyncAfterWrite(size_t written) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);

    pthread_mutex_lock(&logSyncLock);
    unsyncedBytes += written;

    long long elapsedNs =
            (long long) (now.tv_sec - lastSyncTime.tv_sec) * 1000000000LL +
            (long long) (now.tv_nsec - lastSyncTime.tv_nsec);

    // 0 is the failed-open sentinel set by LoggerBridge_start(), not a log fd.
    if (latestlog_fd > 0 &&
        (unsyncedBytes >= LOG_SYNC_BYTES_THRESHOLD || elapsedNs >= LOG_SYNC_INTERVAL_NS)) {
        fdatasync(latestlog_fd);
        unsyncedBytes = 0;
        lastSyncTime = now;
    }
    pthread_mutex_unlock(&logSyncLock);
}

// Explicit flush point: force out any pending bytes right now (log close /
// re-init, logger start failure, JVM exit).
static void logSyncNow(void) {
    pthread_mutex_lock(&logSyncLock);
    if (latestlog_fd > 0 && unsyncedBytes > 0) {
        fdatasync(latestlog_fd);
        unsyncedBytes = 0;
    }
    clock_gettime(CLOCK_MONOTONIC, &lastSyncTime);
    pthread_mutex_unlock(&logSyncLock);
}

// Exit-path flush. nominal_exit() is also reached via jre_launcher.c's
// abort_waiter: the signalled thread spins forever in abort_waiter_handler()
// while the waiter thread calls nominal_exit(). If the signal interrupted a
// thread inside logSyncAfterWrite() (holding logSyncLock), taking the lock here
// would block forever and the process would never exit. The batching counters
// no longer matter once the process is going away, so sync without the lock.
static void logSyncOnExit(void) {
    int fd = latestlog_fd;
    if (fd > 0) fdatasync(fd);
}

// Start batching from a clean slate whenever a new log file is opened.
static void logSyncReset(void) {
    pthread_mutex_lock(&logSyncLock);
    unsyncedBytes = 0;
    clock_gettime(CLOCK_MONOTONIC, &lastSyncTime);
    pthread_mutex_unlock(&logSyncLock);
}

static bool recordBuffer(char* buf, ssize_t len) {
    if (strstr(buf, "Session ID is")) return false;
    if (latestlog_fd != -1)
    {
        // write() to a regular file may be short or interrupted. Retry the tail
        // so the log line is not silently truncated, and only count bytes that
        // actually reached the file in the batching bookkeeping.
        const char *cursor = buf;
        size_t remaining = (size_t) len;
        size_t total_written = 0;
        while (remaining > 0)
        {
            ssize_t written = write(latestlog_fd, cursor, remaining);
            if (written > 0)
            {
                total_written += (size_t) written;
                cursor += written;
                remaining -= (size_t) written;
                continue;
            }
            if (written < 0 && errno == EINTR) continue;
            break; // 0 or a real error: stop instead of spinning forever
        }
        if (total_written > 0) logSyncAfterWrite(total_written);
    }
    return true;
}

static void *logger_thread() {
    JNIEnv *env;
    jstring writeString;

    JavaVM* dvm = pojav_environ->dalvikJavaVMPtr;
    (*dvm)->AttachCurrentThread(dvm, &env, NULL);

    ssize_t  rsize;
    char buf[2050];

    while ((rsize = read(pfd[0], buf, sizeof(buf)-1)) > 0)
    {
        bool shouldRecordString = recordBuffer(buf, rsize); //record with newline int latestlog
        if (buf[rsize-1]=='\n')
        {
            rsize=rsize-1; //truncate
        }
        buf[rsize]=0x00;
        if (shouldRecordString && logListener != NULL)
        {
            writeString = (*env)->NewStringUTF(env, buf); //send to app without newline
            (*env)->CallVoidMethod(env, logListener, logger_onEventLogged, writeString);
            (*env)->DeleteLocalRef(env, writeString);
        }
    }
    (*dvm)->DetachCurrentThread(dvm);
    return NULL;
}

JNIEXPORT void JNICALL
Java_com_movtery_zalithlauncher_bridge_LoggerBridge_start(JNIEnv *env, __attribute((unused)) jclass clazz, jstring logPath) {
    if (latestlog_fd != -1)
    {
        logSyncNow(); // explicit flush point: never leave the old log unsynced
        int localfd = latestlog_fd;
        latestlog_fd = -1;
        close(localfd);
    }

    if (logger_onEventLogged == NULL)
    {
        jclass eventLogListener = (*env)->FindClass(env, "com/movtery/zalithlauncher/bridge/LoggerBridge$EventLogListener");
        logger_onEventLogged = (*env)->GetMethodID(env, eventLogListener, "onEventLogged", "(Ljava/lang/String;)V");
    }

    jclass ioeClass = (*env)->FindClass(env, "java/io/IOException");


    setvbuf(stdout, 0, _IOLBF, 0); // make stdout line-buffered
    setvbuf(stderr, 0, _IONBF, 0); // make stderr unbuffered

    /* create the pipe and redirect stdout and stderr */
    pipe(pfd);
    dup2(pfd[1], 1);
    dup2(pfd[1], 2);

    /* open latestlog.txt for writing */
    const char* logFilePath = (*env)->GetStringUTFChars(env, logPath, NULL);
    latestlog_fd = open(logFilePath, O_WRONLY | O_TRUNC);

    if (latestlog_fd == -1)
    {
        latestlog_fd = 0;
        (*env)->ThrowNew(env, ioeClass, strerror(errno));
        return;
    }
    (*env)->ReleaseStringUTFChars(env, logPath, logFilePath);

    // Start batching from a clean slate for the freshly opened log file.
    logSyncReset();

    /* spawn the logging thread */
    int result = pthread_create(&logger, 0, logger_thread, 0);

    if (result != 0)
    {
        logSyncNow(); // explicit flush point before closing the log
        close(latestlog_fd);
        (*env)->ThrowNew(env, ioeClass, strerror(result));
    }
    pthread_detach(logger);
}

_Noreturn void nominal_exit(int code, bool is_signal) {
    // Explicit flush point: the JVM is going away, so make sure the tail of the
    // log is on disk even if it is below the batching thresholds.
    logSyncOnExit();

    JNIEnv *env;
    jint errorCode = (*exitTrap_jvm)->GetEnv(exitTrap_jvm, (void**)&env, JNI_VERSION_1_6);

    if (errorCode == JNI_EDETACHED)
        errorCode = (*exitTrap_jvm)->AttachCurrentThread(exitTrap_jvm, &env, NULL);

    if (errorCode != JNI_OK)
        killpg(getpgrp(), SIGTERM);

    (*env)->CallStaticVoidMethod(env, exitTrap_exitClass, exitTrap_exitMethod, code, is_signal);

    // Delete the reference, not gonna need 'em later anyway
    (*env)->DeleteGlobalRef(env, exitTrap_ctx);
    (*env)->DeleteGlobalRef(env, exitTrap_exitClass);

    // A hat trick, if you will
    // Call the Android System.exit() to perform Android's shutdown hooks and do a
    // fully clean exit.
    // After doing this, either of these will happen:
    // 1. Runtime calls exit() for real and it will be handled by ByteHook's recurse handler
    // and redirected back to the OS
    // 2. Zygote sends SIGTERM (no handling necessary, the process perishes)
    // 3. A different thread calls exit() and the hook will go through the exit_tripped path
    jclass systemClass = (*env)->FindClass(env,"java/lang/System");
    jmethodID exitMethod = (*env)->GetStaticMethodID(env, systemClass, "exit", "(I)V");
    (*env)->CallStaticVoidMethod(env, systemClass, exitMethod, 0);
    // System.exit() should not ever return, but the compiler doesn't know about that
    // so put a while loop here
    while(1) {}
}

JNIEXPORT void JNICALL Java_com_movtery_zalithlauncher_bridge_LoggerBridge_append(JNIEnv *env, __attribute((unused)) jclass clazz, jstring text) {
    jsize appendStringLength = (*env)->GetStringUTFLength(env, text);
    char newChars[appendStringLength+2];
    (*env)->GetStringUTFRegion(env, text, 0, (*env)->GetStringLength(env, text), newChars);
    newChars[appendStringLength] = '\n';
    newChars[appendStringLength+1] = 0;
    if (recordBuffer(newChars, appendStringLength+1) && logListener != NULL)
        (*env)->CallVoidMethod(env, logListener, logger_onEventLogged, text);
}

JNIEXPORT void JNICALL
Java_com_movtery_zalithlauncher_bridge_LoggerBridge_setListener(JNIEnv *env, __attribute((unused)) jclass clazz, jobject log_listener) {
    jobject logListenerLocal = logListener;

    if (log_listener == NULL) logListener = NULL;
    else logListener = (*env)->NewGlobalRef(env, log_listener);

    if (logListenerLocal != NULL && logListenerLocal != logListener)
        (*env)->DeleteGlobalRef(env, logListenerLocal);
}

JNIEXPORT void JNICALL
Java_com_movtery_zalithlauncher_bridge_ZLBridge_setupExitMethod(JNIEnv *env, jclass clazz,
                                                        jobject context) {
    exitTrap_ctx = (*env)->NewGlobalRef(env,context);
    (*env)->GetJavaVM(env,&exitTrap_jvm);
    exitTrap_exitClass = (*env)->NewGlobalRef(env,(*env)->FindClass(env,"com/movtery/zalithlauncher/bridge/ZLNativeInvoker"));
    exitTrap_exitMethod = (*env)->GetStaticMethodID(env, exitTrap_exitClass, "jvmExit", "(IZ)V");
}