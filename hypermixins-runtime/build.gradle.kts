plugins {
    id("com.gradleup.shadow") version "9.3.0"
}

dependencies {
    implementation(project(":hypermixins-annotations"))
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set("hypermixins-runtime-${version}.jar")
    relocate("org.objectweb.asm", "net.echo.hypermixins.shaded.asm")
}

configure<JavaPluginExtension> {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            // Publish the shaded jar (ASM relocated in) as the main artifact so JitPack
            // consumers get a self-contained runtime. Only @annotations remains a real dep.
            artifact(tasks["shadowJar"])
            artifact(tasks.named("sourcesJar"))
            artifact(tasks.named("javadocJar"))
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()
            pom {
                name.set("HyperMixins Runtime")
                description.set("Runtime transformer, registry, and descriptor loader for HyperMixins. ASM is shaded in.")
                url.set("https://github.com/xEcho1337/hypermixins")
                withXml {
                    val deps = asNode().appendNode("dependencies")
                    val d = deps.appendNode("dependency")
                    d.appendNode("groupId", project.group.toString())
                    d.appendNode("artifactId", "hypermixins-annotations")
                    d.appendNode("version", project.version.toString())
                    d.appendNode("scope", "compile")
                }
            }
        }
    }
}
