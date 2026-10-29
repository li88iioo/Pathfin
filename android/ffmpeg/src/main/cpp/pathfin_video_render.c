/*
 * Pathfin: FFmpeg frames -> libplacebo GLES -> Android video Surface.
 * Uses the public libplacebo API (LGPL-2.1-or-later), including its libav
 * header implementation. No MPV playback code or process is involved.
 */
#include "pathfin_video_render.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/data_space.h>
#include <android/log.h>
#include <android/native_window.h>
#include <dlfcn.h>
#include <math.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libavutil/frame.h>
#include <libplacebo/colorspace.h>
#include <libplacebo/log.h>
#include <libplacebo/opengl.h>
#include <libplacebo/renderer.h>
#include <libplacebo/swapchain.h>
/* C's default static-inline implementation uses the same FFmpeg headers as
 * the decoder. Keeping this in C avoids C++ designated-initializer differences. */
#include <libplacebo/utils/libav.h>

struct PathfinVideoRender {
    EGLDisplay display;
    EGLContext context;
    EGLConfig config;
    EGLSurface idle_surface;
    EGLSurface surface;
    ANativeWindow *window;
    bool initialized;
    bool owner_set;
    pthread_t owner;
    void *egl_library;
    void *gles_library;
    pl_log log;
    char gpu_error[256];
    pl_opengl gl;
    pl_renderer renderer;
    pl_swapchain swapchain;
    pl_tex textures[4];
    bool swap_failed;
    bool surface_is_srgb;
    bool failed;
};

static bool fail(char *error, size_t size, const char *message)
{
    if (error && size) snprintf(error, size, "%s", message);
    __android_log_print(ANDROID_LOG_ERROR, "PathfinVideo", "%s", message);
    return false;
}

static void gpu_log(void *opaque, enum pl_log_level level, const char *message)
{
    PathfinVideoRender *r = opaque;
    if (level > PL_LOG_ERR) return;
    /* No successful-frame logging: errors contain renderer diagnostics only. */
    if (!r->gpu_error[0]) snprintf(r->gpu_error, sizeof(r->gpu_error), "%s", message);
    __android_log_print(ANDROID_LOG_ERROR, "PathfinVideo", "libplacebo: %s", message);
}

static bool fail_gpu(PathfinVideoRender *r, char *error, size_t size, const char *message)
{
    char detail[512];
    if (r->gpu_error[0]) snprintf(detail, sizeof(detail), "%s: %s", message, r->gpu_error);
    else snprintf(detail, sizeof(detail), "%s", message);
    return fail(error, size, detail);
}

static bool fail_egl(char *error, size_t size, const char *message)
{
    char detail[256];
    snprintf(detail, sizeof(detail), "%s (EGL 0x%04x)", message, eglGetError());
    return fail(error, size, detail);
}

static pl_voidfunc_t gpu_get_proc(void *opaque, const char *name)
{
    PathfinVideoRender *r = opaque;
    /* EGL 1.4 need not expose core symbols through eglGetProcAddress. Load
     * those from Android's public dispatch libraries, then resolve extensions
     * through the actual EGL entry point. In particular, do not use glad's
     * default GLES loader: it reads its initially NULL global EGL loader before
     * libplacebo has initialized EGL (the latter happens after GL loading). */
    void *library = strncmp(name, "egl", 3) == 0 ? r->egl_library : r->gles_library;
    pl_voidfunc_t proc = library ? (pl_voidfunc_t)dlsym(library, name) : NULL;
    return proc ? proc : (pl_voidfunc_t)eglGetProcAddress(name);
}

static bool has_extension(const char *extensions, const char *name)
{
    if (!extensions) return false;
    const size_t n = strlen(name);
    for (const char *p = extensions; (p = strstr(p, name)); p += n) {
        if ((p == extensions || p[-1] == ' ') && (p[n] == '\0' || p[n] == ' '))
            return true;
    }
    return false;
}

static void swap_buffers(void *opaque)
{
    PathfinVideoRender *r = opaque;
    if (!eglSwapBuffers(r->display, r->surface)) r->swap_failed = true;
}

