package net.dean.easyarchive.library

import com.github.junrar.Archive
import net.dean.easyarchive.library.InflationUtils.archiveStreamFactory
import net.dean.easyarchive.library.InflationUtils.mkdirs
import net.dean.easyarchive.library.InflationUtils.newInputStream
import net.dean.easyarchive.library.InflationUtils.newOutputStream
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.utils.IOUtils
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.*
import java.util.zip.ZipFile

/**
 * Unarchivers are Inflaters that unarchive multiple entries from a single file. 7z, ar, arj, cpio, dump, tar, and zip
 * are examples of archiver formats
 */
interface Unarchiver : Inflater

/**
 * Fills in some boilerplate code that all Unarchivers use
 *
 * @property ext The file extension this Unarchiver can operate on
 */
abstract class AbstractUnarchiver(val ext: String) : Unarchiver {
    override var eventHandler: ArchiveEventHandler = object: ArchiveEventHandler {
        override fun handle(e: ArchiveEvent) {}
    }
    override fun log(e: ArchiveEvent) {
        eventHandler.handle(e)
    }
    override fun canOperateOn(f: File): Boolean = f.name.endsWith('.' + ext, ignoreCase = true)
    override final fun inflate(f: File, dest: File): List<File> {
        val count = count(f)
        log(ArchiveEvent.start(dest, count))
        val files = doInflate(f, dest, count)
        log(ArchiveEvent.done(f))
        return files
    }

    override final fun count(f: File): Int {
        log(ArchiveEvent.count(f))
        return doCount(f)
    }
    @Throws(IOException::class)
    protected abstract fun doInflate(f: File, dest: File, total: Int): List<File>
    @Throws(IOException::class)
    protected abstract fun doCount(f: File): Int
}

/**
 * Uses generic `ArchiveInputStream`s to unarchive a file.
 */
abstract class GenericUnarchiver(ext: String) : AbstractUnarchiver(ext) {
    /** Gets an ArchiveInputStream for the given file using [ext] */
    protected fun getArchiveInputStream(f: File): ArchiveInputStream =
            archiveStreamFactory.createArchiveInputStream(ext, newInputStream(f))

    /**
     * Iterates through an archive, calling `func` on each entry. The `ArchiveInputStream` passed to [func] is closed
     * after all entries have been iterated through
     */
    protected fun iterEntries(f: File, func: (ArchiveEntry, ArchiveInputStream) -> Unit) {
        val input = getArchiveInputStream(f)
        var entry: ArchiveEntry?
        do {
            entry = input.nextEntry
            if (entry == null)
                break
            func(entry, input)
        } while (true)
        input.close()
    }

    override fun doInflate(f: File, dest: File, total: Int): List<File> {
        val files: MutableList<File> = ArrayList()

        iterEntries(f) { entry, input ->
            val outFile = File(dest, entry.name).canonicalFile
            mkdirs(if (entry.isDirectory) outFile else outFile.parentFile)
            if (!entry.isDirectory) {
                val out = newOutputStream(outFile)
                IOUtils.copy(input, out)
                files += outFile
                log(ArchiveEvent.inflate(outFile, files.size, total))
                out.close()
            }
        }

        return files
    }

    override fun doCount(f: File): Int {
        var count = 0
        iterEntries(f) { entry, input ->
            if (!entry.isDirectory)
                count++
        }
        return count
    }
}

/** Uses the `java.util.zip` to extract a given file */
open class ZipBasedUnarchiver protected constructor(ext: String) : AbstractUnarchiver(ext) {
    override fun doCount(f: File): Int {
        val zip = ZipFile(f)
        val entries = zip.entries()
        var count = 0
        while (entries.hasMoreElements())
            if (!entries.nextElement().isDirectory)
                count++
        zip.close()
        return count
    }

    override fun doInflate(f: File, dest: File, total: Int): List<File> {
        val zip = ZipFile(f)
        var out: OutputStream

        val files: MutableList<File> = ArrayList()
        for (it in zip.entries()) {
            val outFile = File(dest, it.name)
            mkdirs(if (it.isDirectory) outFile else outFile.parentFile)
            if (!outFile.isDirectory) {
                out = newOutputStream(outFile)
                IOUtils.copy(zip.getInputStream(it), out)
                files += outFile
                log(ArchiveEvent.inflate(outFile, files.size, total))
                out.close()
            }
        }
        zip.close()
        return files
    }
}

/** Unarchives zip files */
class ZipUnarchiver : ZipBasedUnarchiver("zip")
/** Unarchives jar files */
class JarUnarchiver : ZipBasedUnarchiver("jar")
/** Unarchives tar files */
class TarUnarchiver : GenericUnarchiver("tar")

/** Unarchives rar files */
class RarUnarchiver : AbstractUnarchiver("rar") {
    override fun doInflate(f: File, dest: File, total: Int): List<File> {
        val archive = Archive(f)
        val files: MutableList<File> = ArrayList()
        for (fh in archive.fileHeaders) {
            val outFile = File(dest, fh.fileNameString.replace('\\', '/'))
            mkdirs(if (fh.isDirectory) outFile else outFile.parentFile)
            if (!fh.isDirectory) {
                val out = newOutputStream(outFile)
                archive.extractFile(fh, out)
                out.close()
                files += outFile
                log(ArchiveEvent.inflate(outFile, files.size, total))
            }
        }

        return files
    }

    override fun doCount(f: File): Int = Archive(f).fileHeaders.filter { !it.isDirectory }.size
}
