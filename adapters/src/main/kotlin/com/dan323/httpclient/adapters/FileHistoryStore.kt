package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.RunReport
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.port.out.HistoryRun
import com.dan323.httpclient.core.port.out.HistoryStore
import com.dan323.httpclient.core.port.out.StoredResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * History on disk. Layout, for the `.http` file `api.http`, by default in the file's folder:
 *
 * ```
 * .http-history/
 *   api.http/                       one folder per .http file (name sanitized, see [HistoryNames])
 *     20261106T101500123Z-a1b2c3d4/ one folder per run; the name is the run id (UTC time + 8 random hex digits)
 *       run.json                    the index of the run, written last (a run without it is incomplete and ignored)
 *       0.body  1.body ...          raw response bodies by request index (cut to maxBodyBytes)
 * ```
 *
 * `run.json` has `version`, `id`, `timestamp`, the `report` (same JSON as [JsonRunReport]) and `responses` (per
 * request: `index`, `status`, `headers`, `durationMillis`, `bodySize`, `bodyTruncated`). There is no shared index file, so
 * two processes recording at the same time cannot overwrite each other's entries; every file is written
 * atomically (temp file in the same folder, then move).
 *
 * Stored responses are raw: bodies and headers may contain secrets. On POSIX, files and folders are created
 * owner-only (`rw-------` / `rwx------`) and never readable by others, even briefly. Nothing outside the history
 * folder is written. Only the newest [maxRuns] runs per file are kept. A body larger than [maxBodyBytes] is
 * stored cut, with `bodyTruncated` set. Corrupt or foreign entries are skipped with a call to [onWarning].
 *
 * @param historyDirFor the history folder of a `.http` file; see [nextToHttpFile] and [underRoot]
 */
