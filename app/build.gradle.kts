import java.net.URL
import java.net.URI
import java.net.NetworkInterface
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

// Function to detect local LAN IP address for gRPC server connection
fun getLocalIp(): String {
    var ip = "10.0.2.2" // Default to emulator fallback
    var wifiIp: String? = null // Prefer Wi-Fi interface
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (iface.isLoopback || !iface.isUp) continue
            
            val ifaceName = iface.name.lowercase()
            val isWifi = ifaceName.contains("wifi") || ifaceName.contains("wlan") || ifaceName.contains("wireless")
            val isVmware = ifaceName.contains("vmware") || ifaceName.contains("vbox") || ifaceName.contains("virtual")
            
            // Skip VMware/virtual adapters unless no other option
            if (isVmware) continue
            
            val addresses = iface.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                // Filter for IPv4 and typical LAN prefixes (192.168.x.x or 10.x.x.x)
                if (!addr.isLoopbackAddress && addr.hostAddress.indexOf(':') < 0) {
                    val sAddr = addr.hostAddress
                    if (sAddr.startsWith("192.") || sAddr.startsWith("10.")) {
                        // Prefer Wi-Fi interfaces
                        if (isWifi) {
                            wifiIp = sAddr
                        } else if (ip == "10.0.2.2") {
                            // Use first non-VMware interface as fallback
                            ip = sAddr
                        }
                    }
                }
            }
        }
        // Return Wi-Fi IP if found, otherwise fallback to first valid IP
        return wifiIp ?: ip
    } catch (e: Exception) {
        println("Could not detect IP: $e")
    }
    return ip
}

val detectedHostIp = getLocalIp()
println("🚀 Injecting Server IP: $detectedHostIp")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.gms.google.services)
    id("com.google.protobuf") version "0.9.4"
}

android {
    namespace = "com.example.expressora"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.expressora"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Inject detected host IP for gRPC server connection (auto-detected at build time)
        buildConfigField("String", "HOST_IP", "\"$detectedHostIp\"")
        
        // Note: Baseline profiles can be enabled when using Android Gradle Plugin 7.4+
        // and the androidx.profileinstaller:profileinstaller dependency.
        // To enable: add the dependency and use the baseline profile plugin.
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }
    
    // Enable ABI splits for smaller APKs
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
    
    // Workaround for IncrementalSplitterRunnable issues with large assets
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
        compose = true
        viewBinding = true
        mlModelBinding = false
    }

    androidResources {
        // Keep model assets uncompressed so interpreters can memory-map them efficiently
        // TFLite and ONNX models should not be compressed for better performance
        noCompress += listOf("tflite", "lite", "onnx")
    }
}

