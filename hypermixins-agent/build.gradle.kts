plugins {
    id("com.gradleup.shadow") version "9.3.0"
}

dependencies {
    implementation(project(":hypermixins-annotations"))
    implementation(project(":hypermixins-runtime"))
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "net.echo.hypermixins.agent.HyperMixinsAgent",
            "Agent-Class" to "net.echo.hypermixins.agent.HyperMixinsAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
        )
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set("hypermixins-agent-${version}.jar")
    relocate("org.objectweb.asm", "net.echo.hypermixins.shaded.asm")
    manifest {
        attributes(
            "Premain-Class" to "net.echo.hypermixins.agent.HyperMixinsAgent",
            "Agent-Class" to "net.echo.hypermixins.agent.HyperMixinsAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
        )
    }
}

val exampleJarTask = rootProject.project(":hypermixins-example").tasks.named("jar")

tasks.test {
    dependsOn(tasks.shadowJar, exampleJarTask)
    systemProperty("hypermixins.agent.jar",
        tasks.shadowJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
    systemProperty("hypermixins.example.jar",
        exampleJarTask.get().outputs.files.singleFile.absolutePath)
    systemProperty("hypermixins.testworld.jar",
        "${rootProject.projectDir}/hypermixins-example/run/test-world-1.0.jar")
    systemProperty("hypermixins.java.home",
        System.getProperty("java.home"))
}
