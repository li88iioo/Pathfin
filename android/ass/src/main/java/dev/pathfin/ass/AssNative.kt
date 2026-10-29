package dev.pathfin.ass

import android.graphics.Bitmap

/** JNI is loaded only by the controller's single worker, never by capability checks. */
internal object AssNative {
    fun load() { System.loadLibrary("pathfin_ass_jni") }
    /** defaultFamily 为空时仅按 defaultFont 文件回退。 */
    external fun create(defaultFont: String, defaultFamily: String, fallbackFonts: Array<String>): Long
    external fun destroy(engine: Long)
    external fun addFont(engine: Long, name: String, bytes: ByteArray)
    external fun newTrack(engine: Long, header: ByteArray): Long
    external fun freeTrack(engine: Long, track: Long)
    external fun processData(engine: Long, track: Long, bytes: ByteArray)
    external fun processChunk(track: Long, bytes: ByteArray, startMs: Long, durationMs: Long)
    /** 0 = unchanged, -1 = changed to no image, positive = actual image count. */
    external fun render(engine: Long, track: Long, timeMs: Long, width: Int, height: Int,
                        storageWidth: Int, storageHeight: Int): Int
    external fun copyFrame(engine: Long, bitmap: Bitmap)
}