// Protobuf configuration
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.21.12"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.60.0"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
            task.plugins {
                create("grpc") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    // Proto DataStore
    implementation("androidx.datastore:datastore:1.0.0")
    implementation("com.google.protobuf:protobuf-javalite:3.21.12")
    
    // gRPC for bidirectional streaming (Landmark Streaming Architecture)
    implementation("io.grpc:grpc-okhttp:1.60.0")
    implementation("io.grpc:grpc-protobuf-lite:1.60.0")
    implementation("io.grpc:grpc-stub:1.60.0")
    implementation("javax.annotation:javax.annotation-api:1.3.2") // Required for generated code
    
    // Firebase BOM
    implementation(platform("com.google.firebase:firebase-bom:33.4.0"))

    // Firebase Auth + Firestore
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")

    // Coroutines for Firebase
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Jetpack Compose Material3 (if not added)
    implementation("androidx.compose.material3:material3:1.3.0")

    // ViewModel Compose integration
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")

    implementation("com.google.android.gms:play-services-auth:20.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")

    // Retrofit for backend OTP requests
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    implementation("org.mindrot:jbcrypt:0.4")

    // OkHttp para sa HTTP requests
    implementation("com.squareup.okhttp3:okhttp:4.11.0")

    // Kotlin coroutines para async calls
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Tensorflow
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    // Select TF Ops for LSTM models with dynamic operations (required for Holistic LSTM)
    implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.14.0")
    // MediaPipe Tasks Vision - explicit version for Holistic support
    implementation("com.google.mediapipe:tasks-vision:0.10.11")
    
    // ONNX Runtime for offline translation models
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.0")
    
    // Gson for JSON parsing (tokenizer.json)
    implementation("com.google.code.gson:gson:2.10.1")
    
    // OpenCV Android SDK for computer vision tasks
    // Note: OpenCV for Android requires manual SDK integration or specific repository setup
    // The standard Maven dependency doesn't exist. To enable OpenCV:
    // 1. Download OpenCV Android SDK from https://opencv.org/releases/
    // 2. Add as a module or configure a custom repository
    // For now, commented out to allow builds to succeed
    // implementation("org.opencv:opencv-android:4.8.0")

    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("androidx.compose.material:material:1.7.2")
    implementation("androidx.compose.material:material:<compose-version>")
    implementation("com.google.accompanist:accompanist-pager:0.30.1")
    implementation("com.google.accompanist:accompanist-pager-indicators:0.30.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-exoplayer:1.1.1")
    implementation("androidx.media3:media3-ui:1.1.1")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.accompanist:accompanist-swiperefresh:0.30.1")
    implementation("com.google.android.exoplayer:exoplayer:2.18.1")
    implementation("androidx.compose.material3:material3:1.1.0")
    implementation("io.coil-kt:coil-compose:2.4.0")
    implementation("io.coil-kt:coil-gif:2.4.0")
    implementation("androidx.compose.animation:animation:1.6.0")
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.firebase.auth)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.foundation)
    implementation(libs.foundation)
    implementation(libs.androidx.foundation.layout)
    implementation(libs.androidx.compose.testing)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.runtime.saveable)
    implementation(libs.material3)
    implementation(libs.androidx.compose.animation)
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// === Recognition artifact auto-copy (submodule -> assets) ===
// NOTE: Model file is now manually managed - auto-copy disabled to prevent overwriting v2 model
// If you want to re-enable auto-copy, update unifiedModelOut above and uncomment the copy task
val recogTflitePath: String? by project    // optional override via -PrecogTflitePath=...
val recogLabelsPath: String? by project    // optional override via -PrecogLabelsPath=...
val recogLabelsNpyPath: String? by project // optional override via -PrecogLabelsNpyPath=...

val assetsDir = File(project.projectDir, "src/main/assets")
// Updated to v2 model - manually managed, not auto-copied from external/recognition
val unifiedModelOut = File(assetsDir, "expressora_unified_v2.tflite")
val unifiedLabelsOut = File(assetsDir, "expressora_labels.json")

private val recognitionSearchDepth = 20
private val labelsJsonRegex = Regex("(?i).*labels.*\\.json$")
private val labelsNpyRegex = Regex("(?i).*(label|classes).*\\.npy$")

val rootDir: File = rootProject.projectDir

fun recognitionDir(root: File): File = File(root, "external/recognition")

fun findLatestRecognitionFile(root: File, predicate: (File) -> Boolean): File? {
    val sub = recognitionDir(root)
    if (!sub.exists()) return null

    return sub.walkTopDown()
        .maxDepth(recognitionSearchDepth)
        .filter { it.isFile && predicate(it) }
        .maxByOrNull { it.lastModified() }
}

fun findLatestTfliteFile(root: File): File? =
    findLatestRecognitionFile(root) { it.extension.equals("tflite", ignoreCase = true) }

fun findLatestLabelsJsonFile(root: File): File? =
    findLatestRecognitionFile(root) { it.name.matches(labelsJsonRegex) }

