package dev.pathfin.ffmpeg.video;

import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.decoder.ffmpeg.FfmpegLibrary;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;

/**
 * 有界 FFmpeg 视频队列。解码在工作线程，Surface 呈现在 ExoPlayer 播放线程。
 * 独立处理 send/receive 和 EOS，不能用一输入一输出的 SimpleDecoder 丢弃 B 帧尾部。
 */
@UnstableApi
public final class NativeVideoDecoder
    implements Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, NativeVideoException> {
  private final Object lock = new Object();
  private final ArrayDeque<DecoderInputBuffer> availableInputs = new ArrayDeque<>();
  private final ArrayDeque<DecoderInputBuffer> queuedInputs = new ArrayDeque<>();
  private final ArrayDeque<VideoDecoderOutputBuffer> availableOutputs = new ArrayDeque<>();
  private final ArrayDeque<VideoDecoderOutputBuffer> queuedOutputs = new ArrayDeque<>();
  private final VideoDecoderOutputBuffer[] allOutputs = new VideoDecoderOutputBuffer[6];
  private final long context;
  private final String name;
  private final Thread worker;
  @Nullable private DecoderInputBuffer dequeuedInput;
  @Nullable private NativeVideoException failure;
  @Nullable private Callback callback;
  @Nullable private Executor callbackExecutor;
  private boolean released;
  private int generation;
  private long outputStartTimeUs;
  private int skippedFrames;
  private volatile int outputMode = C.VIDEO_OUTPUT_MODE_SURFACE_YUV;

  public NativeVideoDecoder(Format format) throws NativeVideoException {
    String codec = NativeVideoRenderer.codecFor(format);
    if (codec == null || !isCodecAvailable(codec)) {
      throw new NativeVideoException("本地视频解码器不可用");
    }
    ByteArrayOutputStream extra = new ByteArrayOutputStream();
    for (byte[] bytes : format.initializationData) extra.write(bytes, 0, bytes.length);
    ColorInfo color = format.colorInfo;
    context = create(codec, extra.toByteArray(), Math.max(0, format.width), Math.max(0, format.height),
        Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors())),
        color == null ? Format.NO_VALUE : color.colorSpace,
        color == null ? Format.NO_VALUE : color.colorRange,
        color == null ? Format.NO_VALUE : color.colorTransfer,
        format.rotationDegrees, NativeVideoRenderer.isDolbyVision(format));
    if (context == 0) throw new NativeVideoException("本地视频解码器初始化失败");
    name = "pathfin.ffmpeg." + codec + ".gpu-sdr";
    for (int i = 0; i < 4; i++) {
      availableInputs.add(new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT));
    }
    for (int i = 0; i < allOutputs.length; i++) {
      allOutputs[i] = new VideoDecoderOutputBuffer(this::releaseOutput);
      availableOutputs.add(allOutputs[i]);
    }
    worker = new Thread(this::runDecoder, "pathfin-video-decode");
    worker.start();
  }

  public static boolean isCodecAvailable(String codec) {
    try {
      return ("h264".equals(codec) || "hevc".equals(codec))
          && FfmpegLibrary.isAvailable() && hasDecoder(codec);
    } catch (LinkageError unavailable) {
      return false;
    }
  }

  @Override public String getName() { return name; }

  @Override public void setOutputStartTimeUs(long timeUs) {
    synchronized (lock) { outputStartTimeUs = timeUs; }
  }

  public void setOutputMode(int mode) { outputMode = mode; }

  @Override @Nullable public DecoderInputBuffer dequeueInputBuffer() throws NativeVideoException {
    synchronized (lock) {
      checkFailure();
      if (dequeuedInput != null) throw new IllegalStateException("输入缓冲尚未归还");
      return dequeuedInput = availableInputs.pollFirst();
    }
  }

  @Override public void queueInputBuffer(DecoderInputBuffer input) throws NativeVideoException {
    synchronized (lock) {
      checkFailure();
      if (input != dequeuedInput) throw new IllegalArgumentException("输入缓冲不属于当前解码器");
      dequeuedInput = null;
      queuedInputs.addLast(input);
      lock.notifyAll();
    }
  }

  @Override @Nullable public VideoDecoderOutputBuffer dequeueOutputBuffer() throws NativeVideoException {
    synchronized (lock) { checkFailure(); return queuedOutputs.pollFirst(); }
  }

  @Override public void setCallback(Callback value, Executor executor) {
    synchronized (lock) { callback = value; callbackExecutor = executor; }
  }

  @Override public void flush() {
    synchronized (lock) {
      generation++;
      outputStartTimeUs = 0;
      skippedFrames = 0;
      if (dequeuedInput != null) { recycleInput(dequeuedInput); dequeuedInput = null; }
      while (!queuedInputs.isEmpty()) recycleInput(queuedInputs.removeFirst());
      while (!queuedOutputs.isEmpty()) recycleOutput(queuedOutputs.removeFirst());
      lock.notifyAll();
    }
  }

  @Override public void release() {
    synchronized (lock) {
      if (released) return;
      released = true;
      lock.notifyAll();
    }
    boolean interrupted = false;
    for (;;) {
      try { worker.join(); break; }
      catch (InterruptedException ignored) { interrupted = true; }
    }
    synchronized (lock) {
      for (VideoDecoderOutputBuffer output : allOutputs) freeFrame(output);
      queuedOutputs.clear();
      queuedInputs.clear();
    }
    close(context);
    if (interrupted) Thread.currentThread().interrupt();
  }

  public void renderToSurface(VideoDecoderOutputBuffer output, Surface surface) throws NativeVideoException {
    synchronized (lock) {
      checkFailure();
      if (output.decoderPrivate == 0) throw new NativeVideoException("视频帧已释放");
    }
    // ExoPlayer 保证 render/release 同属播放线程，AVFrame 在 output.release 前保持所有权。
    render(context, output.decoderPrivate, surface);
  }

  private void runDecoder() {
    int observedGeneration = -1;
    try {
      for (;;) {
        DecoderInputBuffer input;
        int epoch;
        synchronized (lock) {
          while (!released && observedGeneration == generation && queuedInputs.isEmpty()) lock.wait();
          if (released) return;
          epoch = generation;
          input = observedGeneration == epoch ? queuedInputs.removeFirst() : null;
        }
        if (input == null) {
          flush(context);
          observedGeneration = epoch;
          continue;
        }
        try {
          while (isCurrent(epoch)) {
            ByteBuffer data = input.data;
            if (data != null && data.position() != 0) data = data.slice();
            int accepted = send(context, data, data == null ? 0 : data.remaining(), input.timeUs, input.isEndOfStream());
            int frames = drain(epoch);
            if (accepted == 0 || frames < 0) break;
            if (frames == 0) throw new NativeVideoException("视频解码器无法继续读取输入或输出帧");
          }
        } finally {
          synchronized (lock) { recycleInput(input); }
          signal(false);
        }
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      recordFailure(new NativeVideoException("视频解码线程中断", error));
    } catch (NativeVideoException error) {
      recordFailure(error);
    } catch (RuntimeException error) {
      recordFailure(new NativeVideoException("视频解码失败", error));
    }
  }

  /** 读尽当前可用帧；返回 -1 表示这一代已取消，EOS 仅在 native 完整 drain 后发布。 */
  private int drain(int epoch) throws NativeVideoException, InterruptedException {
    int count = 0;
    for (;;) {
      VideoDecoderOutputBuffer output;
      synchronized (lock) {
        while (!released && generation == epoch && availableOutputs.isEmpty()) lock.wait();
        if (released || generation != epoch) return -1;
        output = availableOutputs.removeFirst();
      }
      boolean published = false;
      try {
        int result = receive(context, output);
        if (result == 0) return count;
        boolean eos = result == 2;
        output.init(output.timeUs, outputMode, null);
        if (eos) output.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
        synchronized (lock) {
          if (released || generation != epoch) return -1;
          if (!eos && output.timeUs < outputStartTimeUs) {
            skippedFrames++;
          } else {
            output.skippedOutputBufferCount = skippedFrames;
            skippedFrames = 0;
            queuedOutputs.addLast(output);
            published = true;
          }
        }
        if (published) signal(true);
        count++;
        if (eos) return count;
      } finally {
        if (!published) synchronized (lock) { recycleOutput(output); lock.notifyAll(); }
      }
    }
  }

  private boolean isCurrent(int epoch) {
    synchronized (lock) { return !released && epoch == generation; }
  }

  private void checkFailure() throws NativeVideoException {
    if (failure != null) throw failure;
    if (released) throw new IllegalStateException("视频解码器已释放");
  }

  private void recordFailure(NativeVideoException error) {
    synchronized (lock) { if (!released) failure = error; }
    signal(true);
  }

  private void signal(boolean output) {
    Callback target;
    Executor executor;
    synchronized (lock) {
      if (released || callback == null || callbackExecutor == null) return;
      target = callback; executor = callbackExecutor;
    }
    executor.execute(() -> {
      synchronized (lock) { if (released) return; }
      if (output) target.onOutputBufferAvailable(); else target.onInputBufferAvailable();
    });
  }

  private void recycleInput(DecoderInputBuffer input) {
    input.clear(); availableInputs.addLast(input);
  }

  private void releaseOutput(VideoDecoderOutputBuffer output) {
    synchronized (lock) {
      freeFrame(output);
      if (!released) { output.clear(); availableOutputs.addLast(output); lock.notifyAll(); }
    }
  }

  private void recycleOutput(VideoDecoderOutputBuffer output) {
    freeFrame(output); output.clear(); availableOutputs.addLast(output);
  }

  private static void freeFrame(VideoDecoderOutputBuffer output) {
    if (output.decoderPrivate != 0) { releaseFrame(output.decoderPrivate); output.decoderPrivate = 0; }
  }

  private static native boolean hasDecoder(String codec);
  private static native long create(String codec, byte[] csd, int width, int height, int threads,
      int colorSpace, int colorRange, int colorTransfer, int rotation, boolean dolbyVision) throws NativeVideoException;
  private static native int send(long context, @Nullable ByteBuffer data, int size, long ptsUs, boolean eos) throws NativeVideoException;
  private static native int receive(long context, VideoDecoderOutputBuffer output) throws NativeVideoException;
  private static native void flush(long context);
  private static native void releaseFrame(long frame);
  private static native void render(long context, long frame, Surface surface) throws NativeVideoException;
  private static native void close(long context);
}
