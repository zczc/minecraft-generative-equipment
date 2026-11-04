plugins { base }
allprojects { group = "dev.generative.equipment"; version = "0.1.0" }
subprojects {
    apply(plugin = "java-library")
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
    configure<JavaPluginExtension> { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        systemProperty("ge.repo", rootProject.projectDir.parentFile.absolutePath)
    }
    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
}
