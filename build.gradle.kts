plugins {
    java
    `maven-publish`
    kotlin("jvm") version "2.3.20" apply false
    id("com.gradleup.shadow") version "9.3.0" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
}

allprojects {
    repositories {
        mavenCentral()
        maven {
            name = "brain4jReleases"
            url = uri("https://repo.brain4j.org/releases")
        }
    }
}

subprojects {
    // these modules manage their own plugins/toolchains
    if (name == "hypermixins-annotations" || name == "hypermixins-intellij-plugin") return@subprojects

    apply(plugin = "java")
    apply(plugin = "maven-publish")
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = rootProject.group
    version = rootProject.version

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
    }

    dependencies {
        "implementation"(kotlin("stdlib"))
        "implementation"("org.jetbrains:annotations:26.0.2")
        "implementation"("org.ow2.asm:asm:9.8")
        "implementation"("org.ow2.asm:asm-tree:9.8")

        "testImplementation"("org.ow2.asm:asm-util:9.8")
        "testImplementation"(platform("org.junit:junit-bom:6.0.0-M1"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        "testImplementation"(kotlin("test"))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    configure<JavaPluginExtension> {
        withSourcesJar()
        withJavadocJar()
    }

    configure<PublishingExtension> {
        repositories {
            maven {
                name = "hypermixins"
                val releasesRepo = uri("https://repo.brain4j.org/releases")
                val snapshotsRepo = uri("https://repo.brain4j.org/snapshots")
                url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepo else releasesRepo
                credentials {
                    username = findProperty("BRAIN4J_USERNAME") as String?
                    password = findProperty("BRAIN4J_TOKEN") as String?
                }
            }
        }
    }
}
