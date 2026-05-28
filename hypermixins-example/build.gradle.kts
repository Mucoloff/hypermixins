apply(plugin = "com.google.devtools.ksp")

dependencies {
    compileOnly(files("./run/test-world-1.0.jar"))
    implementation(project(":hypermixins-annotations"))
    implementation(project(":hypermixins-runtime"))
    "ksp"(project(":hypermixins-processor"))
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "net.echo.tests.MixinTest",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
        )
    }
}