class FileHistoryStore(
    private val historyDirFor: (Path) -> Path = nextToHttpFile,
    private val maxRuns: Int = DEFAULT_MAX_RUNS,
    private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
    private val clock: Clock = Clock.systemUTC(),
    private val onWarning: (String) -> Unit = { System.err.println("warning: $it") },
) : HistoryStore {
    init {
        require(maxRuns >= 1) { "maxRuns must be at least 1" }
        require(maxBodyBytes >= 0) { "maxBodyBytes must not be negative" }
    }

    private val random = SecureRandom()

    override fun record(httpFile: Path, report: RunReport, responses: List<HttpResponse?>): HistoryRun {
        require(responses.size == report.entries.size) { "responses (${responses.size}) must match the report entries (${report.entries.size})" }
        val fileDir = fileDir(httpFile)
        createPrivateDirectories(fileDir)
        val now = clock.instant()
        val (id, runDir) = createRunDir(fileDir, now)
        try {
            val stored = responses.mapIndexedNotNull { i, r ->
                if (r == null) return@mapIndexedNotNull null
                val kept = if (r.body.size > maxBodyBytes) r.body.copyOf(maxBodyBytes) else r.body
                writeAtomically(runDir.resolve("$i.body"), kept)
                ResponseDto(i, r.statusCode, r.headers.map { HeaderDto(it.name, it.value) }, r.durationMillis, r.body.size.toLong(), r.body.size > maxBodyBytes)
            }
            val dto = RunDto(FORMAT_VERSION, id, now.toString(), JSON.parseToJsonElement(JsonRunReport.render(report)), stored)
            writeAtomically(runDir.resolve(RUN_FILE), JSON.encodeToString(dto).toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            deleteTree(runDir)
            throw e
        }
        prune(fileDir)
        return HistoryRun(id, now, report)
    }

    override fun list(httpFile: Path): List<HistoryRun> {
        val fileDir = fileDir(httpFile)
        return runIds(fileDir).mapNotNull { id -> loadRun(fileDir, id)?.let { HistoryRun(id, it.instant(), it.report()) } }
    }

    override fun read(httpFile: Path, runId: String): HistoryRun? {
        if (!HistoryNames.isRunId(runId)) return null
        val fileDir = fileDir(httpFile)
        return loadRun(fileDir, runId)?.let { HistoryRun(runId, it.instant(), it.report()) }
    }

    override fun readResponse(httpFile: Path, runId: String, index: Int): StoredResponse? {
        if (!HistoryNames.isRunId(runId)) return null
        val fileDir = fileDir(httpFile)
        val run = loadRun(fileDir, runId) ?: return null
        return toStored(fileDir.resolve(runId), run, index)
    }

    override fun latestResponse(httpFile: Path, requestName: String): StoredResponse? {
        if (requestName.isBlank()) return null
        val fileDir = fileDir(httpFile)
        for (id in runIds(fileDir)) {
            val run = loadRun(fileDir, id) ?: continue
            val entries = run.report().entries
            for (i in entries.indices.reversed()) {
                if (entries[i].name == requestName) toStored(fileDir.resolve(id), run, i)?.let { return it }
            }
        }
        return null
    }

    // ---- reading

    private fun toStored(runDir: Path, run: LoadedRun, index: Int): StoredResponse? {
        val resp = run.dto.responses.firstOrNull { it.index == index } ?: return null
        val entry = run.report().entries.getOrNull(index) ?: return null
        val bodyFile = runDir.resolve("$index.body")
        val body = try {
            if (!Files.isRegularFile(bodyFile, LinkOption.NOFOLLOW_LINKS)) throw IOException("missing body file")
            readBounded(bodyFile)
        } catch (e: IOException) {
            warn("skipping response $index of run ${run.dto.id}: ${e.message}")
            return null
        }
        return StoredResponse(
            run.dto.id, run.instant(), index, entry.name, entry.method, entry.url,
            HttpResponse(resp.status, resp.headers.map { Header(it.name, it.value) }, body, resp.durationMillis),
            resp.bodyTruncated, resp.bodySize,
        )
    }

    private fun readBounded(file: Path): ByteArray = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(maxBodyBytes) }

    private class LoadedRun(val dto: RunDto, private val parsed: RunReport) {
        fun instant(): Instant = Instant.parse(dto.timestamp)
        fun report(): RunReport = parsed
    }

    /** Parses `run.json` of [id]; null (with a warning) when it is missing, too large, corrupt or from a newer version. */
    private fun loadRun(fileDir: Path, id: String): LoadedRun? {
        val file = fileDir.resolve(id).resolve(RUN_FILE)
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
            if (Files.size(file) > MAX_INDEX_BYTES) throw IOException("index larger than $MAX_INDEX_BYTES bytes")
            val dto = JSON.decodeFromString<RunDto>(Files.readString(file))
            if (dto.version > FORMAT_VERSION) throw IOException("written by a newer version (${dto.version})")
            if (dto.id != id) throw IOException("id does not match the folder")
            Instant.parse(dto.timestamp)
            val report = JsonRunReport.parse(dto.report.toString())
            return LoadedRun(dto, report)
        } catch (e: Exception) {
            warn("skipping unreadable history entry $file: ${e.message}")
            return null
        }
    }

    /** Names of the run folders of [fileDir] that look like run ids, newest first. Foreign entries are ignored. */
    private fun runIds(fileDir: Path): List<String> {
        if (!Files.isDirectory(fileDir, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return try {
            Files.list(fileDir).use { s ->
                s.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                    .map { it.fileName.toString() }
                    .filter { HistoryNames.isRunId(it) }
                    .sorted(Comparator.reverseOrder())
                    .toList()
            }
        } catch (e: IOException) {
            warn("cannot list $fileDir: ${e.message}")
            emptyList()
        }
    }

    // ---- writing

    /** The folder of [httpFile]'s runs, verified to stay inside the history folder. */
    private fun fileDir(httpFile: Path): Path {
        val dir = historyDirFor(httpFile.toAbsolutePath()).toAbsolutePath().normalize()
        require(dir.fileName != null) { "the history folder must not be a file system root" }
        return dir
    }

    private fun createRunDir(fileDir: Path, now: Instant): Pair<String, Path> {
        repeat(20) {
            val id = HistoryNames.newRunId(now, random)
            val dir = fileDir.resolve(id)
            try {
                Files.createDirectory(dir, *dirAttrs())
                return id to dir
            } catch (_: FileAlreadyExistsException) {
                // unique ids: a collision is astronomically unlikely, just draw another one
            }
        }
        throw IOException("cannot create a unique run folder in $fileDir")
    }

    private fun prune(fileDir: Path) {
        val ids = runIds(fileDir)
        // an interrupted writer leaves a run folder without run.json; it is cleaned up once it is clearly stale
        val cutoff = clock.instant().minusSeconds(STALE_SECONDS)
        for (id in ids) {
            val dir = fileDir.resolve(id)
            if (!Files.exists(dir.resolve(RUN_FILE), LinkOption.NOFOLLOW_LINKS) && runOf(id)?.isBefore(cutoff) == true) deleteTree(dir)
        }
        val complete = ids.filter { Files.exists(fileDir.resolve(it).resolve(RUN_FILE), LinkOption.NOFOLLOW_LINKS) }
        complete.drop(maxRuns).forEach { deleteTree(fileDir.resolve(it)) }
    }

    private fun runOf(id: String): Instant? = HistoryNames.timeOf(id)

    private fun deleteTree(dir: Path) {
        try {
            if (Files.isSymbolicLink(dir) || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return
            Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        } catch (e: IOException) {
            warn("cannot delete old history entry $dir: ${e.message}")
        }
    }

    private fun warn(message: String) = onWarning(message)

    @Serializable private data class HeaderDto(val name: String, val value: String)

    @Serializable private data class ResponseDto(
        val index: Int,
        val status: Int,
        val headers: List<HeaderDto>,
        val durationMillis: Long,
        val bodySize: Long,
        val bodyTruncated: Boolean,
    )

    @Serializable private data class RunDto(
        val version: Int,
        val id: String,
        val timestamp: String,
        val report: JsonElement,
        val responses: List<ResponseDto>,
    )

    companion object {
        const val DEFAULT_MAX_RUNS = 50
        const val DEFAULT_MAX_BODY_BYTES = 5 * 1024 * 1024
        private const val FORMAT_VERSION = 1
        private const val RUN_FILE = "run.json"
        private const val MAX_INDEX_BYTES = 64L * 1024 * 1024
        private const val STALE_SECONDS = 3600L
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Default location: `.http-history/<file name>` in the folder of the `.http` file. */
        val nextToHttpFile: (Path) -> Path = { file ->
            val abs = file.toAbsolutePath()
            (abs.parent ?: abs.root).resolve(".http-history").resolve(HistoryNames.safeComponent(abs.fileName.toString()))
        }

        /**
         * All files share [root] (for tests and for tools that keep history elsewhere): the folder of a file is
         * `<name>-<hash of its absolute path>`, so equally named files in different folders stay apart.
         */
        fun underRoot(root: Path): (Path) -> Path = { file ->
            val abs = file.toAbsolutePath().normalize()
            val hash = MessageDigest.getInstance("SHA-256").digest(abs.toString().toByteArray()).take(4).joinToString("") { "%02x".format(it) }
            root.resolve("${HistoryNames.safeComponent(abs.fileName.toString())}-$hash")
        }
    }
}

/** Run ids and file-system-safe names for the history folder. */
internal object HistoryNames {
    private val RUN_ID = Regex("^\\d{8}T\\d{9}Z-[0-9a-f]{8}$")
    private val FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)
    private val PARSE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'")
    private val RESERVED = Regex("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$", RegexOption.IGNORE_CASE)
    const val MAX_COMPONENT = 80

    fun newRunId(now: Instant, random: SecureRandom): String =
        FORMAT.format(now) + "-" + ByteArray(4).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun isRunId(s: String) = RUN_ID.matches(s)

    fun timeOf(id: String): Instant? = try {
        java.time.LocalDateTime.parse(id.substringBefore('-'), PARSE).toInstant(ZoneOffset.UTC)
    } catch (_: Exception) {
        null
    }

    /**
     * A single path component made from [name]: separators, Windows-illegal characters and control characters become
     * `_`, trailing dots and spaces are removed, `.`/`..`/empty and Windows reserved device names (also with an
     * extension, e.g. `nul.txt`) are prefixed or replaced, and the result is cut to [MAX_COMPONENT] characters.
     */
    fun safeComponent(name: String): String {
        var s = name.map { if (it in "<>:\"/\\|?*" || it.code < 32 || it.code == 127) '_' else it }.joinToString("")
        s = s.trimEnd('.', ' ')
        if (s.length > MAX_COMPONENT) s = s.take(MAX_COMPONENT).trimEnd('.', ' ')
        if (s.isEmpty() || s.all { it == '.' }) return "_"
        if (RESERVED.matches(s.substringBefore('.').trimEnd(' '))) s = "_$s"
        return s
    }
}

// Owner-only directories where the file system has POSIX permissions; elsewhere (Windows) the default ACLs apply.
// The attribute itself can be built anywhere, it is createDirectory that rejects it, so test the file system, not the call.
private fun dirAttrs(): Array<java.nio.file.attribute.FileAttribute<*>> =
    if (POSIX) arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))) else emptyArray()

private val POSIX = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

/** Creates [dir] and missing parents; folders it creates are owner-only on POSIX. */
private fun createPrivateDirectories(dir: Path) {
    if (Files.isDirectory(dir)) return
    dir.parent?.let { if (!Files.isDirectory(it)) createPrivateDirectories(it) }
    try {
        Files.createDirectory(dir, *dirAttrs())
    } catch (_: FileAlreadyExistsException) {
        if (!Files.isDirectory(dir)) throw IOException("$dir exists and is not a folder")
    }
}

/**
 * Writes [bytes] to a temp file created owner-only in the target's folder (no window where others can read it), then
 * moves it into place atomically where supported. The temp file is removed on failure.
 */
internal fun writeAtomically(target: Path, bytes: ByteArray) {
    val attrs = if (POSIX) arrayOf(PosixFilePermissions.asFileAttribute(OWNER_ONLY)) else emptyArray()
    val tmp = Files.createTempFile(target.parent, ".httpc-history-", ".tmp", *attrs)
    try {
        Files.write(tmp, bytes)
        moveIntoPlace(tmp, target)
    } finally {
        Files.deleteIfExists(tmp)
    }
}

private val OWNER_ONLY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
