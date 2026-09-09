plugins {
    id("java")
    id("application")
}

application {
    mainClass.set("io.hyperion.runtime.vulkan.demo.Phase0Demo")
    // FFM downcalls to libvulkan require native access to be explicitly
    // granted on JDK 22/23 (JEP 454's --enable-native-access gate); JDK 24+
    // relaxes this per-module via the Enable-Native-Access manifest entry.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}
