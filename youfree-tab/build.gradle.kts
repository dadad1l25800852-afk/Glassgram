plugins {
    id("com.android.application")
}
android {
    namespace = "dev.youfree.tab"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.youfree.tab"
        minSdk = 29
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    // Compile-time types only: the framework provides these at runtime.
    compileOnly(project(":xposed-stubs"))
}
