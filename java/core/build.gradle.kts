dependencies { api(project(":protocol")) }
sourceSets.main { resources.srcDir(rootProject.projectDir.parentFile.resolve("contracts")); resources.include("capabilities/**") }
