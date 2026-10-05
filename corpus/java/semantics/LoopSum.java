package semantics;

// Original jadxmp fixture: loop-carried values and the zero-iteration edge.
public class LoopSum {
    public static int sum(int limit) {
        int total = 0;
        for (int i = 0; i < limit; i++) total += i;
        return total;
    }

    public static void check() {
        if (sum(0) != 0 || sum(1) != 0 || sum(10) != 45) throw new AssertionError();
    }
}
