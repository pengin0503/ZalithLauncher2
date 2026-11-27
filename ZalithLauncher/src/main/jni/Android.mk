LOCAL_PATH := $(call my-dir)
HERE_PATH := $(LOCAL_PATH)

# include $(HERE_PATH)/crash_dump/libbase/Android.mk
# include $(HERE_PATH)/crash_dump/libbacktrace/Android.mk
# include $(HERE_PATH)/crash_dump/debuggerd/Android.mk


LOCAL_PATH := $(HERE_PATH)

$(call import-module,prefab/bytehook)
LOCAL_PATH := $(HERE_PATH)


# Stage 5-3: arm64-v8a release optimization for the Lite fork.
# The Lite APK ships arm64-v8a only (-Darch=arm64) and the target device
# (Reno13 A) uses Cortex-A78-class big cores + Cortex-A55 little cores, so the
# flags are gated on LITE_BUILD=1 (passed by Gradle only when -PliteBuild=true)
# AND TARGET_ARCH_ABI; every non-lite build, including the stock arm64 one,
# keeps upstream's generic codegen.
#
# -mcpu=cortex-a78 makes clang emit ARMv8.2-A code (Cortex-A78 implements
# ARMv8.2-A). The little Cortex-A55 cores also implement ARMv8.2-A, so the
# same objects run on both clusters. This is the approved Reno13 A
# exclusivity trade-off: the resulting arm64 objects may not run on older
# arm64 devices.
#
# -flto is deliberately left out (LTO + ByteHook prefab is a known risk) and
# -ffast-math is deliberately not used (JNI/audio code needs IEEE semantics).
ifeq ($(LITE_BUILD)-$(TARGET_ARCH_ABI),1-arm64-v8a)
LITE_OPT_CFLAGS := -O3 -ffunction-sections -fdata-sections -mcpu=cortex-a78
LITE_OPT_LDFLAGS := -Wl,--gc-sections
# Opt in only for modules whose externally-resolved symbols are JNIEXPORT or
# already annotated visibility("default"); see the per-module notes below.
LITE_OPT_HIDDEN := -fvisibility=hidden
else
LITE_OPT_CFLAGS :=
LITE_OPT_LDFLAGS :=
LITE_OPT_HIDDEN :=
endif


include $(CLEAR_VARS)
LOCAL_LDLIBS := -ldl -llog -landroid
LOCAL_MODULE := pojavexec
LOCAL_SHARED_LIBRARIES := driver_helper
LOCAL_CFLAGS += -rdynamic
# -fvisibility=hidden is intentionally NOT applied here: input_bridge_v3.c's
# JNI_OnLoad() has no JNIEXPORT (ART resolves it by name from .dynsym), and
# exithook links against pojavexec's plain pojav_environ / nominal_exit
# symbols. The dlsym(RTLD_DEFAULT, ...) in java_exec_hooks.c resolves
# libjava.so's symbol, not one of ours, so it is not the reason.
LOCAL_CFLAGS += $(LITE_OPT_CFLAGS)
LOCAL_CPPFLAGS += $(LITE_OPT_CFLAGS)
LOCAL_LDFLAGS += $(LITE_OPT_LDFLAGS)
LOCAL_C_INCLUDES := $(LOCAL_PATH)
LOCAL_SRC_FILES := \
    bigcoreaffinity.c \
    egl_bridge.c \
    ctxbridges/br_loader.c \
    ctxbridges/gl_bridge.c \
    ctxbridges/osm_bridge.c \
    ctxbridges/egl_loader.c \
    ctxbridges/osmesa_loader.c \
    ctxbridges/swap_interval_no_egl.c \
    ctxbridges/virgl_bridge.c \
    environ/environ.c \
    glfw/event_wait.c \
    glfw/glfw_event_wait.c \
    logger/logger.c \
    input_bridge_v3.c \
    jre_launcher.c \
    utils.c \
    stdio_is.c \
    java_exec_hooks.c \
    lwjgl_dlopen_hook.c \

ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
LOCAL_CFLAGS += -DADRENO_POSSIBLE
LOCAL_LDLIBS += -lEGL -lGLESv2
endif
include $(BUILD_SHARED_LIBRARY)


