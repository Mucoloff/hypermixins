dependencies {
    compileOnly(project(":hypermixins-annotations"))
    // KSP API — handles both Java and Kotlin sources
    compileOnly("com.google.devtools.ksp:symbol-processing-api:2.3.9")
    // JavaPoet for generating Java $$Descriptor source files
    implementation("com.squareup:javapoet:1.13.0")
}

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
                name.set("HyperMixins Processor")
                description.set("KSP processor that bakes mixin annotations into a \$\$Descriptor consumed by the runtime.")
                url.set("https://github.com/xEcho1337/hypermixins")
            }
        }
    }
}
