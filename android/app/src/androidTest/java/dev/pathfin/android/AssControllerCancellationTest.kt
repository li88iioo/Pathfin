package dev.pathfin.android

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.ass.ExoAssExtension
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real controller/worker regression; no JNI, network, media account or injected production hook. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class AssControllerCancellationTest {
    @Before fun verificationOnly() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == VERIFICATION_PACKAGE)
    }

    @Test fun interruptedLoaderDoesNotFailOwnerAndAnotherSubtitleLoadStillSucceeds() {
        ControllerOwner().use { owner ->
            val track = owner.openTrack("cancelled-input")
            val parser = owner.parser("cancelled-input")
            val input = ("[Script Info]\nScriptType: v4.00+\n\n[Events]\n" +
                "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
                "Dialogue: 0,0:00:00.00,0:01:59.00,Default,,0,0,0,,PATHFIN CANCEL FIXTURE\n").toByteArray()
            val cues = CopyOnWriteArrayList<CuesWithTiming>()
            val output = Consumer<CuesWithTiming> { cues.add(it) }
            val gate = WorkerGate(owner.worker)
            val call = LoaderCall(interrupted = true) {
                parser.parse(input, 0, input.size, SubtitleParser.OutputOptions.allCues(), output)
                true
            }
            try {
                val failure = call.awaitFailure()
                assertFalse("A normal Media3 Loader interruption must not permanently fail ASS", owner.extension.failed)
                assertTrue("The Loader must retain its cancellation interrupt", call.interruptRetained.get())
                assertTrue("A cancelled ASS parser must not emit fallback SSA cues: ${cues.size}", cues.isEmpty())
                assertTrue("Cancellation must reach the real Media3 Loader as InterruptedIOException", failure is InterruptedIOException)
                assertNull("A cancelled input must not return as a successfully parsed sample", call.result.get())
                gate.release()
                owner.drain()
                assertNull("The cancelled queued parse must not modify its track", owner.fullData(track))

                parser.parse(input, 0, input.size, SubtitleParser.OutputOptions.allCues(), output)
                assertArrayEquals(input, owner.fullData(track))
                assertEquals("The next parse must use the raw ASS path", 1, cues.size)
                assertTrue("The extension must not permanently replace raw ASS with base SSA", cues.single().cues.isEmpty())
                assertFalse(owner.extension.failed)
                main { assertTrue(owner.statuses.none { it?.contains("已回退") == true }) }
            } finally {
                gate.release()
                call.finish()
            }
        }
    }

    @Test fun realWorkerExecutionFailureStillFailsOwnerInsteadOfBeingTreatedAsCancellation() {
        ControllerOwner().use { owner ->
            // Each input is legal individually; together they exceed the real owner-wide budget.
            // The second failure is thrown inside the worker and reaches Future.get as ExecutionException.
            val input = ByteArray(4 * 1024 * 1024 + 1) { 'x'.code.toByte() }
            assertTrue(owner.external(owner.openTrack("budget-a"), input))
            assertFalse(owner.external(owner.openTrack("budget-b"), input))
            assertTrue("Actual worker failures must still request the existing SSA fallback", owner.extension.failed)
        }
    }

    @Test fun closingOwnerWithAPendingParseDoesNotMarkFailureAndTerminatesItsWorker() {
        val owner = ControllerOwner()
        val gate = WorkerGate(owner.worker)
        val track = owner.openTrack("closing-input")
        val call = LoaderCall { owner.external(track, "pending subtitle".toByteArray()) }
        try {
            call.awaitWorkerWait()
            owner.close()
            gate.release()
            call.await()
            assertEquals(false, call.result.get())
            assertFalse("Closing a source is not an ASS failure", owner.extension.failed)
            assertTrue("Owned worker cleanup must complete", owner.worker.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS))
            assertTrue(owner.worker.isShutdown)
            assertNull(owner.openTrackOrNull("after-close"))
        } finally {
            gate.release()
            call.finish()
            owner.close()
        }
    }

    private class ControllerOwner : AutoCloseable {
        val statuses = CopyOnWriteArrayList<String?>()
        val extension = main {
            ExoAssExtension(InstrumentationRegistry.getInstrumentation().targetContext, statuses::add)
        }
        private val controller = extension.javaClass.getDeclaredField("controller").apply { isAccessible = true }.get(extension)
        val worker = controller.javaClass.getDeclaredField("worker").apply { isAccessible = true }.get(controller) as ExecutorService
        private val open = controller.javaClass.getDeclaredMethod("openTrack", Format::class.java).apply { isAccessible = true }
        private val closed = AtomicBoolean()

        private fun format(id: String): Format = Format.Builder().setId(id).setSampleMimeType(MimeTypes.TEXT_SSA)
            .setLanguage("en").setLabel("Pathfin controller cancellation fixture").build()

        fun openTrackOrNull(id: String): Any? = open.invoke(controller, format(id))

        fun openTrack(id: String): Any = checkNotNull(openTrackOrNull(id))

        fun parser(id: String): SubtitleParser {
            val factory = Class.forName("dev.pathfin.ass.AssSubtitleParserFactory")
                .getDeclaredConstructor(controller.javaClass, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.newInstance(controller, false) as SubtitleParser.Factory
            return factory.create(format(id))
        }

        fun external(track: Any, bytes: ByteArray): Boolean =
            controller.javaClass.getDeclaredMethod("external", track.javaClass, ByteArray::class.java)
                .apply { isAccessible = true }.invoke(controller, track, bytes) as Boolean

        fun fullData(track: Any): ByteArray? = track.javaClass.getDeclaredField("fullData")
            .apply { isAccessible = true }.get(track) as ByteArray?

        fun drain() = worker.submit {}.get(WAIT_SECONDS, TimeUnit.SECONDS)

        override fun close() {
            if (closed.compareAndSet(false, true)) main { extension.close() }
        }
    }

    private class WorkerGate(worker: ExecutorService) {
        private val release = CountDownLatch(1)
        init {
            val entered = CountDownLatch(1)
            worker.execute {
                entered.countDown()
                check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "Synthetic ASS worker gate timed out" }
            }
            assertTrue("The real single worker must be blocked before loading", entered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        }
        fun release() = release.countDown()
    }

    private class LoaderCall(interrupted: Boolean = false, action: () -> Boolean) {
        val result = AtomicReference<Boolean?>()
        val interruptRetained = AtomicBoolean()
        private val failed = AtomicReference<Throwable?>()
        private val complete = CountDownLatch(1)
        private val thread = Thread({
            try {
                // Loader.LoadTask.cancel() interrupts the extraction thread. Blocking the actual
                // controller worker above guarantees that Future.get observes this cancellation.
                if (interrupted) Thread.currentThread().interrupt()
                result.set(action())
            } catch (error: Throwable) {
                failed.set(error)
            } finally {
                interruptRetained.set(Thread.currentThread().isInterrupted)
                Thread.interrupted()
                complete.countDown()
            }
        }, "Pathfin-synthetic-cancelled-loader").apply { isDaemon = true; start() }

        fun awaitFailure(): Throwable? {
            assertTrue("Synthetic Loader must return", complete.await(WAIT_SECONDS, TimeUnit.SECONDS))
            return failed.get()
        }

        fun await() { awaitFailure()?.let { throw AssertionError("Synthetic Loader invocation failed", it) } }

        fun awaitWorkerWait() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
            while (thread.state != Thread.State.TIMED_WAITING && complete.count != 0L && System.nanoTime() < deadline) {
                complete.await(1, TimeUnit.MILLISECONDS)
            }
            assertEquals("The Loader must be awaiting the gated worker", Thread.State.TIMED_WAITING, thread.state)
        }

        fun finish() {
            if (complete.count != 0L) thread.interrupt()
            assertTrue("Synthetic Loader must not leak after the test", complete.await(WAIT_SECONDS, TimeUnit.SECONDS))
        }
    }

    private companion object {
        const val VERIFICATION_PACKAGE = "dev.pathfin.android.verification"
        const val WAIT_SECONDS = 10L

        fun <T> main(block: () -> T): T {
            val task = FutureTask<T> { block() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync(task)
            return task.get(WAIT_SECONDS, TimeUnit.SECONDS)
        }
    }
}
