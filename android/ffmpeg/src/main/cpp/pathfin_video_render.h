/* Pathfin video presentation bridge. libplacebo/FFmpeg retain LGPL-2.1+ licenses. */
#ifndef PATHFIN_VIDEO_RENDER_H
#define PATHFIN_VIDEO_RENDER_H

#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

struct AVFrame;
struct ANativeWindow;
typedef struct PathfinVideoRender PathfinVideoRender;

/* EGL is initialized lazily. All frame/destroy calls belong to one playback thread.
 * The frame and window are borrowed; this object acquires its own window reference.
 * Error buffers are optional and are always NUL-terminated when their size is > 0. */
PathfinVideoRender *pathfin_video_render_create(void);
bool pathfin_video_render_frame(PathfinVideoRender *render, const struct AVFrame *frame,
                                struct ANativeWindow *window, int rotation,
                                char *error, size_t error_size);
void pathfin_video_render_destroy(PathfinVideoRender *render);

#ifdef __cplusplus
}
#endif
#endif
