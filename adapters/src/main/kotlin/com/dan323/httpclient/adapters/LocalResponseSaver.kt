package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.port.out.ResponseSaver
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Writes `>> path` / `>>! path` responses, confined exactly like [LocalFileReader]: the path is relative to the folder
 * of [httpFile] and must end up (after `..` normalisation and symlink resolution) inside that folder or one of the
 * [policy]'s extra roots. Absolute paths (`/x`, `C:\x`, `\x`), a final symlink, a directory, the `.http` file itself
 * and a read-only file are refused; backslashes count as separators on every platform. A path with leading or trailing
 * whitespace is refused as given (it is validated exactly as written, never trimmed into a different path). Missing
 * parent folders are created, only inside the allowed roots.
 *
 * The body is written byte for byte to an owner-only temp file with a random name in the final, already verified
 * folder, and published from there, so readers never see a half-written file and no step crosses folders. Without
 * `overwrite`, an existing file is never touched: JetBrains-style, a numeric suffix is added before the extension
 * (`out.json`, `out-1.json`, ...); the finished temp file is hard-linked to the first free name (a link fails if the name
 * exists, so concurrent saves end up in different files, and no empty or partial file is ever visible at the final
 * name), then the temp name is removed. Where hard links are unsupported, a plain move without replace is used (exclusive
 * on Windows; on POSIX the JDK checks and then renames, a tiny window in which a concurrent writer of the very same name
 * could be replaced). `>>!` is one atomic rename inside that folder. New files are owner-only (`rw-------`) on POSIX; an
 * overwritten file keeps its permissions. Errors name the path and never contain the body.
 *
 * Names that cannot exist on Windows (`CON`, `NUL`, `COM1`, `LPT1`, ... also with an extension, a trailing dot or space,
 * and any of `<>:"|?*` or a control character, which also rules out `file:stream`) are refused on every platform, so a
 * project shared between systems never saves a file another system cannot hold.
 *
 * Confinement against a folder swapped for a symlink after validation: the verified real path of the final folder is
 * remembered; the temp file is created through the folder path and, immediately before publishing, the folder's real
 * path must still equal the remembered one and the temp file must really be inside it. Publishing then names the source
 * by its random temp name, which exists only in the genuine folder: if the folder path was swapped for a link to
 * elsewhere meanwhile, the rename/link finds no source and fails, so nothing outside is touched or replaced, also for
 * `>>!`. A rename over a symlink replaces the link, never its target.
 *
 * Residual risk (same as [LocalFileReader]; the JDK has no `openat`/directory handles): a local attacker who can write
 * in the allowed folders, can observe the random temp name, and swaps the folder in the few microseconds between the last
 * re-verification and the syscall, while also placing a file of that name at the new location, can still make one save
 * land outside. After publishing the real location is checked once more; a file created by `>>` that is found outside is
 * removed, but one replaced by `>>!` cannot be restored and the error says so.
 */
class LocalResponseSaver(
    private val httpFile: Path,
    private val policy: FileAccessPolicy = FileAccessPolicy(),
    private val maxSuffix: Int = MAX_SUFFIX,
    private val move: (Path, Path) -> Unit = { a, b -> moveIntoPlace(a, b) },
    private val link: (Path, Path) -> Unit = { l, existing -> Files.createLink(l, existing) },
    /** Test seam: runs after the temp file is written, before the final re-verification. */
    private val afterWrite: () -> Unit = {},
) : ResponseSaver {
    private val baseDir: Path = (httpFile.toAbsolutePath().parent ?: httpFile.toAbsolutePath().root).normalize()

    override fun save(path: String, body: ByteArray, overwrite: Boolean): String {
        if (path.isBlank()) throw IOException("empty file path")
        if (path != path.trim()) throw IOException("invalid file path '$path': leading or trailing whitespace")
        val rel = path.replace('\\', '/')
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
        fun inside(p: Path) = roots.any { realPathOf(p).startsWith(it) }
        fun denied(): Nothing = throw FileAccessDeniedException(
            "File '$path' is outside the allowed folders (the .http file's folder${if (policy.extraRoots.isEmpty()) "" else " and the allowed roots"}); ${policy.allowHint}",
        )
        if (!inside(target)) denied()
        val dir = target.parent ?: throw IOException("'$path' is not a file")
        if (Files.isSymbolicLink(target)) throw FileAccessDeniedException("File '$path' is a symbolic link; refusing to write through it")
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw IOException("'$path' is a folder, not a file")
        if (sameFile(target, httpFile)) throw IOException("'$path' is the .http file itself; refusing to overwrite it")
        if (overwrite && Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isWritable(target)) {
            throw IOException("'$path' is read-only")
        }
        Files.createDirectories(dir)
        if (!inside(dir)) denied() // a folder swapped for a symlink in the meantime
        val verifiedDir = realPathOf(dir)

        val tmp = Files.createTempFile(dir, ".httpc-save-", ".tmp", *ownerOnlyAttrs())
        val written = try {
            Files.write(tmp, body)
            afterWrite()
            // Immediately before publishing: the folder is still the verified one and the temp file really is in it.
            if (realPathOf(dir) != verifiedDir || tmp.toRealPath().parent != verifiedDir) {
                throw FileAccessDeniedException("File '$path' changed while being saved; nothing was written")
            }
            if (overwrite) replace(tmp, target) else createNew(dir, target, tmp)
        } finally {
            Files.deleteIfExists(tmp)
        }
        // Post-check: where the file really is now.
        if (!inside(written)) {
            if (overwrite) throw FileAccessDeniedException("File '$path' changed while being saved and ended up outside the allowed folders; it could not be restored")
            Files.deleteIfExists(written)
            throw FileAccessDeniedException("File '$path' changed while being saved; refusing to write it (the file was removed)")
        }
        // an allowed root outside the .http folder, reached with `..`, yields `../x`
        return baseDir.relativize(written).joinToString("/")
    }

    /** `>>!`: one rename of the finished temp file over the target, inside the verified folder. An existing file keeps its permissions. */
    private fun replace(tmp: Path, target: Path): Path {
        if (POSIX && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            runCatching { Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS)) }
        }
        move(tmp, target)
        return target
    }

    /** `>>`: publishes the finished [tmp] at the first free name (`name`, `name-1`, ...) without ever clobbering or exposing a partial file. */
    private fun createNew(dir: Path, wanted: Path, tmp: Path): Path {
        val name = wanted.fileName.toString()
        val dot = name.lastIndexOf('.')
        val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        for (n in 0..maxSuffix) {
            val candidate = if (n == 0) wanted else dir.resolve("$stem-$n$ext")
            try {
                try {
                    link(candidate, tmp)
                } catch (e: FileAlreadyExistsException) {
                    throw e
                } catch (_: UnsupportedOperationException) {
                    Files.move(tmp, candidate) // no hard links here: a move without replace still refuses an existing name
                } catch (_: java.nio.file.FileSystemException) {
                    Files.move(tmp, candidate)
                }
            } catch (_: FileAlreadyExistsException) {
                continue
            }
            return candidate
        }
        throw IOException("no free file name for '${baseDir.relativize(wanted)}' (tried $maxSuffix suffixes)")
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