include $(CLEAR_VARS)
LOCAL_LDLIBS := -ldl -llog
LOCAL_MODULE := vulkan_check
LOCAL_SHARED_LIBRARIES := driver_helper
LOCAL_SRC_FILES := vulkan_checker.c
# JNI-only module: every symbol resolved from outside is JNIEXPORT.
LOCAL_CFLAGS += $(LITE_OPT_CFLAGS) $(LITE_OPT_HIDDEN)
LOCAL_CPPFLAGS += $(LITE_OPT_CFLAGS) $(LITE_OPT_HIDDEN)
LOCAL_LDFLAGS += $(LITE_OPT_LDFLAGS)
include $(BUILD_SHARED_LIBRARY)

ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
LOCAL_CFLAGS += -DADRENO_POSSIBLE
endif


include $(CLEAR_VARS)
LOCAL_MODULE := exithook
LOCAL_LDLIBS := -ldl -llog
LOCAL_SHARED_LIBRARIES := bytehook pojavexec
LOCAL_SRC_FILES := exit_hook.c \
    sdl_hook.c \
    sdl_dlopen_hook.c
# JNI-only module: every externally-resolved symbol is JNIEXPORT.
LOCAL_CFLAGS += $(LITE_OPT_CFLAGS) $(LITE_OPT_HIDDEN)
LOCAL_CPPFLAGS += $(LITE_OPT_CFLAGS) $(LITE_OPT_HIDDEN)
LOCAL_LDFLAGS += $(LITE_OPT_LDFLAGS)
include $(BUILD_SHARED_LIBRARY)


include $(CLEAR_VARS)
LOCAL_LDLIBS := -ldl -llog -landroid
LOCAL_MODULE := driver_helper
LOCAL_SRC_FILES := \
    driver_helper/driver_helper.c \
    driver_helper/nsbypass.c
# -g and -rdynamic are kept for every build: the optimization flags below are
# the only part that is lite-specific.
# -fvisibility=hidden is intentionally NOT applied here: loadTurnipVulkan() is
# a plain (non-JNIEXPORT) C symbol linked by pojavexec and vulkan_check from
# this shared library, so hiding it would break their link.
LOCAL_CFLAGS += -g -rdynamic $(LITE_OPT_CFLAGS)
LOCAL_CPPFLAGS += $(LITE_OPT_CFLAGS)
LOCAL_LDFLAGS += $(LITE_OPT_LDFLAGS)

ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
LOCAL_CFLAGS += -DADRENO_POSSIBLE
LOCAL_LDLIBS += -lEGL -lGLESv2
endif
include $(BUILD_SHARED_LIBRARY)


include $(CLEAR_VARS)
LOCAL_MODULE := linkerhook
LOCAL_SRC_FILES := \
    linkerhook/linkerhook.cpp \
    linkerhook/linkerns.c
LOCAL_LDFLAGS := -z global
# linkerhook's cross-library exports are already annotated
# visibility("default") in linkerns.c, so -fvisibility=hidden only hides its
# internal C++ helpers.
LOCAL_CFLAGS += $(LITE_OPT_CFLAGS) $(LITE_OPT_HIDDEN)
LOCAL_CPPFLAGS += $(LITE_OPT_CFLAGS) $(LITE_OPT_HIDDEN)
LOCAL_LDFLAGS += $(LITE_OPT_LDFLAGS)
include $(BUILD_SHARED_LIBRARY)


include $(CLEAR_VARS)
LOCAL_MODULE := pojavexec_awt
LOCAL_SRC_FILES := \
    awt_bridge.c
# -fvisibility=hidden is intentionally NOT applied here: awt_bridge.c's
# JNI_OnLoad() has no JNIEXPORT and initializes the AWT bridge globals.
LOCAL_CFLAGS += $(LITE_OPT_CFLAGS)
LOCAL_CPPFLAGS += $(LITE_OPT_CFLAGS)
LOCAL_LDFLAGS += $(LITE_OPT_LDFLAGS)
include $(BUILD_SHARED_LIBRARY)


include $(CLEAR_VARS)
LOCAL_MODULE := awt_headless
include $(BUILD_SHARED_LIBRARY)


LOCAL_PATH := $(HERE_PATH)/awt_xawt
include $(CLEAR_VARS)
LOCAL_MODULE := awt_xawt
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)
LOCAL_SHARED_LIBRARIES := awt_headless
LOCAL_SRC_FILES := xawt_fake.c
# JNI-only stub: every entry point is JNIEXPORT.
LOCAL_CFLAGS += $(LITE_OPT_CFLAGS) $(LITE_OPT_HIDDEN)
LOCAL_CPPFLAGS += $(LITE_OPT_CFLAGS) $(LITE_OPT_HIDDEN)
LOCAL_LDFLAGS += $(LITE_OPT_LDFLAGS)
include $(BUILD_SHARED_LIBRARY)


