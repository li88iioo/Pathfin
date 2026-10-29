package dev.pathfin.ffmpeg.video;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;

/** 视频补解或 GPU 呈现失败；不改变媒体请求的直连归属。 */
@UnstableApi
public final class NativeVideoException extends DecoderException {
  public NativeVideoException(String message) { super(message); }
  public NativeVideoException(String message, Throwable cause) { super(message, cause); }
}
