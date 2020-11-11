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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

sealed class Task<T>(
        private val dependency: Dependency<T>,
        val dependencies: List<Task<*>>
) {
    class DoingNothing internal constructor(
            dependency: Dependency<Unit>,
            dependencies: List<Task<*>>
    ) : Task<Unit>(dependency, dependencies)

    class Running internal constructor(
            dependency: Dependency<Unit>,
            dependencies: List<Task<*>>,
            val runnable: Runnable
    ) : Task<Unit>(dependency, dependencies)

    class CallingLambda<T> internal constructor(
            dependency: Dependency<T>,
            dependencies: List<Task<*>>,
            val block: () -> T
    ) : Task<T>(dependency, dependencies)

    class ApplyingLambda<T> internal constructor(
            dependency: Dependency<T>,
            dependencies: List<Task<*>>,
            val block: Dependency.ExecutionContext.() -> T
    ): Task<T>(dependency, dependencies)

    class Dependency<T> private constructor() {

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

            fun doingNothing() =
                    DoingNothing(Dependency(), dependencies)

            fun running(runnable: Runnable) =
                    Running(Dependency(), dependencies, runnable)

            fun <T> calling(block: () -> T) =
                    CallingLambda(Dependency(), dependencies, block)

            fun <T> executing(block: ExecutionContext.() -> T): Task<T> {
                return ApplyingLambda(Dependency(), dependencies, block)
            }

            fun <T> TODO(): Task<T> = executing {
                kotlin.TODO()
            }

            fun <T> TODO(reason: String): Task<T> = executing {
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

            fun <T> value(dependency: Dependency<T>): T =
                    this@ExecutionContext[dependency]

            val <T> Dependency<T>.value: T
                get() = this@ExecutionContext[this]

            protected fun <T> Task<T>.asDependency(): Dependency<T> = this.dependency

            protected abstract operator fun <T> get(dependency: Dependency<T>): T
        }
    }
}

fun <T> task(block: Task.Dependency.DefinitionContext.() -> Task<T>): Task<T> {
    return Task.Dependency.DefinitionContext.defineTask(block)
}

private val NOOP_TASK = task { doingNothing() }

fun aggregatorTask(vararg dependency: Task<*>): Task<Unit> {
    return if (dependency.isEmpty()) {
        NOOP_TASK
    } else {
        task {
            for (d in dependency) dependency(d)
            doingNothing()
        }
    }
}

fun <T> independentTask(block: () -> T): Task<T> = task {
    calling(block)
}

fun independentTask(block: Runnable): Task<Unit> = task {
    running(block)
}

sealed class ExecutionState<T> {
    object Submitted : ExecutionState<Unit>()
    object Skipped: ExecutionState<Unit>()
    class Failed(val exception: Throwable) : ExecutionState<Unit>()
    class Succeeded<T>(val value: T) : ExecutionState<T>()
}

class SkippedExecutionException
    : Exception("Unable to execute task: dependent task(s) failed")

class TaskExecutor(
        val executorService: ExecutorService,
        val concurrency: Int = -1
) : Task.Dependency.ExecutionContext() {

    private val results =
            IdentityHashMap<Task.Dependency<*>, ExecutionState<*>>()

    private var submitted = 0

    @Suppress("UNCHECKED_CAST")
    override operator fun <T> get(dependency: Task.Dependency<T>): T {
        return when (val result = results[dependency]) {
            is ExecutionState.Succeeded<*> -> result.value as T
            else -> throw IllegalStateException()
        }
    }

    private fun <T> findExecutableTasks(task: Task<T>): Sequence<Task<*>> {
        val myState: ExecutionState<*>? = results[task.asDependency()]
        if (myState != null) return emptySequence()
        val ready = task.dependencies.none { d ->
            when (results[d.asDependency()]) {
                null, is ExecutionState.Submitted -> true
                else -> false
            }
        }
        if (ready) {
            return sequenceOf(task)
        }
        return task.dependencies.asSequence().flatMap { d ->
            when (results[d.asDependency()]) {
                null -> findExecutableTasks(d)
                else -> emptySequence()
            }
        }
    }

    private fun submit(task: Task<*>, resultReceiver: ArrayBlockingQueue<ExecutionState<*>>) {
        synchronized(results) {
            for (t in findExecutableTasks(task)) {
                if (results.containsKey(t.asDependency())) continue
                if (concurrency in 1..submitted) break
                executorService.submit {
                    val ready = synchronized(results) {
                        t.dependencies.all { d ->
                            results[d.asDependency()] is ExecutionState.Succeeded<*>
                        }
                    }
                    val result = try {
                        if (!ready) {
                            ExecutionState.Skipped
                        } else {
                            val value = when (t) {
                                is Task.DoingNothing -> Unit
                                is Task.Running -> t.runnable.run()
                                is Task.CallingLambda<*> -> t.block()
                                is Task.ApplyingLambda<*> -> t.block(this)
                            }
                            ExecutionState.Succeeded(value)
                        }
                    } catch (ex: Throwable) {
                        ExecutionState.Failed(ex)
                    }
                    synchronized(results) {
                        //FIXME should be in finally block
                        results[t.asDependency()] = result
                        submitted--
                        if (task == t) {
                            resultReceiver.put(result)
                        } else {
                            submit(task, resultReceiver)
                        }
                    }
                }
                results[t.asDependency()] = ExecutionState.Submitted
                submitted++
            }
        }
    }

    private fun collectExceptions(list: IdentityHashMap<Task<*>, Throwable>, t: Task<*>) {
        for (d in t.dependencies) {
            when (val result = results[d.asDependency()]) {
                is ExecutionState.Failed -> list[d] = result.exception
                is ExecutionState.Skipped -> collectExceptions(list, d)
            }
        }
    }

    fun <T> execute(task: Task<T>): T {
        val result = results[task.asDependency()]
                ?: ArrayBlockingQueue<ExecutionState<*>>(1)
                        .also { q -> submit(task, q) }
                        .take()
        when (result) {
            is ExecutionState.Succeeded -> {
                @Suppress("UNCHECKED_CAST")
                return result.value as T
            }
            is ExecutionState.Failed -> throw result.exception
            is ExecutionState.Skipped -> {
                val list = IdentityHashMap<Task<*>, Throwable>()
                collectExceptions(list, task)
                val ex = SkippedExecutionException()
                for (suppressed in list.values) {
                    if (suppressed != null) ex.addSuppressed(suppressed)
                }
                throw ex
            }
            else -> throw IllegalStateException()
        }
    }

}

fun main() {
    val task1 = independentTask {
        println("executing task1")
        Thread.sleep(1000L)
        2
    }
    val failingTask = independentTask(Runnable {
        println("executing failing task")
        Thread.sleep(1000L)
        if (true) error("1234")
    })
    val failingTask2 = independentTask {
        println("executing failing task 2")
        Thread.sleep(1000L)
        if (true) error("5678")
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
        val dep1 = dependency(task1)
        dependency(task2)
        dependency(failingTask2)
        executing {
            value(dep1) + 100
        }
    }
    val task4 = aggregatorTask(task2, task3)
    val executor = TaskExecutor(Executors.newCachedThreadPool(), 4)
    try {
        val result = executor.execute(task4)
        println(result)
    } finally {
        executor.executorService.shutdown()
    }
}
