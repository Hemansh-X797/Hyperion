plugins {
    id("java")
    id("application")
}

application {
    mainClass.set("io.hyperion.core.demo.Phase3Demo")
}

dependencies {
    implementation(project(":hyperion-api"))
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}
