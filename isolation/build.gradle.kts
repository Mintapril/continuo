plugins { id("com.android.application") }
android {
    namespace = "dev.dsh.pocket.isolation"
    compileSdk = 36
    defaultConfig { applicationId = "dev.dsh.pocket.isolation"; minSdk = 33; targetSdk = 36; versionCode = 4; versionName = "0.4-experiment" }
    // Test signing is retained, but profile-owner code must not expose run-as/debug hooks.
    buildTypes { getByName("debug") { isDebuggable = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { testImplementation("junit:junit:4.13.2") }
