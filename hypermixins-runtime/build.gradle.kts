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
