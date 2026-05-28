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
