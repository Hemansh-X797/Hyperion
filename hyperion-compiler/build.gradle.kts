plugins {
    id("java")
    id("application")
}

application {
    mainClass.set("io.hyperion.compiler.demo.Phase8Demo")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "--add-modules", "jdk.incubator.vector")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.vector"))
}

tasks.withType<Test> {
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

dependencies {
    implementation(project(":hyperion-api"))
    implementation(project(":hyperion-core"))
    implementation(project(":hyperion-runtime-vulkan"))
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}
