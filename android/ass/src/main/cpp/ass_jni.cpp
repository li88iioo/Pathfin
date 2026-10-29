// Pathfin's small libass adapter. The libass public headers retain their ISC notice.
// All handles are confined to one Kotlin worker. No URL, font name, or ASS text is logged.
#include <jni.h>
#include <android/bitmap.h>
#include <ass.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

namespace {
constexpr size_t kMaxFont = 32 * 1024 * 1024;
constexpr size_t kMaxText = 8 * 1024 * 1024;
struct Engine {
    ASS_Library *library = nullptr;
    ASS_Renderer *renderer = nullptr;
    ASS_Image *images = nullptr;
    ASS_Track *lastTrack = nullptr;
    std::vector<ASS_Track *> tracks;
    std::string defaultFont;
    std::string defaultFamily;
    int width = 0, height = 0;
    bool changed = true;
};
template<class T> T *ptr(jlong handle) { return reinterpret_cast<T *>(handle); }

// 没有系统字体提供器：脚本字体缺失或缺字时按 defaultFamily（已注册的内存字体）再按 defaultFont 文件回退。
void applyFonts(Engine *engine) {
    ass_set_fonts(engine->renderer, engine->defaultFont.empty() ? nullptr : engine->defaultFont.c_str(),
                  engine->defaultFamily.empty() ? nullptr : engine->defaultFamily.c_str(), ASS_FONTPROVIDER_NONE, nullptr, 0);
}
void silentLog(int, const char *, va_list, void *) {}
void fail(JNIEnv *env, const char *message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, message);
}
std::string string(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
bool data(JNIEnv *env, jbyteArray bytes, size_t maximum, std::vector<char> &out) {
    if (!bytes) return false;
    jsize length = env->GetArrayLength(bytes);
    if (length <= 0 || static_cast<size_t>(length) > maximum) {
        fail(env, "ASS data exceeds safe size limit"); return false;
    }
    out.resize(static_cast<size_t>(length));
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(out.data()));
    return !env->ExceptionCheck();
}
void addSystemFont(Engine *engine, const std::string &path) {
    // Only hard-coded read-only Android system directories. Never an ASS-provided path.
    if (path.rfind("/system/fonts/", 0) != 0 || path.find("..") != std::string::npos) return;
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    if (!input) return;
    auto size = input.tellg();
    if (size <= 0 || size > static_cast<std::streamoff>(kMaxFont)) return;
    std::vector<char> bytes(static_cast<size_t>(size));
    input.seekg(0);
    if (!input.read(bytes.data(), size)) return;
    std::string name = path.substr(path.find_last_of('/') + 1);
    ass_add_font(engine->library, name.data(), bytes.data(), static_cast<int>(bytes.size()));
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_pathfin_ass_AssNative_create(JNIEnv *env, jobject, jstring defaultFont, jstring defaultFamily, jobjectArray fonts) {
    auto *engine = new Engine();
    engine->library = ass_library_init();
    if (!engine->library) { delete engine; fail(env, "Cannot initialize ASS library"); return 0; }
    ass_set_message_cb(engine->library, silentLog, nullptr);
    ass_set_extract_fonts(engine->library, 1);
    const jsize count = fonts ? std::min<jsize>(env->GetArrayLength(fonts), 6) : 0;
    for (jsize i = 0; i < count; ++i) {
        auto path = static_cast<jstring>(env->GetObjectArrayElement(fonts, i));
        addSystemFont(engine, string(env, path));
        env->DeleteLocalRef(path);
    }
    engine->renderer = ass_renderer_init(engine->library);
    if (!engine->renderer) {
        ass_library_done(engine->library); delete engine;
        fail(env, "Cannot initialize ASS renderer"); return 0;
    }
    engine->defaultFont = string(env, defaultFont);
    engine->defaultFamily = string(env, defaultFamily);
    applyFonts(engine);
    ass_set_hinting(engine->renderer, ASS_HINTING_NONE);
    ass_set_shaper(engine->renderer, ASS_SHAPING_COMPLEX);
    ass_set_use_margins(engine->renderer, 0);
    ass_set_cache_limits(engine->renderer, 1000, 24);
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pathfin_ass_AssNative_destroy(JNIEnv *, jobject, jlong handle) {
    auto *engine = ptr<Engine>(handle);
    if (!engine) return;
    ass_renderer_done(engine->renderer);
    for (auto *track : engine->tracks) ass_free_track(track);
    ass_library_done(engine->library);
    delete engine;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pathfin_ass_AssNative_addFont(JNIEnv *env, jobject, jlong handle, jstring filename, jbyteArray bytes) {
    auto *engine = ptr<Engine>(handle);
    std::vector<char> content;
    if (!engine || !data(env, bytes, kMaxFont, content)) return;
    const std::string name = string(env, filename);
    if (name.empty() || name.size() > 640 || name.find_first_of("/\\") != std::string::npos ||
        name.find("..") != std::string::npos) { fail(env, "Invalid ASS font name"); return; }
    ass_add_font(engine->library, const_cast<char *>(name.c_str()), content.data(), static_cast<int>(content.size()));
    applyFonts(engine);
    engine->changed = true;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_pathfin_ass_AssNative_newTrack(JNIEnv *env, jobject, jlong handle, jbyteArray header) {
    auto *engine = ptr<Engine>(handle);
    if (!engine) return 0;
    if (engine->tracks.size() >= 64) { fail(env, "Too many ASS tracks"); return 0; }
    ASS_Track *track = ass_new_track(engine->library);
    if (!track) { fail(env, "Cannot initialize ASS track"); return 0; }
    if (header && env->GetArrayLength(header) > 0) {
        std::vector<char> bytes;
        if (!data(env, header, kMaxText, bytes)) { ass_free_track(track); return 0; }
        ass_process_codec_private(track, bytes.data(), static_cast<int>(bytes.size()));
    }
    ass_set_check_readorder(track, 1);
    engine->tracks.push_back(track);
    return reinterpret_cast<jlong>(track);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pathfin_ass_AssNative_freeTrack(JNIEnv *, jobject, jlong handle, jlong trackHandle) {
    auto *engine = ptr<Engine>(handle);
    auto *track = ptr<ASS_Track>(trackHandle);
    if (engine && engine->lastTrack == track) { engine->lastTrack = nullptr; engine->images = nullptr; engine->changed = true; }
    if (track && engine) {
        engine->tracks.erase(std::remove(engine->tracks.begin(), engine->tracks.end(), track), engine->tracks.end());
        ass_free_track(track);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pathfin_ass_AssNative_processData(JNIEnv *env, jobject, jlong handle, jlong trackHandle, jbyteArray bytes) {
    auto *engine = ptr<Engine>(handle);
    auto *track = ptr<ASS_Track>(trackHandle);
    std::vector<char> content;
    if (engine && track && data(env, bytes, kMaxText, content)) {
        ass_process_data(track, content.data(), static_cast<int>(content.size()));
        // Full external ASS may contain an inline [Fonts] section. Refresh the memory font
        // provider after extraction; never interpret a script font name as a file or URL.
        applyFonts(engine);
        engine->changed = true;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pathfin_ass_AssNative_processChunk(JNIEnv *env, jobject, jlong trackHandle, jbyteArray bytes, jlong start, jlong duration) {
    auto *track = ptr<ASS_Track>(trackHandle);
    std::vector<char> content;
    if (track && start >= 0 && duration > 0 && duration <= 24LL * 60 * 60 * 1000 &&
        data(env, bytes, 1024 * 1024, content))
        ass_process_chunk(track, content.data(), static_cast<int>(content.size()), start, duration);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_pathfin_ass_AssNative_render(JNIEnv *env, jobject, jlong handle, jlong trackHandle, jlong time,
                                   jint width, jint height, jint storageWidth, jint storageHeight) {
    auto *engine = ptr<Engine>(handle);
    auto *track = ptr<ASS_Track>(trackHandle);
    if (!engine || !track || width <= 0 || height <= 0 ||
        static_cast<int64_t>(width) * height > 2'100'000 || width > 4096 || height > 4096) {
        fail(env, "Invalid ASS frame size"); return 0;
    }
    bool force = engine->changed || engine->lastTrack != track || engine->width != width || engine->height != height;
    ass_set_frame_size(engine->renderer, width, height);
    ass_set_storage_size(engine->renderer, std::max(storageWidth, 1), std::max(storageHeight, 1));
    ass_set_pixel_aspect(engine->renderer, 1.0);
    int changed = 0;
    engine->images = ass_render_frame(engine->renderer, track, std::max<jlong>(time, 0), &changed);
    engine->lastTrack = track; engine->width = width; engine->height = height; engine->changed = false;
    if (!force && !changed) return 0;
    int count = 0;
    for (auto *image = engine->images; image; image = image->next) ++count;
    return count == 0 ? -1 : count;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pathfin_ass_AssNative_copyFrame(JNIEnv *env, jobject, jlong handle, jobject bitmap) {
    auto *engine = ptr<Engine>(handle);
    if (!engine) return;
    AndroidBitmapInfo info{};
    void *memory = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        info.width != static_cast<uint32_t>(engine->width) || info.height != static_cast<uint32_t>(engine->height) ||
        AndroidBitmap_lockPixels(env, bitmap, &memory) != ANDROID_BITMAP_RESULT_SUCCESS) {
        fail(env, "Invalid ASS bitmap"); return;
    }
    std::memset(memory, 0, static_cast<size_t>(info.stride) * info.height);
    for (auto *image = engine->images; image; image = image->next) {
        if (!image->bitmap || image->w <= 0 || image->h <= 0 || image->stride < image->w) continue;
        const unsigned opacity = 255 - (image->color & 255);
        const unsigned red = image->color >> 24, green = (image->color >> 16) & 255, blue = (image->color >> 8) & 255;
        // A malicious ASS position may reach INT_MIN/MAX. Do not negate/subtract signed ints.
        const int64_t dx = image->dst_x, dy = image->dst_y;
        const int64_t left = std::max<int64_t>(0, dx), top = std::max<int64_t>(0, dy);
        const int64_t right = std::min<int64_t>(engine->width, dx + static_cast<int64_t>(image->w));
        const int64_t bottom = std::min<int64_t>(engine->height, dy + static_cast<int64_t>(image->h));
        if (left >= right || top >= bottom) continue;
        // Intersections are now within the validated <= 4096px target and the libass image.
        for (int y = static_cast<int>(top); y < static_cast<int>(bottom); ++y) {
            auto *dst = static_cast<uint8_t *>(memory) + static_cast<size_t>(y) * info.stride;
            const auto *src = image->bitmap + static_cast<size_t>(static_cast<int64_t>(y) - dy) * image->stride;
            for (int x = static_cast<int>(left); x < static_cast<int>(right); ++x) {
                const auto sx = static_cast<size_t>(static_cast<int64_t>(x) - dx);
                const unsigned alpha = (src[sx] * opacity + 127) / 255;
                if (alpha == 0) continue;
                auto *pixel = dst + static_cast<size_t>(x) * 4;
                const unsigned inverse = 255 - alpha;
                pixel[0] = static_cast<uint8_t>((red * alpha + pixel[0] * inverse + 127) / 255);
                pixel[1] = static_cast<uint8_t>((green * alpha + pixel[1] * inverse + 127) / 255);
                pixel[2] = static_cast<uint8_t>((blue * alpha + pixel[2] * inverse + 127) / 255);
                pixel[3] = static_cast<uint8_t>(alpha + (pixel[3] * inverse + 127) / 255);
            }
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
}
