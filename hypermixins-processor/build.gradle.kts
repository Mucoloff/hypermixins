dependencies {
    compileOnly(project(":hypermixins-annotations"))
    // KSP API — handles both Java and Kotlin sources
    compileOnly("com.google.devtools.ksp:symbol-processing-api:2.3.9")
    // JavaPoet for generating Java $$Descriptor source files
    implementation("com.squareup:javapoet:1.13.0")
}
