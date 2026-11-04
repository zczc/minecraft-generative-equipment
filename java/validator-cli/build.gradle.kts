apply(plugin = "application")
dependencies { implementation(project(":core")); implementation(project(":runtime-quickjs")) }
configure<JavaApplication> { mainClass.set("dev.generative.equipment.validator.ValidatorMain") }
tasks.jar {
    // 胖 JAR 解包依赖产物前，必须先构建各 Java 子模块。
    dependsOn(":protocol:jar", ":core:jar", ":runtime-quickjs:jar")
    archiveFileName.set("validator-cli.jar")
    manifest { attributes["Main-Class"] = "dev.generative.equipment.validator.ValidatorMain" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}
