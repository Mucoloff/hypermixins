plugins {
    java
    `maven-publish`
}

group = rootProject.group
version = rootProject.version

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// zero runtime deps — pure annotation module
configure<JavaPluginExtension> {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()

            pom {
                name.set("HyperMixins Annotations")
                description.set("Mixin annotation set for HyperMixins. Zero runtime dependencies.")
                url.set("https://github.com/xEcho1337/hypermixins")
            }
        }
    }
}
