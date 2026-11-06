package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.port.out.ResponseSaver
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Writes `>> path` / `>>! path` responses, confined exactly like [LocalFileReader]: the path is relative to the folder
 * of [httpFile] and must end up (after `..` normalisation and symlink resolution) inside that folder or one of the
 * [policy]'s extra roots. Absolute paths (`/x`, `C:\x`, `\x`), a final symlink, a directory, the `.http` file itself
 * and a read-only file are refused; backslashes count as separators on every platform. Missing parent folders are
 * created, only inside the allowed roots.
 *
 * The body is written byte for byte to a temp file in the target folder, then moved into place, so readers never see
 * a half-written file. Without `overwrite`, an existing file is never touched: JetBrains-style, a numeric suffix is
 * added before the extension (`out.json`, `out-1.json`, `out-2.json`, ...); the name is reserved with an exclusive
 * create, so concurrent saves to the same name end up in different files. New files are owner-only (`rw-------`) on
 * POSIX; an overwritten file keeps its permissions. Errors name the path and never contain the body.
 *
 * Names that cannot exist on Windows (`CON`, `NUL`, `COM1`, `LPT1`, ... also with an extension, a trailing dot or space,
 * and any of `<>:"|?*` or a control character, which also rules out `file:stream`) are refused on every platform, so a
 * project shared between systems never saves a file another system cannot hold.
 *
 * Residual risk (the same as [LocalFileReader]): the confinement is checked before the write and the final real path is
 * re-verified afterwards (the file is deleted if it ended up outside), but an attacker who can write inside the allowed
 * folders can still race that window, e.g. by swapping a parent folder for a symlink between the check and the write;
 * closing it fully would need openat-style APIs the JDK lacks.
 */
class LocalResponseSaver(
    private val httpFile: Path,
    private val policy: FileAccessPolicy = FileAccessPolicy(),
    private val maxSuffix: Int = MAX_SUFFIX,
    private val move: (Path, Path) -> Unit = { a, b -> moveIntoPlace(a, b) },
) : ResponseSaver {
    private val baseDir: Path = (httpFile.toAbsolutePath().parent ?: httpFile.toAbsolutePath().root).normalize()

    override fun save(path: String, body: ByteArray, overwrite: Boolean): String {
        val rel = path.trim().replace('\\', '/')
        if (rel.isEmpty()) throw IOException("empty file path")
        if ('\u0000' in rel) throw IOException("invalid file path '$path'")
        if (rel.startsWith("/") || DRIVE.containsMatchIn(rel)) {
            throw FileAccessDeniedException("File '$path' must be relative to the .http file, not an absolute path")
        }
        if (rel.endsWith("/")) throw IOException("'$path' names a folder, not a file")
        rel.split('/').firstNotNullOfOrNull { windowsProblem(it) }?.let { throw IOException("invalid file path '$path': $it") }
        val target = try { baseDir.resolve(rel).normalize() } catch (e: java.nio.file.InvalidPathException) {
            throw IOException("invalid file path '$path': ${e.reason}")
        }
        val roots = (listOf(baseDir) + policy.extraRoots).map { realPathOf(it.toAbsolutePath().normalize()) }
        fun checkInside(p: Path) {
            if (roots.none { realPathOf(p).startsWith(it) }) {
                throw FileAccessDeniedException(
                    "File '$path' is outside the allowed folders (the .http file's folder${if (policy.extraRoots.isEmpty()) "" else " and the allowed roots"}); ${policy.allowHint}",
                )
            }
        }
        checkInside(target)
        val dir = target.parent ?: throw IOException("'$path' is not a file")
        if (Files.isSymbolicLink(target)) throw FileAccessDeniedException("File '$path' is a symbolic link; refusing to write through it")
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw IOException("'$path' is a folder, not a file")
        if (sameFile(target, httpFile)) throw IOException("'$path' is the .http file itself; refusing to overwrite it")
        if (overwrite && Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isWritable(target)) {
            throw IOException("'$path' is read-only")
        }
        Files.createDirectories(dir)
        checkInside(dir) // a folder swapped for a symlink in the meantime

        val written = if (overwrite) replace(dir, target, body) else createNew(dir, target, body)
        // Post-check: where the file really is now (a parent swapped for a symlink after the checks above).
        if (roots.none { realPathOf(written).startsWith(it) }) {
            Files.deleteIfExists(written)
            throw FileAccessDeniedException("File '$path' changed while being saved; refusing to write it (the file was removed)")
        }
        // an allowed root outside the .http folder, reached with `..`, yields `../x`
        return baseDir.relativize(written).joinToString("/")
    }

    /** `>>!`: temp file, then replace the target. An existing file keeps its permissions. */
    private fun replace(dir: Path, target: Path, body: ByteArray): Path {
        val tmp = Files.createTempFile(dir, ".httpc-save-", ".tmp", *ownerOnlyAttrs())
        try {
            Files.write(tmp, body)
            if (POSIX && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                runCatching { Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS)) }
            }
            move(tmp, target)
        } finally {
            Files.deleteIfExists(tmp)
        }
        return target
    }

    /** `>>`: reserves the first free name (`name`, `name-1`, ...) exclusively, then moves the finished temp file over the reservation. */
    private fun createNew(dir: Path, wanted: Path, body: ByteArray): Path {
        val tmp = Files.createTempFile(dir, ".httpc-save-", ".tmp", *ownerOnlyAttrs())
        try {
            Files.write(tmp, body)
            val name = wanted.fileName.toString()
            val dot = name.lastIndexOf('.')
            val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
            for (n in 0..maxSuffix) {
                val candidate = if (n == 0) wanted else dir.resolve("$stem-$n$ext")
                try {
                    Files.createFile(candidate, *ownerOnlyAttrs())
                } catch (_: FileAlreadyExistsException) {
                    continue
                }
                try {
                    move(tmp, candidate)
                } catch (e: IOException) {
                    Files.deleteIfExists(candidate)
                    throw e
                }
                return candidate
            }
            throw IOException("no free file name for '${baseDir.relativize(wanted)}' (tried $maxSuffix suffixes)")
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun sameFile(a: Path, b: Path): Boolean =
        try { Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b) } catch (_: IOException) { false }

    private fun ownerOnlyAttrs(): Array<java.nio.file.attribute.FileAttribute<*>> =
        if (POSIX) arrayOf(PosixFilePermissions.asFileAttribute(OWNER_ONLY)) else emptyArray()

    private companion object {
        val RESERVED = Regex("^(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])$", RegexOption.IGNORE_CASE)

        /** Why [segment] cannot be a file name on Windows, or null. `.` and `..` are navigation, not names. */
        fun windowsProblem(segment: String): String? = when {
            segment == "." || segment == ".." || segment.isEmpty() -> null
            segment.any { it in "<>:\"|?*" || it.code < 32 } -> "'$segment' contains a character that is not allowed in file names on Windows (one of < > : \" | ? * or a control character)"
            segment.endsWith(".") || segment.endsWith(" ") -> "'$segment' ends with a dot or a space, which Windows does not allow"
            RESERVED.matches(segment.substringBefore('.').trimEnd(' ')) -> "'$segment' is a reserved device name on Windows"
            else -> null
        }
        val DRIVE = Regex("""^[A-Za-z]:""")
        const val MAX_SUFFIX = 9999
        val POSIX = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
        val OWNER_ONLY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
    }
}
