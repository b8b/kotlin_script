#!/bin/sh

/*__kotlin_script_installer__/ 2>&-
# vim: syntax=kotlin
#    _         _   _ _                       _       _
#   | |       | | | (_)                     (_)     | |
#   | | _____ | |_| |_ _ __    ___  ___ _ __ _ _ __ | |_
#   | |/ / _ \| __| | | '_ \  / __|/ __| '__| | '_ \| __|
#   |   < (_) | |_| | | | | | \__ \ (__| |  | | |_) | |_
#   |_|\_\___/ \__|_|_|_| |_| |___/\___|_|  |_| .__/ \__|
#                         ______              | |
#                        |______|             |_|
v=1.3.72.0
artifact=org/cikit/kotlin_script/kotlin_script/"$v"/kotlin_script-"$v".sh
repo=${repo:-https://repo1.maven.org/maven2}
if ! [ -e "${local_repo:=$HOME/.m2/repository}"/"$artifact" ]; then
  fetch_s="$(command -v fetch) -aAqo" || fetch_s="$(command -v curl) -fSso"
  mkdir -p "$local_repo"/org/cikit/kotlin_script/kotlin_script/"$v"
  tmp_f="$(mktemp "$local_repo"/"$artifact"~XXXXXXXXXXXXXXXX)" || exit 1
  if ! ${fetch_cmd:="$fetch_s"} "$tmp_f" "$repo"/"$artifact"; then
    echo "error: failed to fetch kotlin_script" >&2
    rm -f "$tmp_f"; exit 1
  fi
  case "$(openssl dgst -sha256 -r < "$tmp_f")" in
  "175648b97df5b0410c177a379f58aca8f029b3da705ecfda87b542133ba0ac2d "*)
    mv -f "$tmp_f" "$local_repo"/"$artifact" ;;
  *)
    echo "error: failed to validate kotlin_script" >&2
    rm -f "$tmp_f"; exit 1 ;;
  esac
fi
. "$local_repo"/"$artifact"
exit 2
*/

import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class Task<T> private constructor(
        private val dependency: Dependency<T>,
        val dependencies: List<Task<*>>,
        val block: Dependency.ExecutionContext.() -> T
) {
    class Dependency<T> private constructor(dependencies: List<Task<*>>,
                                            block: ExecutionContext.() -> T) {

        private val task: Task<T> = Task(this, dependencies, block)

        class DefinitionContext private constructor() {
            companion object {
                fun <T> defineTask(block: DefinitionContext.() -> Task<T>): Task<T> {
                    val definitionContext = DefinitionContext()
                    return block(definitionContext)
                }
            }

            private val dependencies = mutableListOf<Task<*>>()

            fun <T> dependency(task: Task<T>) =
                    task.dependency.also { dependencies.add(task) }

            fun <T> executing(block: ExecutionContext.() -> T): Task<T> {
                return Dependency(dependencies, block).task
            }

            fun TODO(): Task<Unit> = executing {
                kotlin.TODO()
            }

            fun TODO(reason: String): Task<Unit> = executing {
                kotlin.TODO(reason)
            }
        }

        abstract class ExecutionContext {
            @Deprecated(
                    message = "dependencies must be declared in DefinitionContext",
                    replaceWith = ReplaceWith("DefinitionContext::dependency")
            )
            fun <T> dependency(task: Task<T>): Nothing =
                    error("cannot declare dependency in ExecutionContext")
            val <T> Dependency<T>.value: T
                get() = this@ExecutionContext[task]
            protected abstract operator fun <T> get(task: Task<T>): T
        }
    }
}

fun <T> task(block: Task.Dependency.DefinitionContext.() -> Task<T>): Task<T> {
    return Task.Dependency.DefinitionContext.defineTask(block)
}

private val NOOP_TASK = task {
    executing {}
}

fun aggregatorTask(vararg dependency: Task<*>): Task<Unit> {
    return if (dependency.isEmpty()) {
        NOOP_TASK
    } else {
        task {
            for (d in dependency) dependency(d)
            executing {}
        }
    }
}

fun <T> independentTask(block: () -> T): Task<T> = task {
    executing { block() }
}

fun <T> independentTask(block: Callable<T>): Task<T> = task {
    executing { block.call() }
}

fun independentTask(block: Runnable): Task<Unit> = task {
    executing { block.run() }
}

