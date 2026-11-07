package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.port.out.OutputWriter
import com.dan323.httpclient.core.port.out.ReportWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Writes a report to a temp file in the target folder, then moves it over the target (atomically when the file
 * system supports it), so readers never see a half-written report and no temp file is left behind.
 *
 * Permissions are set deliberately (POSIX only; elsewhere the platform default applies): a new report is owner-only
 * (`rw-------`) because reports may contain URLs and error texts with secrets; replacing an existing report keeps
 * that file's permissions, so a CI job that shares reports with a group keeps working.
 *
 * Also an [OutputWriter]: the same atomic, permission-preserving write for raw bytes (byte-exact, no charset involved).
 */
class AtomicReportWriter internal constructor(
    private val writeFile: (Path, String) -> Unit,
    private val move: (Path, Path, Array<StandardCopyOption>) -> Unit,
) : ReportWriter, OutputWriter {
    constructor() : this({ p, c -> Files.writeString(p, c) }, { a, b, o -> Files.move(a, b, *o) })

    override fun write(path: Path, content: String) = writeAtomically(path) { writeFile(it, content) }

    override fun write(path: Path, bytes: ByteArray) = writeAtomically(path) { Files.write(it, bytes) }

    private fun writeAtomically(path: Path, fill: (Path) -> Unit) {
        val target = path.toAbsolutePath()
        target.parent?.let { Files.createDirectories(it) }
        val tmp = Files.createTempFile(target.parent, ".httpc-report-", ".tmp")
        try {
            // content first (the temp file is owner-only), then the final permissions: a read-only existing report
            // must not make the temp file unwritable
            fill(tmp)
            applyPermissions(tmp, target)
            moveIntoPlace(tmp, target, move)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun applyPermissions(tmp: Path, target: Path) {
        val perms: Set<PosixFilePermission> = try {
            if (Files.isRegularFile(target)) Files.getPosixFilePermissions(target) else OWNER_ONLY
        } catch (_: UnsupportedOperationException) {
            return
        }
        try {
            Files.setPosixFilePermissions(tmp, perms)
        } catch (_: UnsupportedOperationException) {
            // not a POSIX file system
        }
    }

    private companion object {
        val OWNER_ONLY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
    }
}

/**
 * Moves [tmp] over [target], atomically when the file system supports it (some, e.g. certain Windows and network
 * file systems, do not: then a plain replacing move is used). [move] is injectable so the fallback is testable.
 */
internal fun moveIntoPlace(tmp: Path, target: Path, move: (Path, Path, Array<StandardCopyOption>) -> Unit = { a, b, o -> Files.move(a, b, *o) }) {
    try {
        move(tmp, target, arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING))
    } catch (_: AtomicMoveNotSupportedException) {
        move(tmp, target, arrayOf(StandardCopyOption.REPLACE_EXISTING))
    }
}