fun findLatestLabelsNpyFile(root: File): File? =
    findLatestRecognitionFile(root) { it.name.matches(labelsNpyRegex) }

fun deriveLabelsForModel(tfliteFile: File): Pair<File?, File?> {
    val bucket = when {
        tfliteFile.path.contains("basicphrases", ignoreCase = true) -> "basicphrases"
        tfliteFile.path.contains("phrases", ignoreCase = true) -> "phrases"
        tfliteFile.path.contains("alphabet", ignoreCase = true) -> "alphabet"
        else -> null
    }

    fun Sequence<File>.firstMatching(regex: Regex): File? {
        if (bucket != null) {
            val keywordHit = firstOrNull { it.name.contains(bucket, ignoreCase = true) && it.name.matches(regex) }
            if (keywordHit != null) return keywordHit
        }
        return firstOrNull { it.name.matches(regex) }
    }

    fun inspectDirectory(dir: File?, deep: Boolean = false): Pair<File?, File?> {
        if (dir == null || !dir.exists()) return null to null
        val filesSequence = if (deep) {
            dir.walkTopDown().maxDepth(recognitionSearchDepth).filter { it.isFile }
        } else {
            dir.listFiles()?.asSequence()?.filter { it.isFile } ?: emptySequence()
        }
        val cached = filesSequence.toList()
        if (cached.isEmpty()) return null to null
        val jsonCandidate = cached.asSequence().firstMatching(labelsJsonRegex)
        val npyCandidate = cached.asSequence().firstMatching(labelsNpyRegex)
        return jsonCandidate to npyCandidate
    }

    var labelsJson: File? = null
    var labelsNpy: File? = null

    fun capture(result: Pair<File?, File?>) {
        if (labelsJson == null) labelsJson = result.first
        if (labelsNpy == null) labelsNpy = result.second
    }

    capture(inspectDirectory(tfliteFile.parentFile, deep = false))
    if (labelsJson == null || labelsNpy == null) {
        capture(inspectDirectory(tfliteFile.parentFile?.parentFile, deep = false))
    }

    if (labelsJson == null || labelsNpy == null) {
        val parent = tfliteFile.parentFile?.parentFile
        val siblings = parent?.listFiles()?.filter { it.isDirectory } ?: emptyList()
        siblings.filter { bucket != null && it.path.contains(bucket, ignoreCase = true) }
            .forEach {
                if (labelsJson != null && labelsNpy != null) return@forEach
                capture(inspectDirectory(it, deep = true))
            }
    }

    if (labelsJson == null) labelsJson = findLatestLabelsJsonFile(rootDir)
    if (labelsNpy == null) labelsNpy = findLatestLabelsNpyFile(rootDir)

    return labelsJson to labelsNpy
}

val selectedTfliteFileProvider = project.provider {
    recogTflitePath?.let { File(it) }?.takeIf { it.exists() } ?: findLatestTfliteFile(rootDir)
}

val labelsOverrideProvider = project.provider {
    recogLabelsPath?.let { File(it) }?.takeIf { it.exists() }
}

val labelsNpyOverrideProvider = project.provider {
    recogLabelsNpyPath?.let { File(it) }?.takeIf { it.exists() }
}

// Removed writeAlphabetLabels task - unified model uses its own labels JSON

abstract class DownloadTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:OutputFile
    abstract val outFile: RegularFileProperty

    @TaskAction
    fun run() {
        val destination = outFile.asFile.get()
        destination.parentFile.mkdirs()
        if (destination.exists()) {
            logger.lifecycle("${destination.name} already present at ${destination.relativeToOrSelf(project.projectDir)}")
            return
        }

        runCatching {
            val urlString = url.get()
            logger.lifecycle("Downloading ${destination.name} from $urlString...")
            val connection = URI.create(urlString).toURL()
            connection.openStream().use { inputStream ->
                destination.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            val fileSizeMB = destination.length() / (1024.0 * 1024.0)
            logger.lifecycle("Downloaded ${destination.name} (${String.format("%.2f", fileSizeMB)} MB) -> ${destination.relativeToOrSelf(project.projectDir)}")
        }.onFailure {
            logger.error("❌ Could not download ${destination.name} from ${url.get()}")
            logger.error("   Error: ${it.message}")
            logger.error("   Please place ${destination.name} manually in ${destination.relativeToOrSelf(project.projectDir)} if needed.")
            logger.warn("   Build will continue, but the app may not work without this file.")
            // Don't throw - allow build to continue, but user will see error in app
        }
    }
}

