package dev.pathfin.smoke;

/** 固定 8101/8102 自制夹具：320x180、方形像素、无旋转、居中 FIT；不观察或搜索像素。 */
final class FixtureVideoGeometry {
    static final int VIDEO_WIDTH = 320;
    static final int VIDEO_HEIGHT = 180;
    private static final int MAX_SURFACE_EDGE = 32768;

    private FixtureVideoGeometry() { }

    /** 返回 Surface buffer 原点坐标 [left, top, right, bottom]，向内取整且保证有界。 */
    static int[] centeredFit(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAX_SURFACE_EDGE || height > MAX_SURFACE_EDGE)
            throw new IllegalArgumentException("合成视频 Surface 尺寸无效");
        int contentWidth;
        int contentHeight;
        if ((long) width * VIDEO_HEIGHT <= (long) height * VIDEO_WIDTH) {
            contentWidth = width;
            contentHeight = Math.max(1, (int) ((long) width * VIDEO_HEIGHT / VIDEO_WIDTH));
        } else {
            contentHeight = height;
            contentWidth = Math.max(1, (int) ((long) height * VIDEO_WIDTH / VIDEO_HEIGHT));
        }
        int left = (width - contentWidth) / 2;
        int top = (height - contentHeight) / 2;
        return new int[]{left, top, left + contentWidth, top + contentHeight};
    }
}
