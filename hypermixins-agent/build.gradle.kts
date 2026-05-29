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

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifact(tasks.shadowJar)
            artifact(tasks.named("sourcesJar"))
            artifact(tasks.named("javadocJar"))
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()
            pom {
                name.set("HyperMixins Agent")
                description.set("Drop-in -javaagent: jar that auto-registers mixins from META-INF/hypermixins/*.mixins.yml on the classpath.")
                url.set("https://github.com/xEcho1337/hypermixins")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("xEcho1337")
                        name.set("xEcho1337")
                    }
                }
            }
        }
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
