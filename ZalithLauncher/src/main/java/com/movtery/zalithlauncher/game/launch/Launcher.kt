/*
 * Zalith Launcher 2
 * Copyright (C) 2025-2026 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.launch

import android.content.Context
import android.os.Build
import android.os.LocaleList
import android.system.Os
import android.util.ArrayMap
import androidx.annotation.CallSuper
import androidx.compose.ui.unit.IntSize
import com.movtery.zalithlauncher.BuildKeys
import com.movtery.zalithlauncher.bridge.LoggerBridge
import com.movtery.zalithlauncher.bridge.ZLBridge
import com.movtery.zalithlauncher.bridge.ZLNativeInvoker
import com.movtery.zalithlauncher.components.Components
import com.movtery.zalithlauncher.components.InstallableItem
import com.movtery.zalithlauncher.components.UnpackComponentsTask
import com.movtery.zalithlauncher.context.GlobalContext
import com.movtery.zalithlauncher.game.multirt.CdsArchive
import com.movtery.zalithlauncher.game.multirt.Runtime
import com.movtery.zalithlauncher.game.multirt.RuntimesManager
import com.movtery.zalithlauncher.game.path.getGameHome
import com.movtery.zalithlauncher.game.plugin.ffmpeg.FFmpegPluginManager
import com.movtery.zalithlauncher.game.plugin.natives.NativePluginManager
import com.movtery.zalithlauncher.game.plugin.renderer.RendererPluginManager
import com.movtery.zalithlauncher.path.LibPath
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.setting.GameSettings
import com.movtery.zalithlauncher.setting.RendererSettings
import com.movtery.zalithlauncher.setting.unit.getOrMin
import com.movtery.zalithlauncher.utils.device.Architecture
import com.movtery.zalithlauncher.utils.device.Architecture.ARCH_X86
import com.movtery.zalithlauncher.utils.device.Architecture.is64BitsDevice
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.network.getSystemDnsServerAddresses
import com.movtery.zalithlauncher.utils.string.splitPreservingQuotes
import com.oracle.dalvik.VMLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.TimeZone

private const val TAG = "Launcher"

abstract class Launcher(
    val onExit: (code: Int, isSignal: Boolean) -> Unit,
    val openPath: (folder: File) -> Unit
) {
    lateinit var runtime: Runtime
        protected set

    /** 当前启动版本要求的 LWJGL 版本（见 [detectLwjglVersion]），0 = 未探测/默认 */
    protected var lwjglVersion: Int = 0
        private set

    /**
     * 当前启动版本的 LWJGL natives 目录
     */
    protected lateinit var lwjglNativesDir: String

    /**
     * 初始化 LWJGL 组件
     */
    protected suspend fun initLwjglComponent(context: Context, version: Int) {
        check(!::lwjglNativesDir.isInitialized) { "LWJGL component has already been initialized" }
        lwjglVersion = version
        lwjglNativesDir = File(
            PathManager.DIR_COMPONENTS,
            "lwjgl/${lwjglVersionDir(version)}/natives/${Architecture.archAsStringAndroid(Architecture.getDeviceArchitecture())}"
        ).absolutePath
        verifyLwjglNatives(context)
    }

    private suspend fun verifyLwjglNatives(context: Context) {
        val versionDir = lwjglVersionDir(lwjglVersion)
        val component = Components.entries.firstOrNull {
            it.component == "lwjgl/$versionDir"
        } ?: error("Unrecognized LWJGL component: lwjgl/$versionDir")
        val task = UnpackComponentsTask(context, component)

        withContext(Dispatchers.IO) {
            if (task.checkState() == InstallableItem.State.FINISHED) {
                LoggerBridge.appendInfo("LWJGL: LWJGL $versionDir passed SHA-1 verification")
                return@withContext
            }

            LoggerBridge.appendInfo("LWJGL: LWJGL $versionDir failed verification, re-unpacking the component now")
            task.run()
            check(task.checkState() == InstallableItem.State.FINISHED) {
                "LWJGL component failed SHA-1 verification after re-unpack: $versionDir"
            }
            LoggerBridge.appendInfo("LWJGL: LWJGL $versionDir passed SHA-1 verification after re-unpack")
        }
    }

    private val runtimeHome: String by lazy {
        RuntimesManager.getRuntimeHome(runtime.name).absolutePath
    }

    private fun getJavaHome() = if (runtime.isJDK8) "$runtimeHome/jre" else runtimeHome

    /**
     * 大核 CPU id 列表（按 cpuinfo_max_freq 的最高频簇识别，至少 2 个才算命中）。
     * null 表示系统未暴露频率信息或识别失败，此时保持原有行为（不设置 affinity）。
     */
    private val bigCoreCpuIds: List<Int>? by lazy { detectBigCoreCpuIds() }

    private fun detectBigCoreCpuIds(): List<Int>? {
        val cpuDirs = File("/sys/devices/system/cpu").listFiles { file ->
            file.isDirectory && file.name.matches(Regex("cpu\\d+"))
        } ?: return null

        val frequencies = cpuDirs.mapNotNull { dir ->
            val cpuId = dir.name.removePrefix("cpu").toIntOrNull() ?: return@mapNotNull null
            val maxFreq = runCatching {
                File(dir, "cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull() ?: return@mapNotNull null
            if (maxFreq <= 0L) null else cpuId to maxFreq
        }.sortedBy { it.first }

        if (frequencies.size < 2) return null

        // 与最高频率相差 10% 以内的核心视为同一大核簇（CORE_PLAN_A §3）
        val highestFreq = frequencies.maxOf { it.second }
        val bigCoreIds = frequencies
            .filter { it.second >= highestFreq * 9 / 10 }
            .map { it.first }
        return bigCoreIds.takeIf { it.size >= 2 }
    }

    abstract suspend fun launch(screenSize: IntSize): Int
    abstract fun chdir(): String
    abstract fun getLogFile(): File
    abstract fun exit()

    /**
     * 游戏目录（.minecraft），默认为当前选择的游戏目录
     */
    protected open fun getMinecraftPath(): String = getGameHome()

    protected suspend fun launchJvm(
        context: Context,
        jvmArgs: List<String>,
        userHome: String,
        userArgs: String,
        screenSize: IntSize,
        useLocalLanguage: Boolean = true
    ): Int {
        ZLNativeInvoker.staticLauncher = this

        ZLBridge.setLdLibraryPath(getRuntimeLibraryPath())

        LoggerBridge.appendTitle("Env Map")
        setEnv(screenSize)

        LoggerBridge.appendTitle("DLOPEN Java Runtime")
        dlopenJavaRuntime()

        dlopenEngine()

        return launchJavaVM(
            context = context,
            jvmArgs = jvmArgs,
            userHome = userHome,
            userArgs = userArgs,
            screenSize = screenSize,
            useLocalLanguage = useLocalLanguage
        )
    }

    //伪 suspend 函数，等待 JVM 的退出代码
    private suspend fun launchJavaVM(
        context: Context,
        jvmArgs: List<String>,
        userHome: String,
        userArgs: String,
        screenSize: IntSize,
        useLocalLanguage: Boolean
    ): Int {
        val args = getJavaArgs(
            userHome = userHome,
            userArgumentsString = userArgs,
            screenSize = screenSize,
            useLocalLanguage = useLocalLanguage
        ).toMutableList()
        progressFinalUserArgs(args)

        args.addAll(jvmArgs)
        args.add(0, "$runtimeHome/bin/java")

        LoggerBridge.appendTitle("JVM Args")
        val iterator = args.iterator()
        while (iterator.hasNext()) {
            val arg = iterator.next()
            if (arg.startsWith("--accessToken") && iterator.hasNext()) {
                iterator.next()
                LoggerBridge.appendInfo(arg)
                LoggerBridge.appendInfo("********************")
                continue
            }
            LoggerBridge.appendInfo(arg)
        }

        ZLBridge.setupExitMethod(context.applicationContext)
        ZLBridge.initializeGameExitHook()
        ZLBridge.chdir(chdir())

        val exitCode = VMLauncher.launchJVM(args.toTypedArray())
        LoggerBridge.append("Java Exit code: $exitCode")
        return exitCode
    }

    /**
     * 添加 JVM 参数
     */
    protected open fun MutableMap<String, String>.putJavaArgs() {}

    private fun getJavaArgs(
        userHome: String,
        userArgumentsString: String,
        screenSize: IntSize,
        useLocalLanguage: Boolean
    ): List<String> {
        val userArguments = userArgumentsString.splitPreservingQuotes().toMutableList()
        val resolvFile = ensureDNSConfig()

        val overridableArguments = mutableMapOf<String, String>().apply {
            put("java.home", getJavaHome())
            put("java.io.tmpdir", PathManager.DIR_CACHE.absolutePath)
            put("jna.boot.library.path", PathManager.DIR_NATIVE_LIB)
            put("user.home", userHome)
            if (useLocalLanguage) {
                put("user.language", System.getProperty("user.language") ?: "en")
                put("user.country", Locale.getDefault().country)
            }
            put("user.timezone", TimeZone.getDefault().id)
            put("os.name", "Linux")
            put("os.version", "Android-${Build.VERSION.RELEASE}")
            put("pojav.path.minecraft", getMinecraftPath())
            put("pojav.path.private.account", PathManager.DIR_DATA_BASES.absolutePath)
            put("org.lwjgl.vulkan.libname", "libvulkan.so")
            // LWJGL 3.4 的 Library.loadSystem 通过该属性定位 native 库。
            // 指向 per-version natives 目录，保证 3.4.x 游戏加载对应版本的 liblwjgl.so 等。
            put("org.lwjgl.librarypath", lwjglNativesDir)
            put("glfwstub.windowWidth", screenSize.width.toString())
            put("glfwstub.windowHeight", screenSize.height.toString())
            put("glfwstub.initEgl", "false")
            put("ext.net.resolvPath", resolvFile.absolutePath)

            put("log4j2.formatMsgNoLookups", "true")
            // Fix RCE vulnerability of log4j2
            put("java.rmi.server.useCodebaseOnly", "true")
            put("com.sun.jndi.rmi.object.trustURLCodebase", "false")
            put("com.sun.jndi.cosnaming.object.trustURLCodebase", "false")

            put("net.minecraft.clientmodname", BuildKeys.LAUNCHER_NAME)

            // fml
            put("fml.earlyprogresswindow", "false")
            put("fml.ignoreInvalidMinecraftCertificates", "true")
            put("fml.ignorePatchDiscrepancies", "true")

            put("loader.disable_forked_guis", "true")
            put("jdk.lang.Process.launchMechanism", "FORK")

            put("sodium.checks.issue2561", "false")

            put("file.encoding", "UTF-8")
            put("sun.stdout.encoding", "UTF-8")
            put("sun.stderr.encoding", "UTF-8")

            put("cpu.name", getSocName())

            putJavaArgs()
        }.map { entry ->
            "-D${entry.key}=${entry.value}"
        }

        val additionalArguments = overridableArguments.filter { arg ->
            val stripped = arg.substringBefore('=')
            val overridden = userArguments.any { it.startsWith(stripped) }
            if (overridden) {
                Logger.info(TAG, "Arg skipped: $arg")
            }
            !overridden
        }

        userArguments += additionalArguments
        return userArguments
    }

    /**
     * 确保 DNS 配置文件存在
     */
    private fun ensureDNSConfig(): File {
        val resolvFile = File(PathManager.DIR_GAME, "resolv.conf")
        val servers = buildResolvConfSet()
        Logger.info(TAG, "Using DNS servers for game: $servers")
        val configText = servers.joinToString(separator = "\n") { "nameserver $it" }
        runCatching {
            // 配置文件不存在或内容不一致时覆写一次
            if (!resolvFile.exists() || resolvFile.readText().trim() != configText.trim()) {
                resolvFile.writeText(configText)
            }
        }.onFailure {
            Logger.warning(TAG, "Failed to create resolv.conf", it)
            FileUtils.deleteQuietly(resolvFile)
        }
        return resolvFile
    }

    private fun buildResolvConfSet(): Set<String> {
        return buildSet {
            getSystemDnsServerAddresses()
                // JNDI DNS 的 nameserver 解析无法处理裸 IPv6 地址，仅保留 IPv4
                ?.filterNot { it.contains(':') }
                ?.let { addAll(it) }

            // 按地区获取公共 DNS
            if (LocaleList.getDefault().get(0).displayName != Locale.CHINA.displayName) {
                add("1.1.1.1")
                add("1.0.0.1")
            } else {
                add("223.5.5.5")
                add("119.29.29.29")
            }
        }
    }

    /**
     * @param args 需要进行处理的参数
     * @param ramAllocation 指定内存空间大小
     */
    protected open fun progressFinalUserArgs(
        args: MutableList<String>,
        ramAllocation: Int = GameSettings.ramAllocation.getOrMin()
    ) {
        args.purgeArg("-Xms")
        args.purgeArg("-Xmx")
        args.purgeArg("-d32")
        args.purgeArg("-d64")
        args.purgeArg("-Xint")
        args.purgeArg("-XX:+UseTransparentHugePages")
        args.purgeArg("-XX:+UseLargePagesInMetaspace")
        args.purgeArg("-XX:+UseLargePages")
        args.purgeArg("-Dorg.lwjgl.opengl.libname")
        // Don't let the user specify a custom Freetype library (as the user is unlikely to specify a version compiled for Android)
        args.purgeArg("-Dorg.lwjgl.freetype.libname")
        // Overridden by us to specify the exact number of cores that the android system has
        args.purgeArg("-XX:ActiveProcessorCount")

        //Add automatically generated args
        val ramAllocationString = ramAllocation.toString()
        args.add("-Xms${ramAllocationString}M")
        args.add("-Xmx${ramAllocationString}M")

        args.add("-Dorg.lwjgl.openal.libname=${PathManager.DIR_NATIVE_LIB}/libopenal.so")

        // Force LWJGL to use the Freetype library intended for it, instead of using the one
        // that we ship with Java (since it may be older than what's needed).
        // 始终指向 LWJGL 组件 natives 目录内的库，禁止回退到应用原生 libs 目录（其中不含 LWJGL 系库）
        args.add("-Dorg.lwjgl.freetype.libname=${File(lwjglNativesDir, "libfreetype.so").absolutePath}")

        // Our spirv-cross is compiled shared, so it gets named shared.
        args.add("-Dorg.lwjgl.spvc.libname=spirv-cross-c-shared")

        // We don't have jemalloc for our LWJGL so set the allocator to system to avoid error logs
        args.add("-Dorg.lwjgl.system.allocator=system")

        // 识别到大核簇时让 JVM 的 GC/JIT/ForkJoin 线程数与 affinity 保持一致；
        // 识别失败则保持原来的全核数量（CORE_PLAN_A F1-4）
        val activeProcessorCount = bigCoreCpuIds?.size
            ?: java.lang.Runtime.getRuntime().availableProcessors()
        args.add("-XX:ActiveProcessorCount=$activeProcessorCount")

        // Disable hsperfdata (the mmap'd perf counter file): nothing on Android reads it,
        // so this avoids its startup writes and resident pages. No gameplay effect.
        args.add("-XX:-UsePerfData")

        // CDS（Class Data Sharing）：仅游戏启动使用设备上生成的共享归档。
        // 安装器 JVM（JvmLauncher）不加，避免多进程场景下额外的映射/内存开销。
        // 用户显式指定了 Xshare / 归档参数时不覆盖。
        val userSpecifiedCds = args.any {
            it.startsWith("-Xshare") ||
                it.startsWith("-XX:SharedArchiveFile") ||
                it.startsWith("-XX:ArchiveClassesAtExit") ||
                it.startsWith("-XX:+ArchiveClassesAtExit")
        }
        if (this is GameLauncher && !userSpecifiedCds) {
            CdsArchive.findExisting(runtime, runtimeHome)?.let { archive ->
                // 用 -Xshare:auto 而不是 -Xshare:on：归档过期/损坏时 HotSpot 会静默回退，
                // 而 -Xshare:on 在部分版本会直接让游戏启动失败。
                args.add("-Xshare:auto")
                args.add("-XX:SharedArchiveFile=${archive.absolutePath}")
            }
        }
    }

    protected fun MutableList<String>.purgeArg(argStart: String) {
        removeIf { arg: String -> arg.startsWith(argStart) }
    }

    protected fun getJavaLibDir(): String {
        val architecture = runtime.arch?.let { arch ->
            if (Architecture.archAsInt(arch) == ARCH_X86) "i386/i486/i586"
            else arch
        } ?: throw IOException("Unsupported runtime environment: ${runtime.name}, arch is null!")

        var libDir = "/lib"
        architecture.split("/").forEach { arch ->
            val file = File(runtimeHome, "lib/$arch")
            if (file.exists() && file.isDirectory()) {
                libDir = "/lib/$arch"
            }
        }
        return libDir
    }

    private fun getJvmLibDir(): String {
        val jvmLibDir: String
        val path = (if (RuntimesManager.isJDK8(runtimeHome)) "/jre" else "") + getJavaLibDir()
        val jvmFile = File("$runtimeHome$path/server/libjvm.so")
        jvmLibDir = if (jvmFile.exists()) "/server" else "/client"
        return jvmLibDir
    }

    protected open fun getRuntimeLibraryPath(): String {
        val javaLibDir = getJavaLibDir()
        val jvmLibDir = getJvmLibDir()

        val libName = if (is64BitsDevice) "lib64" else "lib"
        val paths = buildList {
            FFmpegPluginManager.takeIf { it.isAvailable }?.libraryPath?.let { add(it) }
            RendererPluginManager.selectedRendererPlugin?.path?.let { add(it) }
            addAll(NativePluginManager.getPaths())
            add("$runtimeHome$javaLibDir/jli")
            if (runtime.isJDK8) {
                add("$runtimeHome/jre$javaLibDir$jvmLibDir:$runtimeHome/jre$javaLibDir")
            } else {
                add("$runtimeHome$javaLibDir$jvmLibDir")
            }
            add("/system/$libName")
            add("/vendor/$libName")
            add("/vendor/$libName/hw")
            add("/system_ext/$libName")
            add(LibPath.JNA.absolutePath)
            PathManager.DIR_RUNTIME_MOD?.absolutePath?.let { add(it) }
            add(lwjglNativesDir)
            add(PathManager.DIR_NATIVE_LIB)
        }
        return paths.joinToString(":")
    }

    protected fun getLibraryPath(): String {
        val libDirName = if (is64BitsDevice) "lib64" else "lib"
        val path = listOfNotNull(
            // per-version LWJGL natives 优先，避免 APK 内旧版 native 抢占
            lwjglNativesDir,
            FFmpegPluginManager.takeIf { it.isAvailable }?.libraryPath,
            "/system/$libDirName",
            "/vendor/$libDirName",
            "/vendor/$libDirName/hw",
            "/system_ext/$libDirName",
            RendererPluginManager.selectedRendererPlugin?.path,
            PathManager.DIR_RUNTIME_MOD?.absolutePath,
            PathManager.DIR_NATIVE_LIB
        )
        return path.joinToString(":")
    }

    protected fun findInLdLibPath(libName: String): String {
        val path = getLibraryPath()
        return path.split(":").find { libPath ->
            val file = File(libPath, libName)
            file.exists() && file.isFile
        }?.let {
            File(it, libName).absolutePath
        } ?: libName
    }

    private fun locateLibs(path: File): List<File> {
        val children = path.listFiles() ?: return emptyList()
        return children.flatMap { file ->
            when {
                file.isFile && file.name.endsWith(".so") -> listOf(file)
                file.isDirectory -> locateLibs(file)
                else -> emptyList()
            }
        }
    }

    private fun setEnv(screenSize: IntSize) {
        val envMap = runCatching {
            initEnv(screenSize)
        }.onFailure {
            LoggerBridge.appendTitle("Init Env Failed")
            LoggerBridge.append(it.stackTraceToString())
        }.getOrThrow()
        envMap.forEach { (key, value) ->
            LoggerBridge.appendInfo("$key = $value")
            runCatching {
                Os.setenv(key, value, true)
            }.onFailure {
                Logger.error(TAG, "Unable to set environment variable.", it)
            }
        }
    }

    @CallSuper
    protected open fun initEnv(screenSize: IntSize): MutableMap<String, String> {
        val envMap: MutableMap<String, String> = ArrayMap()
        setJavaEnv(
            screenSize = screenSize,
            envMap = { envMap }
        )
        return envMap
    }

    private fun setJavaEnv(
        screenSize: IntSize,
        envMap: () -> MutableMap<String, String>
    ) {
        val path = listOfNotNull(
            FFmpegPluginManager.binPath,
            "$runtimeHome/bin",
            Os.getenv("PATH")
        )

        envMap().let { map ->
            map["POJAV_NATIVEDIR"] = PathManager.DIR_NATIVE_LIB
            map["JAVA_HOME"] = getJavaHome()
            map["HOME"] = PathManager.DIR_FILES_EXTERNAL.absolutePath
            map["TMPDIR"] = PathManager.DIR_CACHE.absolutePath
            map["LD_LIBRARY_PATH"] = getLibraryPath()
            map["PATH"] = path.joinToString(":")
            map["AWTSTUB_WIDTH"] = screenSize.width.toString()
            map["AWTSTUB_HEIGHT"] = screenSize.height.toString()
            map["MOD_ANDROID_RUNTIME"] = PathManager.DIR_RUNTIME_MOD?.absolutePath ?: ""
            map["DALVIK_JAVAVM"] = ZLBridge.getJavaVMPointer().toString()
            map["DALVIK_APPLICATION"] = ZLBridge.jObjectToString(GlobalContext.applicationContext)
            map["ALSOFT_DRIVERS"] = "opensl"

            if (RendererSettings.dumpShaders.getValue()) map["LIBGL_VGPU_DUMP"] = "1"
            if (RendererSettings.zinkPreferSystemDriver.getValue()) map["POJAV_ZINK_PREFER_SYSTEM_DRIVER"] = "1"
            if (RendererSettings.vsyncInZink.getValue()) map["POJAV_VSYNC_IN_ZINK"] = "1"

            // CORE_PLAN_A F1-4: native 侧据此把 JVM/游戏线程固定到大核；
            // 识别失败时不设置，native 保持默认 affinity。
            bigCoreCpuIds?.let { ids ->
                map["ZALITH_CPU_IDS"] = ids.joinToString(",")
                map["ZALITH_ACTIVE_CPUS"] = ids.size.toString()
            }

            if (FFmpegPluginManager.isAvailable) map["POJAV_FFMPEG_PATH"] = FFmpegPluginManager.executablePath!!
        }
    }

    private fun dlopenJavaRuntime() {
        var javaLibDir = "$runtimeHome${getJavaLibDir()}"
        val jliLibDir = if (File("$javaLibDir/jli/libjli.so").exists()) "$javaLibDir/jli" else javaLibDir

        if (runtime.isJDK8) {
            javaLibDir = "$runtimeHome/jre${getJavaLibDir()}"
        }
        val jvmLibDir = "$javaLibDir${getJvmLibDir()}"
        val explicitLibs = listOf(
            "$jliLibDir/libjli.so",
            "$jvmLibDir/libjvm.so",
            "$javaLibDir/libfreetype.so",
            "$javaLibDir/libverify.so",
            "$javaLibDir/libjava.so",
            "$javaLibDir/libnet.so",
            "$javaLibDir/libnio.so",
            "$javaLibDir/libawt.so",
            "$javaLibDir/libawt_headless.so",
            "$javaLibDir/libfontmanager.so"
        )
        explicitLibs.forEach { ZLBridge.dlopen(it) }

        // FF-F2-2：只遍历 Java home 的 lib/ 子树（JDK8 为 jre/lib/<arch>），不再递归整个 runtimeHome。
        // 经核对打包的 jre-8/17/21/25，运行时里的 .so 全部位于 lib/ 下；bin/、legal/、conf/、
        // lib/security 等目录不含 .so，因此全树递归实际只命中 lib/，额外收益来自跳过上面显式加载的重复项。
        // 显式列表保持原样；这里按 canonical path 去重，避免同一库被 dlopen 多次（含符号链接）。
        val libDir = File(getJavaHome(), "lib")
        val loadedPaths = explicitLibs.mapTo(mutableSetOf<String>()) { it.canonicalLibPath() }
        var extraLibs = 0
        var skippedDuplicates = 0
        locateLibs(libDir).forEach { file ->
            if (!loadedPaths.add(file.absolutePath.canonicalLibPath())) {
                skippedDuplicates++
                return@forEach
            }
            ZLBridge.dlopen(file.absolutePath)
            extraLibs++
        }
        LoggerBridge.appendInfo(
            "JRE preload: walked ${libDir.absolutePath}: " +
                "$extraLibs extra libs loaded, $skippedDuplicates explicit duplicates skipped"
        )
    }

    private fun String.canonicalLibPath(): String =
        runCatching { File(this).canonicalPath }.getOrDefault(this)

    @CallSuper
    protected open fun dlopenEngine() {
        ZLBridge.dlopen("${PathManager.DIR_NATIVE_LIB}/libopenal.so")
    }
}

fun getCacioJavaArgs(
    screenSize: IntSize,
    isJava8: Boolean
): List<String> {
    val argsList: MutableList<String> = ArrayList()

    // Caciocavallo config AWT-enabled version
    argsList.add("-Djava.awt.headless=false")
    argsList.add("-Dcacio.managed.screensize=${screenSize.width}x${screenSize.height}")
    argsList.add("-Dcacio.font.fontmanager=sun.awt.X11FontManager")
    argsList.add("-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler")
    argsList.add("-Dswing.defaultlaf=javax.swing.plaf.nimbus.NimbusLookAndFeel")
    if (isJava8) {
        argsList.add("-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit")
        argsList.add("-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment")
    } else {
        argsList.add("-Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit")
        argsList.add("-Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment")
        argsList.add("-javaagent:${LibPath.CACIO_17_AGENT.absolutePath}")

        argsList.add("--add-exports=java.desktop/java.awt=ALL-UNNAMED")
        argsList.add("--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED")
        argsList.add("--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED")
        argsList.add("--add-exports=java.desktop/sun.java2d=ALL-UNNAMED")
        argsList.add("--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED")
        argsList.add("--add-exports=java.desktop/sun.awt=ALL-UNNAMED")
        argsList.add("--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED")
        argsList.add("--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED")
        argsList.add("--add-exports=java.desktop/sun.font=ALL-UNNAMED")
        argsList.add("--add-exports=java.base/sun.security.action=ALL-UNNAMED")
        argsList.add("--add-opens=java.base/java.util=ALL-UNNAMED")
        argsList.add("--add-opens=java.desktop/java.awt=ALL-UNNAMED")
        argsList.add("--add-opens=java.desktop/sun.font=ALL-UNNAMED")
        argsList.add("--add-opens=java.desktop/sun.java2d=ALL-UNNAMED")
        argsList.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED")

        // Opens the java.net package to Arc DNS injector on Java 9+
        argsList.add("--add-opens=java.base/java.net=ALL-UNNAMED")
    }

    val cacioClassPath = StringBuilder()
    cacioClassPath.append("-Xbootclasspath/").append(if (isJava8) "p" else "a")
    val cacioFiles = if (isJava8) LibPath.CACIO_8 else LibPath.CACIO_17
    cacioFiles.listFiles()?.onEach {
        if (it.name.endsWith(".jar")) cacioClassPath.append(":").append(it.absolutePath)
    }

    argsList.add(cacioClassPath.toString())

    return argsList
}

/**
 * 获取设备 SoC 名称，在 API 31+ 读取系统属性 ro.soc.model，若失败则返回 Build.HARDWARE
 *
 * [Build.SOC_MODEL] 与 `getprop ro.soc.model` 读取的是同一个系统属性，
 * 因此 API 31+ 直接读取字段即可，无需再启动 getprop 进程。
 */
fun getSocName(): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL.takeIf { it.isNotBlank() }?.let { return it }
    }
    return runCatching {
        ProcessBuilder("getprop", "ro.soc.model")
            .start()
            .inputStream
            .bufferedReader()
            .use { reader ->
                reader.readLine()
            }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.HARDWARE
}
