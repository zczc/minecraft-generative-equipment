dependencies { api(project(":core")) }
// 原生代码下载和编译只发生在构建阶段，包内携带固定版本运行库与许可证。
val buildNative by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir.parentFile)
    commandLine("node", "scripts/build-native.mjs")
    inputs.file("src/main/c/ge_quickjs.c")
    inputs.file(rootProject.projectDir.parentFile.resolve("scripts/build-native.mjs"))
    outputs.dir(layout.buildDirectory.dir("generated/native"))
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated/native")) }
tasks.processResources { dependsOn(buildNative) }
