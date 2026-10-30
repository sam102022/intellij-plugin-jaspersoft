package com.samos.jasperreports.intellij.runner

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.samos.jasperreports.intellij.JasperBundle
import com.samos.jasperreports.intellij.PLUGIN_ID
import com.samos.jasperreports.intellij.project.RunTarget
import java.io.BufferedWriter
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

class RunnerException(message: String) : RuntimeException(message)

/**
 * Client du runner (section 10.4). Un processus par projet, réutilisé entre deux aperçus tant que le JDK et
 * le classpath du module ne changent pas ; arrêté avec le projet. Le moteur ne tourne jamais dans l'IDE (ENF-04).
 */
@Service(Service.Level.PROJECT)
class ReportRunnerClient(private val project: Project) : Disposable {

    private val lock = Any()
    private var process: RunnerProcess? = null

    /** EF-67 */
    var timeoutSeconds: Long = 60

    /** Dernier dossier de rendu par rapport : supprimé au rendu suivant du même rapport. */
    private val lastPreviewDirs = java.util.concurrent.ConcurrentHashMap<Path, Path>()

    init {
        // Rendus d'une session précédente : jamais relus, on les supprime au démarrage.
        ApplicationManager.getApplication().executeOnPooledThread(Runnable {
            runCatching {
                Files.list(cacheDir()).use { dirs ->
                    dirs.filter { it.fileName.toString().startsWith("preview-") }.forEach(::deleteRecursively)
                }
            }
        })
    }

    fun compile(target: RunTarget): CompletableFuture<RunnerResult> =
        execute(target, request("compile", target), onPage = null)

    fun preview(target: RunTarget, options: PreviewOptions, onPage: (PageImage) -> Unit): CompletableFuture<RunnerResult> {
        val request = request("preview", target).apply {
            add("dataSource", options.dataSource.toJson())
            add("parameters", JsonObject().also { params ->
                options.parameters.forEach { (name, value) ->
                    params.add(name, JsonObject().apply {
                        addProperty("type", value.type)
                        addProperty("value", value.value)
                    })
                }
            })
            add("render", JsonObject().apply {
                addProperty("scale", options.scale)
                addProperty("maxPages", options.maxPages)
                addProperty("outputDir", newPreviewDir(target.jrxml).toString())
            })
        }
        return execute(target, request, onPage)
    }

    /** Interrompt la requête en cours sans arrêter le processus. */
    fun cancel() {
        synchronized(lock) { process }?.sendCancel()
    }

    private fun request(action: String, target: RunTarget) = JsonObject().apply {
        addProperty("action", action)
        addProperty("jrxml", target.jrxml.toString())
        addProperty("cacheDir", cacheDir().toString())
    }

    private fun execute(target: RunTarget, request: JsonObject, onPage: ((PageImage) -> Unit)?): CompletableFuture<RunnerResult> {
        val runner = try {
            processFor(target)
        } catch (e: Exception) {
            return CompletableFuture.failedFuture<RunnerResult>(e)
        }
        return runner.send(request, onPage)
            .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .exceptionallyCompose { error ->
                if (error is TimeoutException || error.cause is TimeoutException) {
                    restart(runner)
                    CompletableFuture.failedFuture<RunnerResult>(RunnerException(JasperBundle.message("runner.error.timeout", timeoutSeconds)))
                } else {
                    CompletableFuture.failedFuture<RunnerResult>(error)
                }
            }
    }

    private fun processFor(target: RunTarget): RunnerProcess = synchronized(lock) {
        process?.takeIf { it.isAlive && it.key == target.processKey }?.let { return it }
        process?.destroy()
        val expected = expectedRunnerJar()
        val jar = expected?.takeIf { Files.isRegularFile(it) }
            ?: throw RunnerException(JasperBundle.message("runner.error.noRunnerJar", expected?.toString() ?: PLUGIN_ID))
        RunnerProcess.start(target, jar).also { process = it }
    }

    private fun restart(runner: RunnerProcess) = synchronized(lock) {
        runner.destroy()
        if (process === runner) process = null
    }

    /** Nouveau dossier de rendu pour ce rapport ; celui du rendu précédent est supprimé (les pages sont relues depuis le nouveau). */
    private fun newPreviewDir(jrxml: Path): Path {
        val dir = Files.createTempDirectory(cacheDir(), "preview-")
        lastPreviewDirs.put(jrxml, dir)?.let(::deleteRecursively)
        return dir
    }