tasks.register<DownloadTask>("downloadHandLandmarker") {
    group = "build setup"
    description = "Ensures hand_landmarker.task is present in assets/recognition/."
    val defaultUrl = (project.findProperty("handTaskUrl") as String?)
        ?: "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task"
    url.set(defaultUrl)
    outFile.set(layout.projectDirectory.file("src/main/assets/recognition/hand_landmarker.task"))
}

// BuildConfig fields removed - unified model contract is hardcoded in RecognitionProvider

private fun isCommandAvailable(name: String): Boolean {
    val path = System.getenv("PATH")?.split(File.pathSeparator)?.filter { it.isNotBlank() } ?: return false
    val isWindows = System.getProperty("os.name")?.contains("windows", ignoreCase = true) == true
    val extensions = if (isWindows) {
        System.getenv("PATHEXT")?.split(";")?.map { it.trim() }?.filter { it.isNotBlank() } ?: listOf(".exe", ".bat", ".cmd")
    } else {
        listOf("")
    }

    return path.any { entry ->
        val dir = File(entry)
        if (!dir.isDirectory) return@any false
        if (isWindows) {
            extensions.any { ext -> File(dir, name + ext).exists() }
        } else {
            val candidate = File(dir, name)
            candidate.exists() && candidate.canExecute()
        }
    }
}

private fun resolvePythonExecutable(): String {
    val candidates = listOf("py", "python", "python3")
    return candidates.firstOrNull { isCommandAvailable(it) } ?: "python"
}

tasks.register("diagnoseRecognitionAssets") {
    group = "verification"
    description = "Prints the resolved unified recognition artifacts under external/recognition."

    doLast {
        fun logCandidate(label: String, file: File?) {
            if (file != null) {
                val timestamp = Instant.ofEpochMilli(file.lastModified())
                logger.lifecycle("$label -> ${file.absolutePath} (lastModified=$timestamp)")
            } else {
                logger.lifecycle("$label -> <not found>")
            }
        }

        logger.lifecycle("=== Unified Recognition Assets Diagnostics ===")
        logger.lifecycle("Target: ${unifiedModelOut.relativeToOrSelf(project.projectDir)} + ${unifiedLabelsOut.relativeToOrSelf(project.projectDir)}")
        val tflite = selectedTfliteFileProvider.orNull
        logCandidate("Source model (.tflite)", tflite)
        val derived = tflite?.let { deriveLabelsForModel(it) } ?: (null to null)
        logCandidate("Source labels (.json)", derived.first)
        logCandidate("Source labels (.npy for conversion)", derived.second)
        logger.lifecycle("Assets exist: model=${unifiedModelOut.exists()}, labels=${unifiedLabelsOut.exists()}")
    }
}

// Removed generateLabelsJson task - expressora_labels.json is now manually managed

