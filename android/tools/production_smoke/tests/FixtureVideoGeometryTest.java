package dev.pathfin.smoke;

import java.util.Arrays;
import java.util.Random;

/** 纯 JDK 几何回归；测试生产 smoke 直接调用的同一 helper，无 Android/Gradle 依赖。 */
public final class FixtureVideoGeometryTest {
    public static void main(String[] args) {
        check(1280, 2772, new int[]{0, 1026, 1280, 1746});
        check(2772, 1280, new int[]{248, 0, 2523, 1280});
        check(320, 180, new int[]{0, 0, 320, 180});
        check(1920, 1080, new int[]{0, 0, 1920, 1080});
        check(1080, 1920, new int[]{0, 656, 1080, 1263});
        check(2560, 1080, new int[]{320, 0, 2240, 1080});
        check(1001, 1001, new int[]{0, 219, 1001, 782});
        Random random = new Random(81018102L);
        for (int i = 0; i < 10000; i++) {
            int width = 1 + random.nextInt(32768), height = 1 + random.nextInt(32768);
            int[] rect = FixtureVideoGeometry.centeredFit(width, height);
            int contentWidth = rect[2] - rect[0], contentHeight = rect[3] - rect[1];
            require(rect[0] >= 0 && rect[1] >= 0 && rect[2] <= width && rect[3] <= height,
                    "source rect 必须在 buffer 内");
            require(contentWidth > 0 && contentHeight > 0, "source rect 必须非空");
            require(contentWidth == width || contentHeight == height, "FIT 必须使用至少一条完整边");
            require(Math.abs(rect[0] - (width - rect[2])) <= 1
                    && Math.abs(rect[1] - (height - rect[3])) <= 1, "必须居中且取整误差至多一像素");
            require(Math.abs((long) contentWidth * 180 - (long) contentHeight * 320) <= 320,
                    "比例误差仅允许整数像素取整");
        }
        for (int[] invalid : new int[][]{{0, 1080}, {1920, 0}, {-1, 2}, {32769, 1080}, {1, Integer.MAX_VALUE}}) {
            try { FixtureVideoGeometry.centeredFit(invalid[0], invalid[1]); }
            catch (IllegalArgumentException expected) { continue; }
            throw new AssertionError("非法几何未拒绝");
        }
        System.out.println("PASS 7 fixed portrait/landscape/square cases + 10000 bounded geometry cases + 5 invalid sizes");
    }

    private static void check(int width, int height, int[] expected) {
        require(Arrays.equals(FixtureVideoGeometry.centeredFit(width, height), expected),
                "固定几何不匹配 " + width + "x" + height);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
