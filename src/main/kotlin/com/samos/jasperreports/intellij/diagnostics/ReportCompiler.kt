package com.samos.jasperreports.intellij.diagnostics

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.samos.jasperreports.intellij.project.JasperModuleResolver
import com.samos.jasperreports.intellij.project.ResolveResult
import com.samos.jasperreports.intellij.runner.ReportRunnerClient
import com.samos.jasperreports.intellij.runner.RunnerResult
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Compilation d'un rapport dans le runner, annulable, avec mémorisation des diagnostics (EF-47 à EF-50). */
object ReportCompiler {

    sealed interface Outcome {
        data class Done(val result: RunnerResult) : Outcome
        data class Failed(val message: String) : Outcome
        data object Cancelled : Outcome
    }

    fun compile(project: Project, file: VirtualFile, indicator: ProgressIndicator?): Outcome {
        val target = when (val resolved = JasperModuleResolver.resolve(project, file)) {
            is ResolveResult.Error -> return Outcome.Failed(resolved.message)
            is ResolveResult.Ok -> resolved.target
        }
        val client = ReportRunnerClient.getInstance(project)
        val future = client.compile(target)
        while (true) {
            if (indicator?.isCanceled == true) {
                client.cancel()
                return Outcome.Cancelled
            }
            try {
                val result = future.get(200, TimeUnit.MILLISECONDS)
                EngineDiagnostics.getInstance(project).set(file, result.diagnostics)
                return Outcome.Done(result)
            } catch (waiting: TimeoutException) {
                // on continue d'attendre en surveillant l'annulation
            } catch (cancelled: ProcessCanceledException) {
                throw cancelled
            } catch (error: Exception) {
                return Outcome.Failed(error.cause?.message ?: error.message ?: error.toString())
            }
        }
    }
}
