package kotlin_script

import com.github.ajalt.mordant.animation.progress.ProgressTask
import java.net.URL
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.io.path.*

internal class Resolver(
    private val mavenRepoUrl: String,
    private val mavenRepoCache: Path?,
    private val localRepo: Path,
    private val p: Progress
) {
    private val totalProgress = 10_000L
    private val concurrency = 4

    fun resolveLibs(
        compilerClassPath: List<Dependency>,
        dependencies: List<Dependency>,
        compilerDependencies: MutableMap<Dependency, Path>,
        resolvedDependencies: MutableMap<Dependency, Path>
    ) {
        val lock = ReentrantLock()
        val cond = lock.newCondition()
        val resolved = mutableMapOf<Dependency, Path>()
        val toFetch = mutableSetOf<Dependency>()
        compilerDependencies.putAll(
            verifyLocalLibs(compilerClassPath, resolved, toFetch)
        )
        resolvedDependencies.putAll(
            verifyLocalLibs(dependencies, resolved, toFetch)
        )
        val tasks = toFetch.map{ dep ->
            FetchTask(this, dep, lock, cond)
        }
        if (tasks.isEmpty()) {
            return
        }
        p.withProgress("fetching dependencies", total = totalProgress) { pt ->
            val executor = FetchExecutor(
                tasks = tasks,
                lock = lock,
                cond = cond,
                totalProgress = totalProgress,
                pt = pt
            )
            val threads = Array(concurrency) { i ->
                thread(name = "Fetch$i") {
                    while (true) {
                        val myTask = lock.withLock {
                            var nextTask: FetchTask? = null
                            if (!executor.haveFailed) {
                                for (task in tasks) {
                                    if (task.started) {
                                        continue
                                    }
                                    nextTask = task
                                    if (task.dep.size == null) {
                                        break
                                    }
                                }
                                nextTask?.let { it.started = true }
                            }
                            nextTask
                        } ?: break
                        myTask.run()
                    }
                }
            }
            try {
                executor.run()
            } finally {
                for (thread in threads) {
                    thread.join()
                }
                val ex = tasks.firstNotNullOfOrNull { task ->
                    task.failed
                }
                if (ex != null) {
                    throw ex
                }
            }
        }
    }

    private fun verifyLocalLibs(
        dependencies: List<Dependency>,
        resolved: MutableMap<Dependency, Path>,
        toFetch: MutableSet<Dependency>
    ) = buildMap {
        for (dep in dependencies) {
            val subPath = dep.subPath
            val f = localRepo / subPath
            if (!f.parent.exists()) {
                f.parent.createDirectories()
            }
            put(dep, f)
            if (f.exists() && (dep.size == null || dep.size == f.fileSize())) {
                resolved[dep] = f
            } else if (copyFromRepoCache(dep, f)) {
                p.trace(
                    "cp ${mavenRepoCache}/$subPath $f"
                )
            } else {
                toFetch += dep
            }
        }
    }

    private fun copyFromRepoCache(dep: Dependency, dst: Path): Boolean {
        if (mavenRepoCache == null) {
            return false
        }
        val source = mavenRepoCache / dep.subPath
        if (!source.isReadable()) {
            return false
        }
        val size = source.fileSize()
        if (dep.size != null && dep.size != size) {
            return false
        }
        try {
            val md = if (dep.sha256 != null) {
                MessageDigest.getInstance("SHA-256")
            } else {
                null
            }
            val tmp = createTempFile(dst.parent, "${dst.name}~", "")
            try {
                tmp.outputStream().use { out ->
                    source.inputStream().use { `in` ->
                        val buffer = ByteArray(1024 * 4)
                        while (true) {
                            val n = `in`.read(buffer)
                            if (n < 0) break
                            md?.update(buffer, 0, n)
                            out.write(buffer, 0, n)
                        }
                    }
                }
                if (md != null) {
                    val sha256 = md.digest().joinToString("") {
                        "%02x".format(it)
                    }
                    if (dep.sha256 != sha256) {
                        return false
                    }
                }
                tmp.moveTo(
                    dst,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } finally {
                tmp.deleteIfExists()
            }
        } catch (_: Throwable) {
            return false
        }
        return true
    }

    private class FetchExecutor(
        val tasks: List<FetchTask>,
        val lock: ReentrantLock,
        val cond: Condition,
        val totalProgress: Long,
        val pt: ProgressTask<Unit>?,
        var haveFailed: Boolean = false
    ) {
        private val waitForSizesMs = 800L
        private val guessedSize = 2L * 1024L * 1024L

        fun run() {
            val started = System.currentTimeMillis()
            var remainingProgress = totalProgress
            while (true) {
                val haveTotalSize = lock.withLock {
                    cond.await(waitForSizesMs, TimeUnit.MILLISECONDS)
                    tasks.all { t -> t.detectedSize != null }
                }
                val now = System.currentTimeMillis()
                if (haveTotalSize || (now - started >= waitForSizesMs)) {
                    break
                }
            }
            while (true) {
                var haveInProgress = false
                var completedProgress = 0L
                lock.withLock {
                    cond.await(1, TimeUnit.SECONDS)
                    var totalEstimatedSize = 0L
                    val sizes = LongArray(tasks.size) { i ->
                        val task = tasks[i]
                        if (task.failed != null) {
                            haveFailed = true
                        }
                        if (!task.completed) {
                            haveInProgress = true
                        }
                        if (task.allocatedProgress != null) {
                            return@LongArray -1
                        }
                        val size = when (val size = task.detectedSize) {
                            null -> when {
                                task.completed -> {
                                    // completed without detected size
                                    if (task.transferred > 0) {
                                        task.transferred
                                    } else {
                                        guessedSize
                                    }
                                }

                                task.transferred > 0 -> {
                                    // in progress without detected size
                                    guessedSize
                                }

                                else -> {
                                    // pending
                                    null
                                }
                            }

                            else -> size
                        }
                        if (size != null) {
                            totalEstimatedSize += size
                            size
                        } else {
                            totalEstimatedSize += guessedSize
                            -1L
                        }
                    }
                    val sizeToProgress = remainingProgress.toDouble() /
                            totalEstimatedSize.toDouble()
                    for (i in tasks.indices) {
                        val task = tasks[i]
                        val size = sizes[i]
                        if (size >= 0) {
                            val allocate = (size.toDouble() * sizeToProgress)
                                .toLong()
                            remainingProgress -= allocate
                            task.allocatedProgress = allocate
                        }
                        task.allocatedProgress?.let {
                            completedProgress += task.progress()
                        }
                    }
                }
                if (haveFailed) {
                    // only finish downloads that have already been started
                    haveInProgress = false
                    for (task in tasks) {
                        if (task.started && !task.completed) {
                            haveInProgress = true
                        }
                    }
                }
                if (!haveInProgress) {
                    pt?.update { completed = totalProgress }
                    break
                }
                pt?.update {
                    completed = minOf(totalProgress - 1, completedProgress)
                }
            }
        }
    }

    private class FetchTask(
        val resolver: Resolver,
        val dep: Dependency,
        val lock: ReentrantLock,
        val cond: Condition
    ) {
        var detectedSize: Long? = null
        var allocatedProgress: Long? = null
        var transferred: Long = 0
        var started: Boolean = false
        var completed: Boolean = false
        var failed: Throwable? = null

        fun progress(): Long {
            if (!started) {
                return 0L
            }
            val allocatedProgress = this.allocatedProgress ?: return 0L
            if (completed) {
                return allocatedProgress
            }
            val totalSize = detectedSize ?: dep.size ?: return 0L
            // transferred : totalSize == x : allocatedProgress
            val result = allocatedProgress.toDouble() * minOf(
                1.0, transferred.toDouble() / totalSize.toDouble()
            )
            return result.toLong()
        }

        fun run() {
            val subPath = dep.subPath
            val f = resolver.localRepo / subPath
            val tmp = createTempFile(f.parent, "${f.name}~", "")
            try {
                fetchFromRepo(dep, tmp)
                tmp.moveTo(
                    f,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (ex: Throwable) {
                failed = ex
            } finally {
                lock.withLock {
                    completed = true
                    cond.signal()
                }
                tmp.deleteIfExists()
            }
        }

        private fun fetchFromRepo(dep: Dependency, tmp: Path) {
            val url = "${resolver.mavenRepoUrl}/${dep.subPath}"
            resolver.p.trace("fetch $url")
            val md = if (dep.sha256 != null) {
                MessageDigest.getInstance("SHA-256")
            } else {
                null
            }
            tmp.outputStream().use { out ->
                val cn = URL(url).openConnection()
                val contentLength = cn.contentLengthLong
                if (contentLength >= 0) {
                    if (dep.size != null && dep.size != contentLength) {
                        error(
                            "error fetching $dep: unexpected Content-Length: " +
                                    "$contentLength, expected ${dep.size}"
                        )
                    }
                    lock.withLock {
                        detectedSize = contentLength
                        cond.signal()
                    }
                }
                cn.inputStream.use { `in` ->
                    val buffer = ByteArray(1024 * 4)
                    while (true) {
                        val n = `in`.read(buffer)
                        if (n < 0) break
                        md?.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        lock.withLock {
                            transferred += n
                            cond.signal()
                        }
                    }
                }
                if (dep.size != null && dep.size != transferred) {
                    error(
                        "error fetching $dep: received $transferred Byte(s), " +
                                "expected ${dep.size} Byte(s)"
                    )
                }
                if (contentLength >= 0 && contentLength != transferred) {
                    error(
                        "error fetching $dep: received $transferred Byte(s), " +
                                "expected $contentLength Byte(s)"
                    )
                }
                if (md != null) {
                    val sha256 = md.digest().joinToString("") {
                        "%02x".format(it)
                    }
                    if (dep.sha256 != sha256) {
                        error("unexpected sha256=$sha256 for $dep")
                    }
                }
            }
        }
    }
}
