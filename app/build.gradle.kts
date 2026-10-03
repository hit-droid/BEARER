plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 是否构建原生推理层（llama.cpp JNI）。默认关闭，使工程无需 NDK 即可编译运行（走 StubEngine）。
// 接真实模型时：在 gradle.properties 设 offlineagent.native=true，或命令行加 -Pofflineagent.native=true，
// 并先执行 scripts/build_llama.sh 生成 libllamajni.so。
val useNativeLlm = providers.gradleProperty("offlineagent.native")
    .getOrElse("false")
    .toBoolean()

android {
    namespace = "com.offlineagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.offlineagent"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // 供代码判断当前构建是否包含原生推理库。
        buildConfigField("Boolean", "USE_NATIVE_LLM", useNativeLlm.toString())

        // 仅当开启原生开关时才要求 NDK，否则普通 ./gradlew assembleDebug 即可编译。
        if (useNativeLlm) {
            externalNativeBuild {
                cmake {
                    cppFlags {
                        append("-std=c++17")
                        append("-frtti")
                        append("-fexceptions")
                    }
                    arguments("-DLLAMA_BUILD=ON")
                }
            }
            ndk {
                // 与本地安装的 NDK 版本保持一致；可在 local.properties 用 ndk.dir 覆盖。
                version = "26.1.10909125"
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // llama.cpp 会产出 libllama.so 与胶水层 libllamajni.so
            pickFirsts += setOf("**/libllama.so")
        }
    }

    if (useNativeLlm) {
        externalNativeBuild {
            cmake {
                path = file("src/main/jni/CMakeLists.txt")
                version = "3.22.1+"
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // 协程与 JSON 序列化
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // 测试
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
