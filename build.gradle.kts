plugins {
    id("org.jetbrains.intellij.platform") version "2.5.0"
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
}

group = "com.kirogui.jetbrains"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
        instrumentationTools()
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.1.0")
    testRuntimeOnly("junit:junit:4.13.2") // IntelliJ Platform's test runtime still references JUnit 4 rules
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // Optional: -PtranscriptOut=<dir> makes TranscriptRenderTest write PNG previews of the chat transcript.
    providers.gradleProperty("transcriptOut").orNull?.let { systemProperty("transcript.out", it) }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }
        changeNotes = """
            <ul>
                <li>Chat font family and size settings; livelier look with starter prompts on an empty chat</li>
                <li>kiro-cli arguments and environment variables in Settings | Tools | Kiro</li>
                <li>Retry and Restart buttons reconnect to the open conversation when kiro-cli fails</li>
                <li>Long descriptions in the / and @ popups are shortened instead of overflowing</li>
                <li>New plugin, session and tool window icons; requires IntelliJ 2025.1 or newer</li>
            </ul>
        """.trimIndent()
    }

    // Marketplace upload: set PUBLISH_TOKEN (and, if you sign, CERTIFICATE_CHAIN / PRIVATE_KEY / PRIVATE_KEY_PASSWORD).
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

kotlin {
    jvmToolchain(21)
}

// IDE 2025.1 bundles Kotlin stdlib 2.1.x; staying on the 2.0 API level is harmless; don't use newer stdlib APIs.
kotlin {
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}
