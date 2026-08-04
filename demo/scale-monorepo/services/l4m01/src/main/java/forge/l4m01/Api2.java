package forge.l4m01;

import java.util.ArrayList;
import java.util.List;

/** Generated scale-fixture source. Real code so javac does real work. */
public final class Api2 {

    public static String describe() {
        List<String> parts = new ArrayList<>();
        parts.add("l4m01#2");
        return String.join(",", parts);
    }

    public long compute0(long seed) {
        long acc = seed ^ 0L;
        for (int i = 0; i < 17; i++) {
            acc = (acc * 31 + i) ^ (acc >>> 7);
        }
        return acc;
    }

    public long compute1(long seed) {
        long acc = seed ^ 427799L;
        for (int i = 0; i < 17; i++) {
            acc = (acc * 31 + i) ^ (acc >>> 7);
        }
        return acc;
    }

    public long compute2(long seed) {
        long acc = seed ^ 855598L;
        for (int i = 0; i < 17; i++) {
            acc = (acc * 31 + i) ^ (acc >>> 7);
        }
        return acc;
    }

    public long compute3(long seed) {
        long acc = seed ^ 283394L;
        for (int i = 0; i < 17; i++) {
            acc = (acc * 31 + i) ^ (acc >>> 7);
        }
        return acc;
    }

    public long compute4(long seed) {
        long acc = seed ^ 711193L;
        for (int i = 0; i < 17; i++) {
            acc = (acc * 31 + i) ^ (acc >>> 7);
        }
        return acc;
    }

    public long compute5(long seed) {
        long acc = seed ^ 138989L;
        for (int i = 0; i < 17; i++) {
            acc = (acc * 31 + i) ^ (acc >>> 7);
        }
        return acc;
    }
}
