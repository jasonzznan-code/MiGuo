plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Build-time default API key: local.properties miguo.defaultApiKey, or ../.secrets/default_api_key.
// Never commit the raw key; local.properties and .secrets/ are gitignored.
// IMPORTANT: only internal flavor may inject a non-empty DEFAULT_API_KEY. store always "".
fun loadDefaultApiKey(): String {
    val lp = rootProject.file("local.properties")
    if (lp.exists()) {
        for (raw in lp.readLines()) {
            val line = raw.trim()
            if (line.startsWith("miguo.defaultApiKey=")) {
                val v = line.substringAfter("=").trim()
                if (v.isNotEmpty()) return v
            }
        }
    }
    val secret = rootProject.file("../.secrets/default_api_key")
    if (secret.exists()) return secret.readText(Charsets.UTF_8).trim()
    return ""
}

fun loadProp(key: String, fallback: String): String {
    val lp = rootProject.file("local.properties")
    if (lp.exists()) {
        for (raw in lp.readLines()) {
            val line = raw.trim()
            if (line.startsWith("$key=")) {
                val v = line.substringAfter("=").trim()
                if (v.isNotEmpty()) return v
            }
        }
    }
    val fromCmd = (findProperty(key) as? String)?.trim()
    if (!fromCmd.isNullOrEmpty()) return fromCmd
    return fallback
}

fun isPublicHttpsUrl(url: String): Boolean {
    val u = url.trim()
    if (!u.startsWith("https://", ignoreCase = true)) return false
    val withoutScheme = u.substring(8) // after https://
    val host = withoutScheme.substringBefore('/').substringBefore(':').lowercase()
    if (host.isEmpty()) return false
    if (host == "localhost" || host == "127.0.0.1" || host == "::1") return false
    if (host.startsWith("192.168.") || host.startsWith("10.")) return false
    if (host.startsWith("172.")) {
        val second = host.split(".").getOrNull(1)?.toIntOrNull()
        if (second != null && second in 16..31) return false
    }
    return true
}

fun javaStringLiteral(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val defaultApiKeyForBuild = loadDefaultApiKey()
// Do NOT default AUTH to unreachable LAN for store; empty unless local.properties sets public HTTPS.
val defaultAuthBaseForBuild = loadProp("miguo.defaultAuthBase", "")
val defaultChatBaseForBuild = loadProp("miguo.defaultChatBase", "https://ai.njzyb.com:3010")

fun loadKeystoreProperties(): Map<String, String> {
    val f = rootProject.file("keystore.properties")
    if (!f.exists()) return emptyMap()
    val out = linkedMapOf<String, String>()
    for (raw in f.readLines()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val idx = line.indexOf('=')
        if (idx <= 0) continue
        out[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
    }
    return out
}

val keystoreProperties = loadKeystoreProperties()
val hasReleaseKeystore = keystoreProperties.isNotEmpty()

android {
    namespace = "com.miguo.voice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.miguo.voice"
        minSdk = 26
        targetSdk = 35
        // 2026-11-05：上线候选；含「内容由 AI 生成」；联系邮箱不变；versionName 1.0.0 / vc104 storeRelease
        versionCode = 104
        versionName = "1.0.0"
        // Safe default: empty. Flavors override (store stays empty; internal may inject for local dev).
        buildConfigField("String", "DEFAULT_API_KEY", javaStringLiteral(""))
        // Fallback only; product flavors override with authoritative defaults.
        buildConfigField("String", "DEFAULT_AUTH_BASE", javaStringLiteral(defaultAuthBaseForBuild))
        buildConfigField("String", "DEFAULT_CHAT_BASE", javaStringLiteral(defaultChatBaseForBuild))
        buildConfigField("String", "MIGUO_CHANNEL", javaStringLiteral("default"))
        buildConfigField("boolean", "IS_INTERNAL", "false")
        buildConfigField("boolean", "ALLOW_SKIP_LOGIN", "false")
        buildConfigField("boolean", "SHOW_INTERNAL_OTP_HINT", "false")
        // Open-source slim defaults; flavors override. store=false hides C1/C2/C6 paths.
        buildConfigField("boolean", "FEATURE_VOICE", "false")
        buildConfigField("boolean", "FEATURE_PLAN", "false")
    }

    flavorDimensions += "channel"
    productFlavors {
        create("internal") {
            dimension = "channel"
            // Keep applicationId = com.miguo.voice (no .miguo / .internal suffix).
            versionNameSuffix = "-internal"
            // 内验默认：公网 AUTH /nm（与 store 一致）+ 公网 CHAT
            buildConfigField("String", "DEFAULT_AUTH_BASE", javaStringLiteral("https://ai.njzyb.com:3010/nm"))
            buildConfigField("String", "DEFAULT_CHAT_BASE", javaStringLiteral("https://ai.njzyb.com:3010"))
            buildConfigField("String", "MIGUO_CHANNEL", javaStringLiteral("internal"))
            buildConfigField("boolean", "IS_INTERNAL", "true")
            buildConfigField("boolean", "ALLOW_SKIP_LOGIN", "true")
            buildConfigField("boolean", "SHOW_INTERNAL_OTP_HINT", "true")
            // internal 可注入本地开发 key（风险：勿分发；旧 key 轮换依赖运维/服务端）
            buildConfigField("String", "DEFAULT_API_KEY", javaStringLiteral(defaultApiKeyForBuild))
            buildConfigField("boolean", "FEATURE_VOICE", "true")
            buildConfigField("boolean", "FEATURE_PLAN", "true")
        }
        create("store") {
            dimension = "channel"
            // store: no versionNameSuffix — versionName must be exactly 1.0.0
            // 上架：单公网地址；AUTH 走 /nm 反代，勿回退空或 trycloudflare。
            // Single public host: AUTH via memory_core /nm reverse-proxy to cloud-relay :8787
            val storeAuth = "https://ai.njzyb.com:3010/nm"
            buildConfigField("String", "DEFAULT_AUTH_BASE", javaStringLiteral(storeAuth))
            val storeChatRaw = defaultChatBaseForBuild.trim()
            val storeChat = if (isPublicHttpsUrl(storeChatRaw)) storeChatRaw else "https://ai.njzyb.com:3010"
            buildConfigField("String", "DEFAULT_CHAT_BASE", javaStringLiteral(storeChat))
            buildConfigField("String", "MIGUO_CHANNEL", javaStringLiteral("store"))
            buildConfigField("boolean", "IS_INTERNAL", "false")
            buildConfigField("boolean", "ALLOW_SKIP_LOGIN", "false")
            buildConfigField("boolean", "SHOW_INTERNAL_OTP_HINT", "false")
            // P0：store 强制空 DEFAULT_API_KEY，禁止把 local.properties 的 key 打进上架包
            buildConfigField("String", "DEFAULT_API_KEY", javaStringLiteral(""))
            buildConfigField("boolean", "FEATURE_VOICE", "false")
            buildConfigField("boolean", "FEATURE_PLAN", "false")
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                val storeFilePath = keystoreProperties["storeFile"]
                    ?: error("keystore.properties missing storeFile")
                storeFile = rootProject.file(storeFilePath)
                storePassword = keystoreProperties["storePassword"]
                    ?: error("keystore.properties missing storePassword")
                keyAlias = keystoreProperties["keyAlias"]
                    ?: error("keystore.properties missing keyAlias")
                keyPassword = keystoreProperties["keyPassword"]
                    ?: error("keystore.properties missing keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // release 默认 debuggable=false；仅当 keystore.properties 存在时挂签名
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
