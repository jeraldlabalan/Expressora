import java.net.URL
import java.net.URI
import java.time.Instant
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

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
        // Keep TFLite assets uncompressed so Interpreter can memory-map them; our loader falls back to direct buffers if compression slips through.
        noCompress += listOf("tflite", "lite")
    }
}

// Protobuf configuration
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.21.12"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
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
    implementation("com.google.mediapipe:tasks-vision:latest.release")
    
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
// Single unified model contract: expressora_unified.tflite (126-dim, 2-hand) + expressora_labels.json
val recogTflitePath: String? by project    // optional override via -PrecogTflitePath=...
val recogLabelsPath: String? by project    // optional override via -PrecogLabelsPath=...
val recogLabelsNpyPath: String? by project // optional override via -PrecogLabelsNpyPath=...

val assetsDir = File(project.projectDir, "src/main/assets")
val unifiedModelOut = File(assetsDir, "expressora_unified.tflite")
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
            logger.lifecycle("hand_landmarker.task already present at ${destination.relativeToOrSelf(project.projectDir)}")
            return
        }

        runCatching {
            val urlString = url.get()
            val connection = URI.create(urlString).toURL()
            connection.openStream().use { inputStream ->
                destination.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            logger.lifecycle("Downloaded ${destination.name} -> ${destination.relativeToOrSelf(project.projectDir)}")
        }.onFailure {
            logger.warn("Could not download hand_landmarker.task (${it.message}). Place it in ${destination.relativeToOrSelf(project.projectDir)} if needed.")
        }
    }
}

tasks.register<DownloadTask>("downloadHandLandmarker") {
    group = "build setup"
    description = "Ensures hand_landmarker.task is present in assets."
    val defaultUrl = (project.findProperty("handTaskUrl") as String?)
        ?: "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task"
    url.set(defaultUrl)
    outFile.set(layout.projectDirectory.file("src/main/assets/hand_landmarker.task"))
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

tasks.register<Exec>("generateLabelsJson") {
    val pyScript = File(rootDir, "tools/convert_npy_to_json.py")
    val placeholderNpy = layout.buildDirectory.file("intermediates/recognition/placeholder.labels.npy").get().asFile
    val derivedLabelsProvider = project.provider {
        selectedTfliteFileProvider.orNull?.let { deriveLabelsForModel(it) }
            ?: (findLatestLabelsJsonFile(rootDir) to findLatestLabelsNpyFile(rootDir))
    }
    val npyFileProvider = project.provider {
        labelsNpyOverrideProvider.orNull ?: derivedLabelsProvider.orNull?.second
    }

    inputs.property("recogLabelsPathOverride", recogLabelsPath ?: "")
    inputs.property("recogLabelsNpyPathOverride", recogLabelsNpyPath ?: "")
    inputs.property("recogTflitePathOverride", recogTflitePath ?: "")
    inputs.file(npyFileProvider.map { it ?: placeholderNpy })
    outputs.file(unifiedLabelsOut)

    onlyIf {
        if (recogLabelsPath != null) return@onlyIf false
        if (unifiedLabelsOut.exists()) return@onlyIf false
        val candidate = npyFileProvider.orNull
        if (candidate == null) {
            val modelName = selectedTfliteFileProvider.orNull?.name ?: "<unknown>"
            logger.warn("No labels .npy found for unified model '$modelName'. Runtime will synthesize CLASS_i labels. Run diagnoseRecognitionAssets for details.")
            return@onlyIf false
        }
        true
    }

    doFirst {
        val npy = npyFileProvider.orNull ?: error("generateLabelsJson expected an NPY file, but none was found.")
        assetsDir.mkdirs()
        val pythonExec = resolvePythonExecutable()
        logger.lifecycle("Converting NPY to unified labels JSON: ${npy.relativeToOrSelf(rootDir)} -> ${unifiedLabelsOut.relativeToOrSelf(project.projectDir)} (python=$pythonExec)")
        environment("PYTHONUTF8", "1")
        environment("PYTHONIOENCODING", "utf-8")
        commandLine(pythonExec, pyScript.absolutePath, "--inp", npy.absolutePath, "--out", unifiedLabelsOut.absolutePath)
    }

    doLast {
        if (!unifiedLabelsOut.exists()) {
            logger.warn("Unified labels JSON not created. Ensure Python+numpy are installed or provide -PrecogLabelsPath=...")
        }
    }
}

tasks.register("copyUnifiedRecognitionArtifacts") {
    val derivedLabelsProvider = project.provider {
        selectedTfliteFileProvider.orNull?.let { deriveLabelsForModel(it) }
            ?: (findLatestLabelsJsonFile(rootDir) to findLatestLabelsNpyFile(rootDir))
    }

    inputs.property("recogTflitePathOverride", recogTflitePath ?: "")
    inputs.property("recogLabelsPathOverride", recogLabelsPath ?: "")
    outputs.file(unifiedModelOut)
    outputs.file(unifiedLabelsOut)

    doLast {
        assetsDir.mkdirs()
        val tflite = selectedTfliteFileProvider.orNull
        val (derivedJson, derivedNpy) = derivedLabelsProvider.orNull ?: (null to null)

        // Copy unified model
        if (tflite != null) {
            tflite.copyTo(unifiedModelOut, overwrite = true)
            logger.lifecycle("Copied unified model: ${tflite.relativeToOrSelf(rootDir)} -> ${unifiedModelOut.relativeToOrSelf(project.projectDir)}")
        } else {
            logger.warn("No .tflite found under external/recognition/ or via -PrecogTflitePath. App will warn at runtime.")
        }

        // Copy or derive unified labels
        val existingLabels = unifiedLabelsOut.exists()
        val labelsOverride = labelsOverrideProvider.orNull
        val labelsSource = when {
            existingLabels -> null
            labelsOverride != null -> labelsOverride
            else -> derivedJson
        }

        if (existingLabels) {
            logger.lifecycle("Unified labels JSON already present in assets; skipping copy.")
        } else if (labelsSource != null) {
            labelsSource.copyTo(unifiedLabelsOut, overwrite = true)
            logger.lifecycle("Copied unified labels: ${labelsSource.relativeToOrSelf(rootDir)} -> ${unifiedLabelsOut.relativeToOrSelf(project.projectDir)}")
        } else {
            val npyInfo = derivedNpy?.relativeToOrSelf(rootDir)
            if (npyInfo != null) {
                logger.lifecycle("No labels JSON source found; NPY candidate at $npyInfo (run generateLabelsJson to convert)")
            } else {
                logger.warn("No labels JSON or NPY found. Runtime will synthesize CLASS_i labels.")
            }
        }
    }
}

tasks.named("copyUnifiedRecognitionArtifacts").configure {
    mustRunAfter("generateLabelsJson")
}

// Ensure unified asset preparation tasks run before building the app
tasks.named("preBuild").configure {
    dependsOn("downloadHandLandmarker")
    dependsOn("generateLabelsJson")
    dependsOn("copyUnifiedRecognitionArtifacts")
}