// Task to explicitly delete old model file (expressora_unified.tflite) if it exists
// This runs BEFORE build to prevent the old file from interfering
tasks.register("cleanupOldModel") {
    doLast {
        val oldModelFile = File(assetsDir, "expressora_unified.tflite")
        val oldModelFp16 = File(assetsDir, "expressora_unified_fp16.tflite")
        val oldModelInt8 = File(assetsDir, "expressora_unified_int8.tflite")
        
        var deleted = false
        if (oldModelFile.exists()) {
            oldModelFile.delete()
            deleted = true
            logger.lifecycle("🗑️ DELETED old model file: expressora_unified.tflite")
        }
        if (oldModelFp16.exists()) {
            oldModelFp16.delete()
            deleted = true
            logger.lifecycle("🗑️ DELETED old model file: expressora_unified_fp16.tflite")
        }
        if (oldModelInt8.exists()) {
            oldModelInt8.delete()
            deleted = true
            logger.lifecycle("🗑️ DELETED old model file: expressora_unified_int8.tflite")
        }
        
        if (!deleted) {
            logger.debug("✅ No old model files found, nothing to clean")
        } else {
            logger.warn("⚠️ Old model files were deleted - make sure they're not restored from git!")
        }
    }
}

tasks.register("copyUnifiedRecognitionArtifacts") {
    // DISABLED: Model file auto-copy is disabled - models are manually managed
    // This prevents the build from overwriting expressora_unified_v2.tflite with old files
    dependsOn("cleanupOldModel") // Ensure old file is deleted first
    
    doLast {
        // CRITICAL: Delete old model file if it somehow reappeared
        val oldModelFile = File(assetsDir, "expressora_unified.tflite")
        if (oldModelFile.exists()) {
            oldModelFile.delete()
            logger.warn("⚠️ Found and deleted old model file: ${oldModelFile.relativeToOrSelf(project.projectDir)}")
        }
        
        // NO-OP: Model files are manually managed, not auto-copied
        logger.info("Model file auto-copy disabled - using manually managed files in assets/")
        
        // Just verify the v2 model file exists (won't create or overwrite)
        if (unifiedModelOut.exists()) {
            logger.lifecycle("✅ Model file verified: ${unifiedModelOut.relativeToOrSelf(project.projectDir)}")
        } else {
            logger.warn("⚠️ Model file not found: ${unifiedModelOut.relativeToOrSelf(project.projectDir)} - please add it manually to assets")
        }

        // Labels JSON (expressora_labels.json) is now manually managed - no auto-copy
        logger.info("Labels JSON auto-copy disabled - using manually managed files in assets/")
    }
}

// Removed dependency on generateLabelsJson task

// Ensure unified asset preparation tasks run before building the app
// CRITICAL: Run cleanupOldModel FIRST before any other tasks
tasks.named("preBuild").configure {
    // MUST run cleanupOldModel FIRST to prevent old model from appearing
    dependsOn("cleanupOldModel")
    dependsOn("downloadHandLandmarker")
    dependsOn("copyUnifiedRecognitionArtifacts")
}

// Ensure asset tasks complete before packaging to avoid file lock issues
tasks.matching { it.name.startsWith("package") || it.name.startsWith("bundle") }.configureEach {
    mustRunAfter("downloadHandLandmarker", "copyUnifiedRecognitionArtifacts")
}

// === Automatic Logcat Capture ===
private fun findAdbExecutable(): String? {
    // First check if adb is in PATH
    if (isCommandAvailable("adb")) {
        return "adb"
    }
    
    // Try to find adb in Android SDK (common locations)
    val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (androidHome != null) {
        val adbPath = File(androidHome, "platform-tools/adb${if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) ".exe" else ""}")
        if (adbPath.exists() && adbPath.canExecute()) {
            return adbPath.absolutePath
        }
    }
    
    // Try common SDK locations
    val homeDir = System.getProperty("user.home")
    val commonSdkPaths = listOf(
        File(homeDir, "AppData/Local/Android/Sdk/platform-tools/adb.exe"), // Windows
        File(homeDir, "Library/Android/sdk/platform-tools/adb"), // macOS
        File(homeDir, "Android/Sdk/platform-tools/adb"), // Linux/Unix
    )
    
    for (adbPath in commonSdkPaths) {
        if (adbPath.exists() && adbPath.canExecute()) {
            return adbPath.absolutePath
        }
    }
    
    return null
}

