package semantics;

// Original jadxmp fixture: source-level ordering across signed zero, NaN, and infinities.
public class FloatingComparisons {
    public static int order(double a, double b) {
        if (a < b) return -1;
        if (a == b) return 0;
        return 1;
    }

    public static boolean check() {
        return order(-0.0, 0.0) == 0
                && order(0.0, -0.0) == 0
                && order(Double.NaN, 1.0) == 1
                && order(Double.NaN, Double.NaN) == 1
                && order(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY) == -1;
    }
}
