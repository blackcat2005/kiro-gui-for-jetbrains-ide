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
                <li>0.1.3: smoother chat and start-up: the chat is drawn block by block (only what changed), in the Swing view the HTML is built off the UI thread and hidden chats are not drawn, and the embedded browser is created only when a chat is on screen, so restoring several chats no longer freezes the IDE</li>
                <li>0.1.3: background jobs and the debugger for Kiro: it can start slow commands (Run tool window) and debug sessions without blocking the chat, and is told when a command ends or the debugger stops (Settings → Tools → Kiro → IDE tools)</li>
                <li>0.1.3: several chats at once (+ in the tool window title, one tab per chat, each with its own conversation and background jobs); jobs are shown in a panel under the chat instead of a separate tab; new setting "Shell for Kiro's commands" (Git Bash, Command Prompt, WSL, custom) for places where PowerShell is blocked</li>
                <li>0.1.3: test_run: Kiro runs all of the project's tests (or a file, folder or single test) with the IDE's test runner, is woken with a report of the failures (test, file:line, assertion, frames in your code) or the compile errors, and can rerun only the failed tests</li>
                <li>0.1.3: Jobs panel: running and ended jobs with live output; stop, show console, attach output to the chat. Kiro can read run and debug consoles (run_logs), and each debugger stop includes the program's last output lines</li>
                <li>0.1.3: fixed: files Kiro edited kept showing the old text in an open editor (kiro-cli 3.x sends lower-case drive letters on Windows); the IDE now also re-reads files after Kiro's tools and commands finish</li>
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

    // ./gradlew verifyPlugin: checks the built plugin against real IDE builds (downloads them, nothing is installed).
    pluginVerification {
        ides {
            // Pinned to the oldest, a middle and the newest supported line (all already in the Gradle cache) instead of
            // select{}, which pulled every release in 251..263 for both editions (6+ GB) and failed on flaky downloads.
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6")
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaUltimate, "2026.3")
        }
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
