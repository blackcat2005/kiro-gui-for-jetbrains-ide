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
                <li>0.1.3: works with kiro-cli 3.x as well as 2.x: the model list and model switching, context %, credits per turn, slash commands and warnings such as a failing MCP server now appear on both engines; new "Agent engine" setting</li>
                <li>0.1.3: Library lists your agents and MCP servers and shows which are on (the agent in use is marked; a server that is disabled or replaced by the workspace entry shows as off)</li>
                <li>0.1.3: new "Start timeout" setting (default 2 minutes) for slow kiro-cli starts; start-up errors show what kiro-cli printed; restarting also stops the processes kiro-cli started; start-up timings are written to idea.log</li>
                <li>Attach files, selected code and images: Attach button, @ file picker, Ctrl+V for screenshots and files; the open editor file is attached automatically (can be turned off)</li>
                <li>Long pastes become compact chips; "Send to Kiro" attaches the selection as a snippet instead of filling the input</li>
                <li>Diffs are shown as coloured rows with Copy diff / Copy new</li>
                <li>Shift+Enter inserts a new line</li>
                <li>Run kiro-cli through a shell of your choice (PowerShell, cmd, bash, WSL or custom)</li>
                <li>Notification (balloon and system) when Kiro finishes a reply</li>
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
