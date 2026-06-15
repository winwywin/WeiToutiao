// ========================================================================
// 【第一层：顶层插件区】
// 必须严格作为文件的第一个独立闭包，禁止加任何“this.”前缀，防止污染类加载器
// ========================================================================
plugins {
    // 导入并应用 Android 应用程序核心插件（由 Version Catalog 统一托管版本）
    alias(libs.plugins.android.application)
}

// ========================================================================
// 【第二层：Android 核心构建配置区】
// 严格还原本地最原始的“android { ... }”闭包声明，确保作用域完全对齐
// ========================================================================
android {
    // 声明当前应用程序的全局唯一包名命名空间
    namespace = "com.example.test_micrott"

    // ========================================================================
    // 🛡️ 完璧归赵：原封不动保留你本地独特的次级 API 级别（Minor API Level）编译设定
    // ========================================================================
    compileSdk {
        // 指定物理编译环境为大版本 36 发布的第一个扩展次级 API 级别
        version = release(36) {
            // 声明次级 API 级别为 1，确保项目能安全调用该增量版本的底层接口
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // 声明应用在手机系统中的唯一数字身份证（应用 ID）
        applicationId = "com.example.test_micrott"
        // 声明应用支持的最低 Android 手机系统版本（API 24 即 Android 7.0）
        minSdk = 24
        // 声明应用针对的目标优化 Android 系统版本
        targetSdk = 36
        // 声明应用的版本号，用于应用商店识别升级
        versionCode = 1
        // 声明应用的版本名称，展示给用户看
        versionName = "1.0"

        // 声明自动化测试所使用的仪器驱动类
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 在正式发布包中关闭代码混淆与压缩，方便当前阶段调试
            isMinifyEnabled = false
            // 挂载系统默认的混淆优化规则文件以及本地自定义规则文件
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // ========================================================================
    // 🛡️ 核心修复：激活 ViewBinding 翻译官引擎（高兼容性标准闭包语法）
    // ========================================================================
    buildFeatures {
        // 强行开启视图绑定特性，激活后台自动为 res/layout/*.xml 生成绑定类的编译插件
        viewBinding = true
    }

    compileOptions {
        // 声明 Java 源代码编译的兼容性级别为 Java 11
        sourceCompatibility = JavaVersion.VERSION_11
        // 声明生成的字节码目标兼容性级别为 Java 11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// ========================================================================
// 【第三层：全局三方依赖库挂载区】
// ========================================================================
dependencies {
    // 1. 物理支持 by viewModels() 架构组件无缝委托的核心库
    implementation(libs.androidx.activity.ktx)
    // 2. 提供向下兼容的标准 Activity 与基础 UI 组件库
    implementation(libs.androidx.appcompat)
    // 3. 支撑二维复杂相对约束布局的核心底盘库
    implementation(libs.androidx.constraintlayout)
    // 4. 提供 Android 核心系统组件的 Kotlin 扩展方法支持库
    implementation(libs.androidx.core.ktx)
    // 5. 谷歌官方 Material 视觉设计语言核心组件库
    implementation(libs.material)

    // ========================================================================
    // 🛡️ 核心修复：手动补齐 MVI 必不可少的协程与生命周期感知流支持库
    // ========================================================================
    // 物理支持在 MainActivity 中拉起 lifecycleScope.launch 去收集状态流
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    // 物理支持在 ViewModel 内部拉起 viewModelScope 执行异步高低频数据清洗
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    // 物理支持 Kotlin 协程在 Android 主线程与异步线程之间的切换及流式编程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 单元测试与真机集成测试相关的依赖声明
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    // ========================================================================
    // PictureSelector + Glide：第三方相册选择器（替换自研 GalleryPicker）
    // ========================================================================
    implementation("io.github.lucksiege:pictureselector:v3.11.3")
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // ========================================================================
    // LeakCanary：运行时内存泄漏自动检测（仅 debug 构建包含）
    // ========================================================================
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}