static void drop_window(PathfinVideoRender *r)
{
    if (r->display != EGL_NO_DISPLAY && r->context != EGL_NO_CONTEXT)
        eglMakeCurrent(r->display, r->idle_surface, r->idle_surface, r->context);
    if (r->swapchain) pl_swapchain_destroy(&r->swapchain);
    if (r->surface != EGL_NO_SURFACE) {
        eglDestroySurface(r->display, r->surface);
        r->surface = EGL_NO_SURFACE;
    }
    if (r->window) {
        ANativeWindow_release(r->window);
        r->window = NULL;
    }
}

static bool initialize(PathfinVideoRender *r, char *error, size_t size)
{
    r->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (r->display == EGL_NO_DISPLAY || !eglInitialize(r->display, NULL, NULL))
        return fail_egl(error, size, "Cannot initialize EGL display");
    r->initialized = true;
    if (!eglBindAPI(EGL_OPENGL_ES_API)) return fail_egl(error, size, "Cannot bind GLES API");
    const EGLint attributes[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE,
    };
    EGLint count = 0;
    if (!eglChooseConfig(r->display, attributes, &r->config, 1, &count) || count != 1)
        return fail_egl(error, size, "No GLES3 SDR surface configuration");
    const EGLint context_attributes[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    r->context = eglCreateContext(r->display, r->config, EGL_NO_CONTEXT, context_attributes);
    if (r->context == EGL_NO_CONTEXT) return fail_egl(error, size, "Cannot create GLES3 context");
    const EGLint idle_attributes[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    r->idle_surface = eglCreatePbufferSurface(r->display, r->config, idle_attributes);
    if (r->idle_surface == EGL_NO_SURFACE ||
        !eglMakeCurrent(r->display, r->idle_surface, r->idle_surface, r->context))
        return fail_egl(error, size, "Cannot create EGL cleanup surface");

    r->egl_library = dlopen("libEGL.so", RTLD_NOW | RTLD_LOCAL);
    r->gles_library = dlopen("libGLESv3.so", RTLD_NOW | RTLD_LOCAL);
    if (!r->egl_library || !r->gles_library)
        return fail(error, size, "Cannot load Android EGL/GLES dispatch libraries");
    r->log = pl_log_create(PL_API_VER, pl_log_params(
        .log_cb = gpu_log, .log_priv = r, .log_level = PL_LOG_ERR));
    if (!r->log) return fail(error, size, "Cannot allocate GPU diagnostic logger");
    struct pl_opengl_params gl_params = {
        .get_proc_addr_ex = gpu_get_proc,
        .proc_ctx = r,
        .egl_display = r->display,
        .egl_context = r->context,
    };
    r->gl = pl_opengl_create(r->log, &gl_params);
    if (!r->gl) {
        const char *vendor = (const char *)glGetString(GL_VENDOR);
        const char *renderer = (const char *)glGetString(GL_RENDERER);
        const char *version = (const char *)glGetString(GL_VERSION);
        const char *glsl = (const char *)glGetString(GL_SHADING_LANGUAGE_VERSION);
        __android_log_print(ANDROID_LOG_ERROR, "PathfinVideo",
            "GLES initialization failed: vendor=%s renderer=%s version=%s GLSL=%s",
            vendor ? vendor : "unknown", renderer ? renderer : "unknown",
            version ? version : "unknown", glsl ? glsl : "unknown");
        return fail_gpu(r, error, size, "Cannot initialize GPU color renderer");
    }
    r->renderer = pl_renderer_create(r->log, r->gl->gpu);
    if (!r->renderer) return fail_gpu(r, error, size, "Cannot create GPU frame renderer");
    return true;
}

static bool attach_window(PathfinVideoRender *r, ANativeWindow *window,
                          char *error, size_t size)
{
    drop_window(r);
    ANativeWindow_acquire(window);
    r->window = window;
    EGLint visual = 0;
    if (!eglGetConfigAttrib(r->display, r->config, EGL_NATIVE_VISUAL_ID, &visual) ||
        ANativeWindow_setBuffersGeometry(window, 0, 0, visual) != 0)
        return fail(error, size, "Cannot configure SDR video Surface");

    /* Introduced in API 28: no static reference, so API 26 can load this JNI.
     * EGL's sRGB surface declaration below is also available on API 26 drivers. */
    typedef int32_t (*set_dataspace_fn)(ANativeWindow *, int32_t);
    set_dataspace_fn set_dataspace = NULL;
    void *nativewindow = dlopen("libnativewindow.so", RTLD_NOW | RTLD_LOCAL);
    if (nativewindow) {
        set_dataspace = (set_dataspace_fn)dlsym(nativewindow, "ANativeWindow_setBuffersDataSpace");
    }
    bool dataspace_set = set_dataspace && set_dataspace(window, ADATASPACE_SRGB) == 0;
    if (nativewindow) dlclose(nativewindow);
    bool egl_srgb = has_extension(eglQueryString(r->display, EGL_EXTENSIONS),
                                  "EGL_KHR_gl_colorspace");
    if (!dataspace_set && !egl_srgb)
        return fail(error, size, "Device cannot declare SDR output colorspace");
    const EGLint srgb_attributes[] = {
        EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_SRGB_KHR, EGL_NONE,
    };
    const EGLint default_attributes[] = { EGL_NONE };
    r->surface = eglCreateWindowSurface(r->display, r->config, window,
                                        egl_srgb ? srgb_attributes : default_attributes);
    if (r->surface == EGL_NO_SURFACE ||
        !eglMakeCurrent(r->display, r->surface, r->surface, r->context))
        return fail_egl(error, size, "Cannot bind video Surface to GPU");

    /* An sRGB default framebuffer performs the final linear->sRGB conversion.
     * In that case ask libplacebo for linear output to avoid double encoding. */
    GLint encoding = GL_LINEAR;
    glGetError();
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glGetFramebufferAttachmentParameteriv(GL_FRAMEBUFFER, GL_BACK,
                                          GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING, &encoding);
    if (glGetError() != GL_NO_ERROR)
        return fail(error, size, "Cannot determine Surface transfer behavior");
    r->surface_is_srgb = encoding == GL_SRGB;
    struct pl_opengl_swapchain_params params = {
        .swap_buffers = swap_buffers,
        .priv = r,
        .max_swapchain_depth = 2,
    };
    r->swapchain = pl_opengl_create_swapchain(r->gl, &params);
    if (!r->swapchain) return fail(error, size, "Cannot create video swapchain");
    return true;
}

PathfinVideoRender *pathfin_video_render_create(void)
{
    PathfinVideoRender *r = calloc(1, sizeof(*r));
    if (r) {
        r->display = EGL_NO_DISPLAY;
        r->context = EGL_NO_CONTEXT;
        r->surface = r->idle_surface = EGL_NO_SURFACE;
    }
    return r;
}

bool pathfin_video_render_frame(PathfinVideoRender *r, const AVFrame *frame,
                                ANativeWindow *window, int rotation,
                                char *error, size_t size)
{
    if (!r || !frame || !window) return fail(error, size, "Missing video frame or Surface");
    if (!r->owner_set) {
        r->owner = pthread_self();
        r->owner_set = true;
    } else if (!pthread_equal(r->owner, pthread_self())) {
        return fail(error, size, "GPU renderer used from a different playback thread");
    }
    if (r->failed) return fail(error, size, "GPU video renderer requires recreation after failure");
    if (!r->renderer && !initialize(r, error, size)) {
        r->failed = true;
        return false;
    }
    if (r->window != window && !attach_window(r, window, error, size)) {
        r->failed = true;
        return false;
    }
    if (!r->swapchain || !eglMakeCurrent(r->display, r->surface, r->surface, r->context))
        return fail(error, size, "Video Surface is no longer available");
    EGLint width = 0, height = 0;
    if (!eglQuerySurface(r->display, r->surface, EGL_WIDTH, &width) ||
        !eglQuerySurface(r->display, r->surface, EGL_HEIGHT, &height) || width <= 0 || height <= 0)
        return fail(error, size, "Video Surface has no drawable size");
    if (!pl_swapchain_resize(r->swapchain, &width, &height))
        return fail(error, size, "Cannot resize video swapchain");
    if (!pl_test_pixfmt(r->gl->gpu, frame->format))
        return fail(error, size, "GPU cannot represent decoded pixel format without losing precision");

    struct pl_frame image = {0}, target = {0};
    struct pl_avframe_params params = {
        .frame = frame, .tex = r->textures, .map_dovi = true,
    };
    if (!pl_map_avframe_ex(r->gl->gpu, &image, &params))
        return fail(error, size, "Cannot upload video pixels and color metadata");
    if (av_frame_get_side_data(frame, AV_FRAME_DATA_DOVI_METADATA) &&
        (image.repr.sys != PL_COLOR_SYSTEM_DOLBYVISION || !image.repr.dovi)) {
        pl_unmap_avframe(r->gl->gpu, &image);
        return fail(error, size, "GPU renderer did not accept Dolby Vision color metadata");
    }
    image.rotation = pl_rotation_normalize(image.rotation + rotation / 90);
    struct pl_swapchain_frame swap_frame;
    if (!pl_swapchain_start_frame(r->swapchain, &swap_frame)) {
        pl_unmap_avframe(r->gl->gpu, &image);
        return fail(error, size, "Cannot start video output frame");
    }
    pl_frame_from_swapchain(&target, &swap_frame);
    target.color = pl_color_space_srgb;
    /* Match libplacebo's display-linear convention: 1.0 = SDR reference
     * white (203 nit). In the sRGB FBO branch, GL performs only the OETF;
     * it cannot undo a second luminance normalization. A 100-nit target here
     * would compress even SDR highlights to 100/203 before GL encodes them.
     * Zero means "unknown" to libplacebo, so use its explicit black sentinel
     * to avoid assuming 1000:1 contrast and lifting BT.1886 shadows. HDR/DV
     * still tone maps to this SDR target; these are signal reference values,
     * not a promise about the panel's physical brightness. */
    target.color.hdr.min_luma = PL_COLOR_HDR_BLACK;
    target.color.hdr.max_luma = PL_COLOR_SDR_WHITE;
    if (r->surface_is_srgb) target.color.transfer = PL_COLOR_TRC_LINEAR;
    double aspect = (double)(image.crop.x1 - image.crop.x0) /
                    (double)(image.crop.y1 - image.crop.y0);
    if (frame->sample_aspect_ratio.num > 0 && frame->sample_aspect_ratio.den > 0)
        aspect *= (double)frame->sample_aspect_ratio.num / frame->sample_aspect_ratio.den;
    if (image.rotation & 1) aspect = 1.0 / aspect;
    if (!isfinite(aspect) || !(aspect > 0.0)) aspect = (double)frame->width / frame->height;
    pl_rect2df_aspect_set(&target.crop, (float)aspect, 0.0f);
    pl_frame_clear(r->gl->gpu, &target, (float[3]) {0.0f, 0.0f, 0.0f});
    bool rendered = pl_render_image(r->renderer, &image, &target, &pl_render_default_params);
    bool submitted = pl_swapchain_submit_frame(r->swapchain);
    r->swap_failed = false;
    if (rendered && submitted) pl_swapchain_swap_buffers(r->swapchain);
    pl_unmap_avframe(r->gl->gpu, &image);
    if (!rendered || !submitted || r->swap_failed)
        return fail_gpu(r, error, size, "GPU color presentation failed");
    return true;
}

void pathfin_video_render_destroy(PathfinVideoRender *r)
{
    if (!r) return;
    if (r->context != EGL_NO_CONTEXT)
        eglMakeCurrent(r->display, r->idle_surface, r->idle_surface, r->context);
    drop_window(r);
    if (r->renderer) pl_renderer_destroy(&r->renderer);
    if (r->gl) {
        for (size_t i = 0; i < 4; ++i) pl_tex_destroy(r->gl->gpu, &r->textures[i]);
        pl_opengl_destroy(&r->gl);
    }
    if (r->log) pl_log_destroy(&r->log);
    if (r->gles_library) dlclose(r->gles_library);
    if (r->egl_library) dlclose(r->egl_library);
    if (r->display != EGL_NO_DISPLAY) {
        eglMakeCurrent(r->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (r->idle_surface != EGL_NO_SURFACE) eglDestroySurface(r->display, r->idle_surface);
        if (r->context != EGL_NO_CONTEXT) eglDestroyContext(r->display, r->context);
        if (r->initialized) eglTerminate(r->display);
    }
    free(r);
}
