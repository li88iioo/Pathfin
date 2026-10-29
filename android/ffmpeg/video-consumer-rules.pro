# Pathfin 视频桥接：JNI 使用固定类名、异常构造器和 Media3 输出缓冲字段。
-keep,includedescriptorclasses class dev.pathfin.ffmpeg.video.NativeVideoDecoder {
    native <methods>;
}
-keep class dev.pathfin.ffmpeg.video.NativeVideoException {
    public <init>(java.lang.String);
}
-keepclassmembers class androidx.media3.decoder.VideoDecoderOutputBuffer {
    long decoderPrivate;
    int width;
    int height;
}
-keepclassmembers class androidx.media3.decoder.DecoderOutputBuffer {
    long timeUs;
}
