plugins {
    id("com.gradleup.shadow") version "9.3.0"
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set("hypermixins-api-${version}.jar")
    relocate("org.objectweb.asm", "net.echo.hypermixins.shaded.asm")
}

// Remove ASM from published POM — it is shaded into the jar
configurations.named("shadow") {
    isTransitive = false
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["shadow"])
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()

            artifact(tasks.named("sourcesJar"))
            artifact(tasks.named("javadocJar"))

            pom.withXml {
                @Suppress("UNCHECKED_CAST")
                val root = asNode()
                @Suppress("UNCHECKED_CAST")
                val depsNode = (root.get("dependencies") as groovy.util.NodeList).firstOrNull() as? groovy.util.Node
                @Suppress("UNCHECKED_CAST")
                (depsNode?.get("dependency") as? groovy.util.NodeList)?.removeIf { dep ->
                    dep as groovy.util.Node
                    ((dep.get("groupId") as groovy.util.NodeList).firstOrNull() as? groovy.util.Node)?.text() == "org.ow2.asm"
                }
            }

            pom {
                name.set("HyperMixins API")
                description.set("Lightweight Java-agent mixin library using ASM bytecode transformation.")
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
