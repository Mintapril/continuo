plugins { id("com.android.application") }
android {
    namespace = "dev.dsh.pocket.fixture"
    compileSdk = 36
    // -PfixtureId=dev.dsh.pocket.x builds the same fixture under a different package name, so a launcher
    // experiment can install a package the phone's home screen has never seen (the default keeps the fixture id).
    defaultConfig { applicationId = (project.findProperty("fixtureId") as String?) ?: "dev.dsh.pocket.fixture"; minSdk = 28; targetSdk = 36; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
