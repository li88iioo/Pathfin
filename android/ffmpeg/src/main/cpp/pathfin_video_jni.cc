/*
 * Pathfin native video extension for Media3. FFmpeg performs CPU video decode;
 * libplacebo performs GPU color conversion/presentation. Both dependencies keep
 * their original LGPL-2.1-or-later licenses and pinned source disclosures.
 */
#include <jni.h>
#include <android/native_window_jni.h>
#include <cerrno>
#include <climits>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/dovi_meta.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/mathematics.h>
#include <libavutil/mem.h>
#include <libavutil/pixdesc.h>
}
#include "pathfin_video_render.h"

namespace {
constexpr int kMaximumDimension = 4096;
constexpr int64_t kMaximumPixels = 4096LL * 2304;

bool supported_dimensions(int width, int height)
{
    return width >= 0 && height >= 0 && width <= kMaximumDimension &&
           height <= kMaximumDimension &&
           (width == 0 || height == 0 || static_cast<int64_t>(width) * height <= kMaximumPixels);
}

int allocate_video_frame(AVCodecContext *decoder, AVFrame *frame, int flags)
{
    // A new SPS can change dimensions after avcodec_open2. Refuse it before
    // allocating pixel buffers, including dimensions whose product alone fits.
    if (frame->width <= 0 || frame->height <= 0 ||
        !supported_dimensions(frame->width, frame->height))
        return AVERROR(EINVAL);
    return avcodec_default_get_buffer2(decoder, frame, flags);
}

struct VideoContext {
    AVCodecContext *decoder;
    PathfinVideoRender *renderer;
    int rotation;
    bool dolby_vision;
    bool sent_eos;
    AVColorSpace source_space;
    AVColorPrimaries source_primaries;
    AVColorTransferCharacteristic source_transfer;
    AVColorRange source_range;
    jclass output_class;
    jfieldID output_time;
    jfieldID output_width;
    jfieldID output_height;
    jfieldID output_private;
};

void throw_video(JNIEnv *env, const char *message)
{
    if (env->ExceptionCheck()) return;
    jclass exception = env->FindClass("dev/pathfin/ffmpeg/video/NativeVideoException");
    if (exception) {
        env->ThrowNew(exception, message);
        env->DeleteLocalRef(exception);
    }
}

void throw_ffmpeg(JNIEnv *env, const char *operation, int code)
{
    char detail[AV_ERROR_MAX_STRING_SIZE] = {0};
    char message[256];
    av_strerror(code, detail, sizeof(detail));
    snprintf(message, sizeof(message), "%s: %s", operation, detail);
    throw_video(env, message);
}

VideoContext *context(JNIEnv *env, jlong handle)
{
    auto *ctx = reinterpret_cast<VideoContext *>(static_cast<intptr_t>(handle));
    if (!ctx) throw_video(env, "Native video decoder is closed");
    return ctx;
}

const AVCodec *find_codec(JNIEnv *env, jstring name)
{
    if (!name) return nullptr;
    const char *codec = env->GetStringUTFChars(name, nullptr);
    if (!codec) return nullptr;
    const AVCodec *decoder = avcodec_find_decoder_by_name(codec);
    env->ReleaseStringUTFChars(name, codec);
    return decoder && decoder->type == AVMEDIA_TYPE_VIDEO ? decoder : nullptr;
}

void set_color_properties(AVCodecContext *decoder, int space, int range, int transfer)
{
    // Media3 C.Color* values use Android MediaFormat constants, not FFmpeg enums.
    switch (space) {
        case 1: decoder->colorspace = AVCOL_SPC_BT709;
                decoder->color_primaries = AVCOL_PRI_BT709; break;
        case 2: decoder->colorspace = AVCOL_SPC_BT470BG;
                decoder->color_primaries = AVCOL_PRI_BT470BG; break;
        case 6: decoder->colorspace = AVCOL_SPC_BT2020_NCL;
                decoder->color_primaries = AVCOL_PRI_BT2020; break;
        default: break;
    }
    switch (range) {
        case 1: decoder->color_range = AVCOL_RANGE_JPEG; break;
        case 2: decoder->color_range = AVCOL_RANGE_MPEG; break;
        default: break;
    }
    switch (transfer) {
        case 1: decoder->color_trc = AVCOL_TRC_LINEAR; break;
        case 2: decoder->color_trc = AVCOL_TRC_IEC61966_2_1; break;
        case 3: decoder->color_trc = AVCOL_TRC_SMPTE170M; break;
        case 6: decoder->color_trc = AVCOL_TRC_SMPTE2084; break;
        case 7: decoder->color_trc = AVCOL_TRC_ARIB_STD_B67; break;
        case 10: decoder->color_trc = AVCOL_TRC_GAMMA22; break;
        default: break;
    }
}

bool in_bounds(size_t offset, size_t bytes, size_t total)
{
    return offset <= total && bytes <= total - offset;
}

bool validate_dolby(JNIEnv *env, const VideoContext *ctx, const AVFrame *frame)
{
    const AVFrameSideData *sd = av_frame_get_side_data(frame, AV_FRAME_DATA_DOVI_METADATA);
    const bool has_rpu = av_frame_get_side_data(frame, AV_FRAME_DATA_DOVI_RPU_BUFFER) != nullptr;
    if (!sd) {
        if (ctx->dolby_vision || has_rpu) {
            throw_video(env, "Dolby Vision frame has no usable color metadata; refusing incorrect colors");
            return false;
        }
        return true;
    }
    if (sd->size < sizeof(AVDOVIMetadata)) {
        throw_video(env, "Truncated Dolby Vision color metadata");
        return false;
    }
    const auto *metadata = reinterpret_cast<const AVDOVIMetadata *>(sd->data);
    if (!in_bounds(metadata->header_offset, sizeof(AVDOVIRpuDataHeader), sd->size) ||
        !in_bounds(metadata->mapping_offset, sizeof(AVDOVIDataMapping), sd->size) ||
        !in_bounds(metadata->color_offset, sizeof(AVDOVIColorMetadata), sd->size) ||
        metadata->num_ext_blocks < 0 || metadata->num_ext_blocks > AV_DOVI_MAX_EXT_BLOCKS ||
        (metadata->num_ext_blocks > 0 &&
         (metadata->ext_block_size < sizeof(AVDOVIDmData) ||
          metadata->ext_block_size > sd->size / static_cast<size_t>(metadata->num_ext_blocks) ||
          !in_bounds(metadata->ext_block_offset,
                     metadata->ext_block_size * metadata->num_ext_blocks, sd->size)))) {
        throw_video(env, "Invalid Dolby Vision metadata layout");
        return false;
    }
    const auto *header = av_dovi_get_header(metadata);
    const bool known_profile =
        (header->vdr_rpu_profile == 0 && header->bl_video_full_range_flag) ||
        header->vdr_rpu_profile == 1;
    const AVPixFmtDescriptor *pixels = av_pix_fmt_desc_get(static_cast<AVPixelFormat>(frame->format));
    if (!known_profile || !header->disable_residual_flag || header->bl_bit_depth != 10 ||
        !pixels || pixels->nb_components != 3 || pixels->comp[0].depth != 10) {
        throw_video(env, "Unsupported Dolby Vision profile or enhancement-layer residual");
        return false;
    }
    return true;
}

bool initialize_output_fields(JNIEnv *env, VideoContext *ctx, jobject output)
{
    if (!output) {
        throw_video(env, "Missing Media3 video output buffer");
        return false;
    }
    if (ctx->output_class) return true;
    jclass local = env->GetObjectClass(output);
    if (!local) return false;
    ctx->output_class = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!ctx->output_class) return false;
    ctx->output_time = env->GetFieldID(ctx->output_class, "timeUs", "J");
    ctx->output_width = env->GetFieldID(ctx->output_class, "width", "I");
    ctx->output_height = env->GetFieldID(ctx->output_class, "height", "I");
    ctx->output_private = env->GetFieldID(ctx->output_class, "decoderPrivate", "J");
    return !env->ExceptionCheck() && ctx->output_time && ctx->output_width &&
           ctx->output_height && ctx->output_private;
}

