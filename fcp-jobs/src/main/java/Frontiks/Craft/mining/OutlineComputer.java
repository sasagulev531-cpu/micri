package Frontiks.Craft.mining;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Чистый расчёт контура узла: рёбра тех граней куба, которые открыты к воздуху.
 * Координаты безразмерные в [0,1] (от угла блока до угла блока) —
 * смещение в мировые координаты делает вызывающая сторона.
 * Отделено от Bukkit для unit-тестов.
 */
public final class OutlineComputer {

    private OutlineComputer() {}

    /** Смещения соседних блоков для граней в порядке +x, -x, +y, -y, +z, -z. */
    public static final int[][] FACE_OFFSETS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    // 8 углов куба, индекс: bit0=x, bit1=y, bit2=z
    private static final double[][] CORNERS = new double[8][3];
    static {
        for (int c = 0; c < 8; c++) {
            CORNERS[c] = new double[]{c & 1, (c >> 1) & 1, (c >> 2) & 1};
        }
    }

    // Углы каждой грани в циклическом порядке (порядок по часовой при взгляде снаружи)
    private static final int[][] FACE_CORNERS = {
            {1, 3, 7, 5}, // +x
            {0, 4, 6, 2}, // -x
            {2, 3, 7, 6}, // +y
            {0, 1, 5, 4}, // -y
            {4, 5, 7, 6}, // +z
            {0, 1, 3, 2}, // -z
    };

    /**
     * Считает точки контура.
     * @param faceOpen   true/false для граней в порядке +x,-x,+y,-y,+z,-z
     * @param pointsPerEdge сколько точек рисовать ВДОЛЬ каждого ребра (без углов); >=1
     * @return список рёбер; каждое ребро — массив из pointsPerEdge точек (double[3]);
     *         общие рёбра смежных граней входят один раз
     */
    public static List<double[][]> computeOutlinePoints(boolean[] faceOpen, int pointsPerEdge) {
        int ppe = Math.max(1, pointsPerEdge);
        Set<Long> uniqueEdges = new LinkedHashSet<>();
        for (int face = 0; face < 6; face++) {
            if (face >= faceOpen.length || !faceOpen[face]) continue;
            int[] corners = FACE_CORNERS[face];
            for (int i = 0; i < 4; i++) {
                int a = corners[i];
                int b = corners[(i + 1) % 4];
                uniqueEdges.add(edgeKey(a, b));
            }
        }
        List<double[][]> result = new ArrayList<>(uniqueEdges.size());
        for (long key : uniqueEdges) {
            int a = (int) (key / 8);
            int b = (int) (key % 8);
            double[][] points = new double[ppe][3];
            double[] ca = CORNERS[a];
            double[] cb = CORNERS[b];
            for (int i = 0; i < ppe; i++) {
                double t = (i + 1.0) / (ppe + 1.0);
                points[i][0] = ca[0] + (cb[0] - ca[0]) * t;
                points[i][1] = ca[1] + (cb[1] - ca[1]) * t;
                points[i][2] = ca[2] + (cb[2] - ca[2]) * t;
            }
            result.add(points);
        }
        return result;
    }

    /** Количество уникальных открытых рёбер (удобно для тестов). */
    public static int edgeCount(boolean[] faceOpen) {
        return computeOutlinePoints(faceOpen, 1).size();
    }

    private static long edgeKey(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return lo * 8L + hi;
    }
}