abstract class LogcatCaptureTask : DefaultTask() {
    @TaskAction
    fun run() {
        val adbPath = findAdbExecutable()
        if (adbPath == null) {
            logger.warn("adb not found in PATH or Android SDK. Logcat capture skipped.")
            logger.warn("Please ensure adb is available or set ANDROID_HOME/ANDROID_SDK_ROOT environment variable.")
            return
        }
        
        // Create logs directory in project root
        val logsDir = File(rootProject.projectDir, "logs")
        logsDir.mkdirs()
        
        // Generate timestamped filename
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val logFile = File(logsDir, "logcat_$timestamp.txt")
        
        // Check if device is connected
        try {
            val checkDeviceProcess = ProcessBuilder(adbPath, "devices")
                .redirectErrorStream(true)
                .start()
            
            val deviceOutput = checkDeviceProcess.inputStream.bufferedReader().readText()
            checkDeviceProcess.waitFor()
            
            if (!deviceOutput.contains("device") || deviceOutput.trim().lines().count { it.endsWith("device") } == 0) {
                logger.warn("No Android device connected. Logcat capture skipped.")
                logger.info("Connect a device and run the app again to capture logs.")
                return
            }
        } catch (e: Exception) {
            logger.warn("Failed to check for connected devices: ${e.message}")
            logger.warn("Logcat capture skipped.")
            return
        }
        
        // Start logcat capture in background
        try {
            val isWindows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
            
            if (isWindows) {
                // On Windows, use PowerShell Start-Process to run in background
                val psCommand = """
                    Start-Process -FilePath "$adbPath" -ArgumentList @('logcat','-v','time','*:V') -RedirectStandardOutput "$logFile" -RedirectStandardError "$logFile" -WindowStyle Hidden -NoNewWindow
                """.trimIndent()
                
                val process = ProcessBuilder("powershell.exe", "-Command", psCommand)
                    .start()
                
                // Don't wait for the process - it runs in background
                process.waitFor(100, TimeUnit.MILLISECONDS)
            } else {
                // On Unix/Linux/macOS, use nohup to run in background
                val processBuilder = ProcessBuilder("nohup", adbPath, "logcat", "-v", "time", "*:V")
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                    .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
                
                val process = processBuilder.start()
                
                // Don't wait for the process - it runs in background
                process.waitFor(100, TimeUnit.MILLISECONDS)
            }
            
            logger.lifecycle("Logcat capture started: ${logFile.relativeToOrSelf(rootProject.projectDir)}")
            logger.info("Logs are being written to: ${logFile.absolutePath}")
            logger.info("Note: Logcat capture runs in the background. Stop it manually if needed.")
        } catch (e: Exception) {
            logger.error("Failed to start logcat capture: ${e.message}", e)
        }
    }
}

tasks.register<LogcatCaptureTask>("startLogcatCapture") {
    group = "android"
    description = "Starts capturing logcat logs to a timestamped file in logs/ directory"
}

// Hook logcat capture to run after install tasks
// Use afterEvaluate to ensure Android plugin tasks are registered first
afterEvaluate {
    // Try to hook into installDebug task (may not exist in all AGP versions)
    tasks.findByName("installDebug")?.let {
        it.finalizedBy("startLogcatCapture")
    }
    
    // Try to hook into installRelease task
    tasks.findByName("installRelease")?.let {
        it.finalizedBy("startLogcatCapture")
    }
    
    // Also hook into variant-specific install tasks (for newer AGP versions)
    tasks.matching { it.name.startsWith("install") && it.name.contains("Debug", ignoreCase = true) }.configureEach {
        finalizedBy("startLogcatCapture")
    }
    
    tasks.matching { it.name.startsWith("install") && it.name.contains("Release", ignoreCase = true) }.configureEach {
        finalizedBy("startLogcatCapture")
    }
}

