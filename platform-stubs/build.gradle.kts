import java.util.Properties

plugins { `java-library` }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
val local = Properties().apply { rootProject.file("local.properties").reader(Charsets.UTF_8).use { load(it) } }
dependencies { compileOnly(files("${local.getProperty("sdk.dir")}/platforms/android-36/android.jar")) }
