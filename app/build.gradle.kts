plugins {
    id("com.android.application")
}

android {
    namespace = "com.lowlatencygamedetection.tool"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lowlatencygamedetection.tool"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // 1. 指定 ABI（模拟器用x86_64，真机用arm64-v8a）
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64") // 为模拟器保留x86_64
        }

        // 2. 告诉Gradle我们要用CMake
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    // 3. 指向CMakeLists.txt路径
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // 4. 启用Prefab（必须在android块内）
    buildFeatures {
        prefab = true
    }

    applicationVariants.configureEach {
        outputs.configureEach {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "LLGameStandardTest.apk"
        }
    }

}

dependencies {
    // 5. Oboe官方AAR（通过Prefab集成）
    implementation("com.google.oboe:oboe:1.8.1")

    // 6. 游戏模式API（可选，用于Android 12+）
    implementation("androidx.games:games-activity:2.0.2")

    // 基础依赖（请确保项目中有这些库）
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
}