sealed class ExecutionState<T> {
    object Submitted : ExecutionState<Unit>()
    object Skipped: ExecutionState<Unit>()
    class Failed(val exception: Throwable) : ExecutionState<Unit>()
    class Succeeded<T>(val value: T) : ExecutionState<T>()
}

class SkippedExecutionException : Exception("Unable to execute task: dependent task(s) failed")

class TaskExecutor(val executorService: ExecutorService,
                   val concurrency: Int = -1) : Task.Dependency.ExecutionContext() {

    private val results = IdentityHashMap<Task<*>, ExecutionState<*>>()
    private var submitted = 0

    @Suppress("UNCHECKED_CAST")
    override operator fun <T> get(task: Task<T>): T = when (val result = results[task]) {
        is ExecutionState.Succeeded<*> -> result.value as T
        else -> throw IllegalStateException()
    }

    private fun <T> findExecutableTasks(task: Task<T>): List<Task<*>> {
        val myState: ExecutionState<*>? = results[task]
        if (myState != null) return emptyList()
        val ready = task.dependencies.none { d ->
            when (results[d]) {
                null, is ExecutionState.Submitted -> true
                else -> false
            }
        }
        if (ready) {
            return listOf(task)
        }
        return task.dependencies.flatMap { d ->
            when (results[d]) {
                null -> findExecutableTasks(d)
                else -> emptyList()
            }
        }
    }

    private fun submit(task: Task<*>, resultReceiver: ArrayBlockingQueue<ExecutionState<*>>) {
        synchronized(results) {
            for (t in findExecutableTasks(task)) {
                if (results.containsKey(t)) continue
                if (concurrency in 1..submitted) break
                executorService.submit {
                    val ready = synchronized(results) {
                        t.dependencies.all { d ->
                            results[d] is ExecutionState.Succeeded<*>
                        }
                    }
                    val result = if (!ready) {
                        ExecutionState.Skipped
                    } else {
                        try {
                            ExecutionState.Succeeded(t.block(this))
                        } catch (ex: Throwable) {
                            ExecutionState.Failed(ex)
                        }
                    }
                    synchronized(results) {
                        //FIXME should be in finally block
                        results[t] = result
                        submitted--
                        if (task == t) {
                            resultReceiver.put(result)
                        } else {
                            submit(task, resultReceiver)
                        }
                    }
                }
                results[t] = ExecutionState.Submitted
                submitted++
            }
        }
    }

    private fun SkippedExecutionException.addSuppressed(t: Task<*>) {
        for (d in t.dependencies) {
            when (val result = results[d]) {
                is ExecutionState.Failed -> addSuppressed(result.exception)
                is ExecutionState.Skipped -> addSuppressed(d)
            }
        }
    }

    fun <T> execute(task: Task<T>): T {
        val result = results[task]
                ?: ArrayBlockingQueue<ExecutionState<*>>(1)
                        .also { q -> submit(task, q) }
                        .take()
        when (result) {
            is ExecutionState.Succeeded -> {
                @Suppress("UNCHECKED_CAST")
                return result.value as T
            }
            is ExecutionState.Failed -> throw result.exception
            is ExecutionState.Skipped -> throw SkippedExecutionException().also { ex ->
                ex.addSuppressed(task)
            }
            else -> throw IllegalStateException()
        }
    }

}

fun main() {
    val task1 = task {
        executing {
            println("executing task1")
            Thread.sleep(1000L)
            2
        }
    }
    val failingTask = task {
        executing {
            println("executing failing task")
            Thread.sleep(1000L)
            if (true) error("1234")
        }
    }
    val failingTask2 = task {
        executing {
            println("executing failing task 2")
            Thread.sleep(1000L)
            if (true) error("5678")
        }
    }
    val task2 = task {
        dependency(failingTask)
        val dep1 = dependency(task1)
        val dep2 = dependency(task1)
        val dep3 = dependency(task1)
        val dep4 = dependency(task1)
        val dep5 = dependency(task1)
        executing {
            dep1.value * dep2.value * dep3.value * dep4.value * dep5.value
        }
    }
    val task3 = task {
        dependency(task2)
        dependency(failingTask2)
        executing { 100 }
    }
    val executor = TaskExecutor(Executors.newCachedThreadPool(), 4)
    try {
        val result = executor.execute(task3)
        println(result)
    } finally {
        executor.executorService.shutdown()
    }
}
