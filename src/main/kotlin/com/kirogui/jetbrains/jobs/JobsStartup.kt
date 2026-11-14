package com.kirogui.jetbrains.jobs

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts [JobService] with the project, so the console of every run is kept from the start (for `run_logs`). */
class JobsStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(JobService::class.java)
    }
}