# delete fake libs after linked
$(info $(shell (rm $(HERE_PATH)/../jniLibs/*/libawt_headless.so)))


# 模块：jsound - OpenJDK libjsound 核心 + OpenAL 平台后端
# 为四套 JRE（jre8/17/21/25）提供 javax.sound.sampled 的原生实现，
# 核心 .c/.h 文件 vendor 自 openjdk/jdk17u
# （https://github.com/openjdk/jdk17u/blob/master/src/java.desktop/share/native/libjsound，
# GPL-2.0 + Classpath 异常，与本项目 GPL-3.0 兼容；8u 与 17u 的该目录字节级一致，
# 故一套源码可同时服务两代 ABI）。PORT/MIDI 子系统关闭（桩返回 0 设备），
# 音频路径由 jsound_openal.c 桥接 OpenAL，JDK8 的 Platform 额外 native 由 jdk8_compat.c 补齐
LOCAL_PATH := $(HERE_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := jsound
LOCAL_CFLAGS += \
    -DX_PLATFORM=X_LINUX \
    -D_LITTLE_ENDIAN \
    -DUSE_DAUDIO=TRUE \
    -DUSE_PORTS=FALSE \
    -DUSE_PLATFORM_MIDI_OUT=FALSE \
    -DUSE_PLATFORM_MIDI_IN=FALSE
# JNI-only module: every entry point the JRE resolves is JNIEXPORT.
LOCAL_CFLAGS += $(LITE_OPT_CFLAGS) $(LITE_OPT_HIDDEN)
LOCAL_CPPFLAGS += $(LITE_OPT_CFLAGS) $(LITE_OPT_HIDDEN)
LOCAL_LDFLAGS += $(LITE_OPT_LDFLAGS)
LOCAL_C_INCLUDES := $(HERE_PATH)/jsound
LOCAL_LDLIBS := -llog -ldl
LOCAL_SRC_FILES := \
    jsound/Utilities.c \
    jsound/Platform.c \
    jsound/DirectAudioDevice.c \
    jsound/DirectAudioDeviceProvider.c \
    jsound/PortMixer.c \
    jsound/PortMixerProvider.c \
    jsound/MidiOutDevice.c \
    jsound/MidiOutDeviceProvider.c \
    jsound/MidiInDevice.c \
    jsound/MidiInDeviceProvider.c \
    jsound/PlatformMidi.c \
    jsound/jsound_openal.c \
    jsound/jdk8_compat.c
include $(BUILD_SHARED_LIBRARY)


LOCAL_PATH := $(HERE_PATH)/flite

include $(CLEAR_VARS)
LOCAL_MODULE := flite
LOCAL_SRC_FILES := flite_bridge.c
LOCAL_LDLIBS := -llog
# JNI-only module (fcl_flite_init/fcl_flite_say are JNIEXPORT).
LOCAL_CFLAGS += $(LITE_OPT_CFLAGS) $(LITE_OPT_HIDDEN)
LOCAL_CPPFLAGS += $(LITE_OPT_CFLAGS) $(LITE_OPT_HIDDEN)
LOCAL_LDFLAGS += $(LITE_OPT_LDFLAGS)
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := fliteWrapper
LOCAL_SRC_FILES := flite_wrapper.c
LOCAL_SHARED_LIBRARIES := flite
LOCAL_CFLAGS += $(LITE_OPT_CFLAGS) $(LITE_OPT_HIDDEN)
LOCAL_CPPFLAGS += $(LITE_OPT_CFLAGS) $(LITE_OPT_HIDDEN)
LOCAL_LDFLAGS += $(LITE_OPT_LDFLAGS)
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := flite_cmu_us_kal16
LOCAL_SRC_FILES := flite_cmu_us_kal16.c
LOCAL_CFLAGS += $(LITE_OPT_CFLAGS) $(LITE_OPT_HIDDEN)
LOCAL_CPPFLAGS += $(LITE_OPT_CFLAGS) $(LITE_OPT_HIDDEN)
LOCAL_LDFLAGS += $(LITE_OPT_LDFLAGS)
include $(BUILD_SHARED_LIBRARY)

LOCAL_PATH := $(HERE_PATH)
