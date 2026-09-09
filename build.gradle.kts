plugins {
    id("java")
}

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            // Bumped to 22 for Phase 0 (finalized FFM, JEP 454); now pinned
            // to 24 as of Phase 2, since java.lang.classfile (JEP 484) only
            // finalizes in JDK 24. FFM continues to work unchanged on 24.
            languageVersion.set(JavaLanguageVersion.of(24))
        }
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-Xlint:all"))
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }

    tasks.withType<JavaExec> {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}
