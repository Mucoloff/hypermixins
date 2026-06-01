plugins {
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = rootProject.group
version = rootProject.version

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // Runtime ExpressionValidator (pure, no ASM) drives the @Expression syntax annotator.
    implementation(project(":hypermixins-runtime"))
    intellijPlatform {
        local(file(System.getProperty("user.home") + "/Applications/IntelliJ IDEA.app"))
        bundledPlugin("com.intellij.java")
        instrumentationTools()
    }
}

tasks.named("buildSearchableOptions") { enabled = false }

intellijPlatform {
    pluginConfiguration {
        name = "HyperMixins"
        version = project.version.toString()

        description = """
            IDE support for HyperMixins — a Java-agent mixin library.
            <ul>
              <li>Gutter icons with navigation to mixin target classes and methods</li>
              <li>Inspections: target class/method not found, signature mismatch, @Definition shape</li>
              <li>Autocomplete for <code>@At(desc = "...")</code> call-site descriptors</li>
              <li>Inline @Expression DSL syntax validation</li>
            </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }
}
