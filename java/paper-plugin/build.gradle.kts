dependencies {
    implementation(project(":core"))
    implementation(project(":runtime-quickjs"))
    compileOnly("io.papermc.paper:paper-api:26.3.build.240-stable")
}
// Only project classes are bundled; Paper's library loader supplies JSON dependencies.
tasks.jar {
    archiveFileName.set("GenerativeEquipment-0.1.0.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(":core:classes", ":protocol:classes", ":runtime-quickjs:classes")
    from(project(":core").extensions.getByType<SourceSetContainer>()["main"].output)
    from(project(":protocol").extensions.getByType<SourceSetContainer>()["main"].output)
    from(project(":runtime-quickjs").extensions.getByType<SourceSetContainer>()["main"].output)
}
