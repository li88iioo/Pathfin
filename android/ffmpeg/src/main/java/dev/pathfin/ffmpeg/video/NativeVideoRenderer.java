package dev.pathfin.ffmpeg.video;

import android.os.Handler;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.video.DecoderVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 系统视频解码器后的补解 Renderer；CPU 解码，GPU 按帧元数据映射到 SDR。 */
@UnstableApi
public final class NativeVideoRenderer extends DecoderVideoRenderer {
  private static final Pattern DV_PROFILE = Pattern.compile("(?:dvhe|dvh1)\\.(\\d{2})\\.\\d{2}");
  @Nullable private NativeVideoDecoder decoder;

  public NativeVideoRenderer(long joiningTimeMs, Handler handler, VideoRendererEventListener listener) {
    super(joiningTimeMs, handler, listener, 50);
  }

  @Override public String getName() { return "PathfinFfmpegVideoRenderer"; }

  @Override public int supportsFormat(Format format) {
    if (!MimeTypes.isVideo(format.sampleMimeType))
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
    String codec = codecFor(format);
    if (codec == null || !NativeVideoDecoder.isCodecAvailable(codec))
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE);
    if (format.cryptoType != C.CRYPTO_TYPE_NONE || format.drmInitData != null)
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM);
    // 无 CodecPrivate 的 Matroska 会把未知位深填成 8，不能用该默认值拒绝 DV5。
    // DV5 仍由 JNI 按实际 AVFrame 和 RPU 验证 10 位及无残差层，不能仅凭容器标签放行像素。
    if (!isDolbyVision(format) && format.colorInfo != null &&
        (!supportsDepth(format.colorInfo.lumaBitdepth, false) || !supportsDepth(format.colorInfo.chromaBitdepth, false)))
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE);
    if (!supportsDimensions(format.width, format.height))
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE);
    return RendererCapabilities.create(C.FORMAT_HANDLED, ADAPTIVE_NOT_SEAMLESS, TUNNELING_NOT_SUPPORTED);
  }

  /** 与 Renderer 共用的能力边界；不为未知 DV Profile 或增强层重建虚报支持。 */
  public static boolean supportsSource(String codec, @Nullable Integer dvProfile, int width, int height, int bitDepth) {
    if (!supportsDimensions(width, height) || !supportsDepth(bitDepth, dvProfile != null)
        || dvProfile != null && dvProfile != 5) return false;
    if (dvProfile != null && !"hevc".equalsIgnoreCase(codec)) return false;
    return NativeVideoDecoder.isCodecAvailable(codec);
  }

  private static boolean supportsDimensions(int width, int height) {
    return width <= 4096 && height <= 4096 && (width <= 0 || height <= 0 || (long) width * height <= 4096L * 2304);
  }

  private static boolean supportsDepth(int depth, boolean dolbyVision) {
    return depth <= 0 || (dolbyVision ? depth == 10 : depth >= 8 && depth <= 12);
  }

  @Nullable static String codecFor(Format format) {
    if (MimeTypes.VIDEO_H264.equals(format.sampleMimeType)) return "h264";
    if (MimeTypes.VIDEO_H265.equals(format.sampleMimeType)) return "hevc";
    if (MimeTypes.VIDEO_MPEG2.equals(format.sampleMimeType)) return "mpeg2video";
    if (isDolbyVision(format) && format.codecs != null) {
      Matcher matcher = DV_PROFILE.matcher(format.codecs);
      if (matcher.matches() && Integer.parseInt(matcher.group(1)) == 5) return "hevc";
    }
    return null;
  }

  static boolean isDolbyVision(Format format) { return MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType); }

  @Override protected NativeVideoDecoder createDecoder(Format format, @Nullable CryptoConfig cryptoConfig) throws NativeVideoException {
    if (cryptoConfig != null) throw new NativeVideoException("本地补解不处理加密视频");
    decoder = new NativeVideoDecoder(format);
    return decoder;
  }

  @Override protected void renderOutputBufferToSurface(VideoDecoderOutputBuffer output, Surface surface) throws NativeVideoException {
    try {
      if (decoder == null) throw new NativeVideoException("视频解码器尚未初始化");
      decoder.renderToSurface(output, surface);
    } finally { output.release(); }
  }

  @Override protected void setDecoderOutputMode(int mode) {
    if (decoder != null) decoder.setOutputMode(mode);
  }

  @Override protected void releaseDecoder() {
    super.releaseDecoder();
    decoder = null;
  }
}
