plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "hypermixins"
include("hypermixins-annotations")
include("hypermixins-processor")
include("hypermixins-runtime")
include("hypermixins-example")
include("hypermixins-intellij-plugin")
