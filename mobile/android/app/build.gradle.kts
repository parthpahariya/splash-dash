plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// Release signing and store versions come from CI (.github/scripts/android.sh); local builds keep the defaults.
val releaseKeystore: String? = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
fun requiredEnv(name: String): String =
    providers.environmentVariable(name).orNull ?: error("$name is required when ANDROID_KEYSTORE_PATH is set")

android {
    namespace = "com.boringman.splashdash"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.boringman.splashdash"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.environmentVariable("ANDROID_VERSION_CODE").orNull?.toInt() ?: 1
        versionName = providers.environmentVariable("ANDROID_VERSION_NAME").orNull ?: "1.0.0"
    }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = requiredEnv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = requiredEnv("ANDROID_KEY_ALIAS")
                keyPassword = requiredEnv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { testImplementation("junit:junit:4.13.2") }