void destroy(JNIEnv *env, VideoContext *ctx)
{
    if (!ctx) return;
    pathfin_video_render_destroy(ctx->renderer);
    if (ctx->output_class) env->DeleteGlobalRef(ctx->output_class);
    avcodec_free_context(&ctx->decoder);
    free(ctx);
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_pathfin_ffmpeg_video_NativeVideoDecoder_hasDecoder(JNIEnv *env, jclass, jstring name)
{
    return find_codec(env, name) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_pathfin_ffmpeg_video_NativeVideoDecoder_create(
    JNIEnv *env, jclass, jstring name, jbyteArray csd, jint width, jint height,
    jint threads, jint color_space, jint color_range, jint color_transfer,
    jint rotation, jboolean dolby_vision)
{
    const AVCodec *codec = find_codec(env, name);
    if (!codec) {
        throw_video(env, "Requested CPU video decoder is not available");
        return 0;
    }
    if (!supported_dimensions(width, height) || rotation % 90 != 0 ||
        (dolby_vision && codec->id != AV_CODEC_ID_HEVC)) {
        throw_video(env, "Invalid video decoder configuration");
        return 0;
    }
    auto *ctx = static_cast<VideoContext *>(calloc(1, sizeof(VideoContext)));
    if (!ctx) {
        throw_video(env, "Cannot allocate video decoder");
        return 0;
    }
    ctx->rotation = (rotation % 360 + 360) % 360;
    ctx->dolby_vision = dolby_vision == JNI_TRUE;
    ctx->decoder = avcodec_alloc_context3(codec);
    ctx->renderer = pathfin_video_render_create();
    if (!ctx->decoder || !ctx->renderer) {
        destroy(env, ctx);
        throw_video(env, "Cannot allocate CPU decoder or GPU renderer");
        return 0;
    }
    ctx->decoder->width = width;
    ctx->decoder->height = height;
    ctx->decoder->max_pixels = kMaximumPixels;
    ctx->decoder->get_buffer2 = allocate_video_frame;
    ctx->decoder->pkt_timebase = AVRational {1, 1000000};
    ctx->decoder->thread_count = threads > 0 ? (threads > 8 ? 8 : threads) : 2;
    ctx->decoder->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
    set_color_properties(ctx->decoder, color_space, color_range, color_transfer);
    ctx->source_space = ctx->decoder->colorspace;
    ctx->source_primaries = ctx->decoder->color_primaries;
    ctx->source_transfer = ctx->decoder->color_trc;
    ctx->source_range = ctx->decoder->color_range;
    if (csd) {
        const jsize size = env->GetArrayLength(csd);
        if (size > 16 * 1024 * 1024) {
            destroy(env, ctx);
            throw_video(env, "Video initialization data is too large");
            return 0;
        }
        if (size > 0) {
            ctx->decoder->extradata = static_cast<uint8_t *>(av_mallocz(size + AV_INPUT_BUFFER_PADDING_SIZE));
            if (!ctx->decoder->extradata) {
                destroy(env, ctx);
                throw_video(env, "Cannot allocate padded video initialization data");
                return 0;
            }
            env->GetByteArrayRegion(csd, 0, size, reinterpret_cast<jbyte *>(ctx->decoder->extradata));
            ctx->decoder->extradata_size = size;
            if (env->ExceptionCheck()) { destroy(env, ctx); return 0; }
        }
    }
    int result = avcodec_open2(ctx->decoder, codec, nullptr);
    if (result < 0) {
        destroy(env, ctx);
        throw_ffmpeg(env, "Cannot open CPU video decoder", result);
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(ctx));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_pathfin_ffmpeg_video_NativeVideoDecoder_send(
    JNIEnv *env, jclass, jlong handle, jobject data, jint size, jlong pts_us, jboolean eos)
{
    auto *ctx = context(env, handle);
    if (!ctx) return 0;
    if (ctx->sent_eos) {
        if (eos) return 0;
        throw_video(env, "Video input after EOS requires a flush");
        return 0;
    }
    AVPacket *packet = nullptr;
    if (!eos) {
        auto *bytes = data ? static_cast<uint8_t *>(env->GetDirectBufferAddress(data)) : nullptr;
        const jlong capacity = data ? env->GetDirectBufferCapacity(data) : -1;
        if (!bytes || size <= 0 || capacity < size || size > INT_MAX - AV_INPUT_BUFFER_PADDING_SIZE) {
            throw_video(env, "Invalid direct video input buffer");
            return 0;
        }
        packet = av_packet_alloc();
        int result = packet ? av_new_packet(packet, size) : AVERROR(ENOMEM);
        if (result < 0) {
            av_packet_free(&packet);
            throw_ffmpeg(env, "Cannot allocate padded video packet", result);
            return 0;
        }
        memcpy(packet->data, bytes, size);
        packet->pts = pts_us;
        packet->dts = AV_NOPTS_VALUE;
        packet->time_base = AVRational {1, 1000000};
    }
    const int result = avcodec_send_packet(ctx->decoder, packet);
    av_packet_free(&packet);
    if (result == AVERROR(EAGAIN)) return 1;
    if (result < 0) {
        throw_ffmpeg(env, "CPU video input failed", result);
        return 0;
    }
    if (eos) ctx->sent_eos = true;
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_pathfin_ffmpeg_video_NativeVideoDecoder_receive(
    JNIEnv *env, jclass, jlong handle, jobject output)
{
    auto *ctx = context(env, handle);
    if (!ctx || !initialize_output_fields(env, ctx, output)) return 0;
    if (env->GetLongField(output, ctx->output_private) != 0) {
        throw_video(env, "Previous native video frame has not been released");
        return 0;
    }
    AVFrame *frame = av_frame_alloc();
    if (!frame) { throw_video(env, "Cannot allocate video frame"); return 0; }
    int result = avcodec_receive_frame(ctx->decoder, frame);
    if (result < 0) {
        av_frame_free(&frame);
        if (result == AVERROR(EAGAIN)) return 0;
        if (result == AVERROR_EOF) return 2;
        throw_ffmpeg(env, "CPU video decode failed", result);
        return 0;
    }
    if (!validate_dolby(env, ctx, frame)) { av_frame_free(&frame); return 0; }
    int64_t pts = frame->best_effort_timestamp;
    if (pts == AV_NOPTS_VALUE) pts = frame->pts;
    if (pts == AV_NOPTS_VALUE || frame->width <= 0 || frame->height <= 0 ||
        !supported_dimensions(frame->width, frame->height)) {
        av_frame_free(&frame);
        throw_video(env, "Decoded video frame has no valid presentation timestamp or dimensions");
        return 0;
    }
    // Bitstream color tags take precedence; container properties only fill gaps.
    if (frame->colorspace == AVCOL_SPC_UNSPECIFIED) frame->colorspace = ctx->source_space;
    if (frame->color_primaries == AVCOL_PRI_UNSPECIFIED) frame->color_primaries = ctx->source_primaries;
    if (frame->color_trc == AVCOL_TRC_UNSPECIFIED) frame->color_trc = ctx->source_transfer;
    if (frame->color_range == AVCOL_RANGE_UNSPECIFIED) frame->color_range = ctx->source_range;
    // DecoderVideoRenderer publishes width/height with square pixels and no
    // unapplied rotation. Report display geometry while leaving AVFrame pixels
    // untouched: libplacebo applies this same SAR and rotation exactly once.
    int64_t display_width = frame->width;
    int display_height = frame->height;
    if (frame->sample_aspect_ratio.num > 0 && frame->sample_aspect_ratio.den > 0)
        display_width = av_rescale_rnd(frame->width, frame->sample_aspect_ratio.num,
                                      frame->sample_aspect_ratio.den, AV_ROUND_NEAR_INF);
    if (display_width <= 0 || display_width > INT_MAX) {
        av_frame_free(&frame);
        throw_video(env, "Decoded video frame has invalid display aspect ratio");
        return 0;
    }
    int reported_width = static_cast<int>(display_width);
    int reported_height = display_height;
    if (ctx->rotation == 90 || ctx->rotation == 270) {
        reported_width = display_height;
        reported_height = static_cast<int>(display_width);
    }
    env->SetLongField(output, ctx->output_time, pts);
    env->SetIntField(output, ctx->output_width, reported_width);
    env->SetIntField(output, ctx->output_height, reported_height);
    if (env->ExceptionCheck()) { av_frame_free(&frame); return 0; }
    env->SetLongField(output, ctx->output_private,
                      static_cast<jlong>(reinterpret_cast<intptr_t>(frame)));
    return 1;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pathfin_ffmpeg_video_NativeVideoDecoder_flush(JNIEnv *env, jclass, jlong handle)
{
    auto *ctx = context(env, handle);
    if (!ctx) return;
    avcodec_flush_buffers(ctx->decoder);
    ctx->sent_eos = false;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pathfin_ffmpeg_video_NativeVideoDecoder_releaseFrame(JNIEnv *, jclass, jlong handle)
{
    auto *frame = reinterpret_cast<AVFrame *>(static_cast<intptr_t>(handle));
    av_frame_free(&frame);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pathfin_ffmpeg_video_NativeVideoDecoder_render(
    JNIEnv *env, jclass, jlong handle, jlong frame_handle, jobject surface)
{
    auto *ctx = context(env, handle);
    const auto *frame = reinterpret_cast<const AVFrame *>(static_cast<intptr_t>(frame_handle));
    if (!ctx) return;
    if (!frame || !surface) { throw_video(env, "Missing video frame or playback Surface"); return; }
    // SurfaceView may replace the native window inside the same Java Surface.
    // Resolve it every frame; the renderer alone retains the current window
    // and rebuilds its EGL surface when the actual native window changes.
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (!window || env->ExceptionCheck()) {
        if (window) ANativeWindow_release(window);
        throw_video(env, "Cannot acquire playback Surface");
        return;
    }
    char error[256] = {0};
    const bool rendered = pathfin_video_render_frame(ctx->renderer, frame, window,
                                                    ctx->rotation, error, sizeof(error));
    ANativeWindow_release(window);
    if (!rendered)
        throw_video(env, error[0] ? error : "GPU video presentation failed");
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pathfin_ffmpeg_video_NativeVideoDecoder_close(JNIEnv *env, jclass, jlong handle)
{
    // Java has joined the decode worker and released every independently owned
    // AVFrame; close runs on the same playback thread as render.
    destroy(env, reinterpret_cast<VideoContext *>(static_cast<intptr_t>(handle)));
}
