package semantics;

// Original jadxmp fixture: signed overflow, division and a branch-sensitive remainder.
public class Arithmetic {
    public static int mix(int a, int b) {
        int value = a * 31 + b;
        if (value < 0) return value / 7;
        return value % 7;
    }

    public static boolean check() {
        return mix(3, 4) == 6 && mix(-3, 4) == -12
                && mix(Integer.MAX_VALUE, 1) == 0;
    }
}