    private fun deleteRecursively(dir: Path) {
        runCatching {
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder<Path>()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun cacheDir(): Path =
        Path.of(PathManager.getSystemPath(), "jasperreports", project.locationHash).also { Files.createDirectories(it) }

    override fun dispose() {
        synchronized(lock) {
            process?.destroy()
            process = null
        }
    }

    companion object {
        fun getInstance(project: Project): ReportRunnerClient = project.service()

        /**
         * Le JAR est livré dans `<plugin>/runner/`, hors du classpath du plugin (voir build.gradle.kts). Le dossier du
         * plugin se déduit du JAR qui contient cette classe (`<plugin>/lib/x.jar`), sans API interne de la plateforme.
         */
        fun expectedRunnerJar(): Path? =
            PathManager.getJarPathForClass(ReportRunnerClient::class.java)
                ?.let { Path.of(it).parent?.parent }
                ?.resolve("runner/jasper-runner.jar")
    }
}

/** Un processus runner et ses requêtes en cours. */
internal class RunnerProcess private constructor(val key: String, private val process: Process) {

    private class Pending(val future: CompletableFuture<RunnerResult>, val onPage: ((PageImage) -> Unit)?)

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val writer: BufferedWriter = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)
    private val stderrTail = ArrayDeque<String>()

    val isAlive: Boolean get() = process.isAlive

    init {
        ApplicationManager.getApplication().executeOnPooledThread(Runnable { readStdout() })
        ApplicationManager.getApplication().executeOnPooledThread(Runnable { readStderr() })
    }

    fun send(request: JsonObject, onPage: ((PageImage) -> Unit)?): CompletableFuture<RunnerResult> {
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<RunnerResult>()
        pending[id] = Pending(future, onPage)
        request.addProperty("id", id)
        try {
            write(request)
        } catch (e: Exception) {
            pending.remove(id)
            future.completeExceptionally(RunnerException(JasperBundle.message("runner.error.stopped") + "\n" + tail()))
        }
        return future
    }

    fun sendCancel() {
        runCatching { write(JsonObject().apply { addProperty("action", "cancel") }) }
    }

    fun destroy() {
        runCatching { write(JsonObject().apply { addProperty("action", "shutdown") }) }
        if (!runCatching { process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) {
            process.destroyForcibly()
        }
        failAll()
    }

    private fun write(message: JsonObject) = synchronized(writer) {
        writer.write(GSON.toJson(message))
        writer.newLine()
        writer.flush()
    }

    private fun readStdout() {
        try {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines -> lines.forEach(::dispatch) }
        } catch (e: Exception) {
            LOG.debug("Runner output closed", e)
        } finally {
            failAll()
        }
    }

    private fun readStderr() {
        try {
            process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    LOG.info("[runner] $line")
                    synchronized(stderrTail) {
                        stderrTail.addLast(line)
                        if (stderrTail.size > 40) stderrTail.removeFirst()
                    }
                }
            }
        } catch (e: Exception) {
            LOG.debug("Runner error stream closed", e)
        }
    }

    private fun dispatch(line: String) {
        val message = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: run {
            LOG.warn("Unexpected runner output: $line")
            return
        }
        when (message.get("type")?.asString) {
            "ready" -> LOG.info("Runner ready: $message")
            "progress" -> {
                val id = message.get("id").asLong
                pending[id]?.onPage?.invoke(
                    PageImage(message.get("page").asInt, message.get("total").asInt, Path.of(message.get("image").asString))
                )
            }
            "result" -> {
                val id = message.get("id").asLong
                pending.remove(id)?.future?.complete(parseResult(message))
            }
            else -> LOG.warn("Unknown runner message: $line")
        }
    }

    private fun failAll() {
        if (pending.isEmpty()) return
        val error = RunnerException(JasperBundle.message("runner.error.stopped") + "\n" + tail())
        pending.values.forEach { it.future.completeExceptionally(error) }
        pending.clear()
    }

    private fun tail(): String = synchronized(stderrTail) { stderrTail.joinToString("\n") }

    companion object {
        private val LOG = logger<RunnerProcess>()
        private val GSON = Gson()
        private const val MAIN_CLASS = "com.samos.jasperreports.runner.ReportRunner"

        fun start(target: RunTarget, runnerJar: Path): RunnerProcess {
            // Fichier d'arguments : le classpath d'un projet Maven dépasse vite la limite de ligne de commande Windows.
            val classpath = (target.classpath + runnerJar.toString()).joinToString(File.pathSeparator)
            val argFile = Files.createTempFile("jasper-runner", ".args").toFile().apply {
                deleteOnExit()
                writeText("-cp\n\"" + classpath.replace("\\", "\\\\") + "\"\n", StandardCharsets.UTF_8)
            }
            val commandLine = GeneralCommandLine(
                target.javaExecutable.toString(),
                "-Djava.awt.headless=true",
                "-Dfile.encoding=UTF-8",
                "@" + argFile.absolutePath,
                MAIN_CLASS,
            ).withCharset(StandardCharsets.UTF_8)
                .withWorkDirectory(target.jrxml.parent.toFile())

            LOG.info("Starting JasperReports runner for module ${target.moduleName} (JasperReports ${target.jasperVersion})")
            return RunnerProcess(target.processKey, commandLine.createProcess())
        }

        private fun parseResult(message: JsonObject): RunnerResult {
            val status = when (message.get("status")?.asString) {
                "ok" -> RunStatus.OK
                "cancelled" -> RunStatus.CANCELLED
                else -> RunStatus.ERROR
            }
            val diagnostics = message.getAsJsonArray("diagnostics")?.map { element ->
                val d = element.asJsonObject
                RunnerDiagnostic(
                    severity = when (d.get("severity")?.asString) {
                        "warning" -> Severity.WARNING
                        "info" -> Severity.INFO
                        else -> Severity.ERROR
                    },
                    message = d.get("message")?.asString.orEmpty(),
                    source = d.get("source")?.takeUnless { it.isJsonNull }?.asString,
                    code = d.get("code")?.takeUnless { it.isJsonNull }?.asString,
                    line = d.get("line")?.takeUnless { it.isJsonNull }?.asInt,
                )
            }.orEmpty()
            return RunnerResult(
                status,
                message.get("pages")?.asInt ?: 0,
                diagnostics,
                message.get("dataSource")?.takeUnless { it.isJsonNull }?.asString,
            )
        }
    }
}
