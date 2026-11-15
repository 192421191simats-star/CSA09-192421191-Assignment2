import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Random;

/**
 * Q2 - Music playlist editor: ArrayList vs LinkedList vs plain array (manual shifting),
 * plus ArrayDeque for the "Up Next" queue.
 *
 * Every (structure, operation, size) is run REPS times after a warm-up; each run is
 * written to data/q2_raw.csv and the averages to data/q2_summary.csv.
 *
 * Compile: javac -d out PlaylistBenchmark.java
 * Run    : java -Xms1g -Xmx1g -cp out PlaylistBenchmark input/songs.csv
 */
public class PlaylistBenchmark {

    record Song(int id, String title, String artist, int seconds) { }

    // ------------------------------------------------------------ alternative: plain array
    /** A playlist kept in a plain Song[] with manual shifting and doubling. */
    static final class SongArray {
        private Song[] a = new Song[16];
        private int size;

        void addLast(Song s) { ensure(); a[size++] = s; }
        void insertAt(int i, Song s) {                         // shift right: O(n - i)
            ensure();
            System.arraycopy(a, i, a, i + 1, size - i);
            a[i] = s;
            size++;
        }
        Song removeAt(int i) {                                 // shift left: O(n - i)
            Song s = a[i];
            System.arraycopy(a, i + 1, a, i, size - i - 1);
            a[--size] = null;
            return s;
        }
        Song get(int i) { return a[i]; }                       // O(1)
        int size() { return size; }
        private void ensure() { if (size == a.length) a = Arrays.copyOf(a, a.length * 2); }
    }

    static final int REPS = 7;
    static final int[] SIZES = {1_000, 10_000, 100_000};
    static final int OPS = 1_000;                            // inserts/removes/gets per measurement
    static long sink;

    static Song[] library;

    static Song song(int i) { return library[i % library.length]; }

    // ------------------------------------------------------------ operations
    interface Op { long run(int n, Random rnd); }   // returns number of operations performed

    static List<Integer> dummy;

    @SuppressWarnings("unchecked")
    static List<Song> make(String kind, int n) {
        List<Song> l = kind.equals("ArrayList") ? new ArrayList<>() : new LinkedList<>();
        for (int i = 0; i < n; i++) l.add(song(i));
        return l;
    }

    static SongArray makeArray(int n) {
        SongArray a = new SongArray();
        for (int i = 0; i < n; i++) a.addLast(song(i));
        return a;
    }

    // ------------------------------------------------------------ small timed kernels
    static final java.util.Map<Integer, int[]> KS = new java.util.HashMap<>();
    static List<Song> buildList(String kind, int n) { return make(kind, n); }
    static SongArray buildArray(int n) { return makeArray(n); }
    static Deque<Song> buildDeque(int n) { Deque<Song> d = new ArrayDeque<>(); for (int i = 0; i < n; i++) d.addLast(song(i)); return d; }
    static void frontInsert(List<Song> l) { for (int i = 0; i < OPS; i++) l.add(0, song(i)); }
    static void frontInsert(SongArray a) { for (int i = 0; i < OPS; i++) a.insertAt(0, song(i)); }
    static void frontInsert(Deque<Song> d) { for (int i = 0; i < OPS; i++) d.addFirst(song(i)); }
    static long frontRemove(List<Song> l) { long s = 0; for (int i = 0; i < OPS; i++) s += l.remove(0).id(); return s; }
    static long frontRemove(SongArray a) { long s = 0; for (int i = 0; i < OPS; i++) s += a.removeAt(0).id(); return s; }
    static long frontRemove(Deque<Song> d) { long s = 0; for (int i = 0; i < OPS; i++) s += d.pollFirst().id(); return s; }
    static void midInsert(List<Song> l) { for (int i = 0; i < OPS; i++) l.add(l.size() / 2, song(i)); }
    static void midInsert(SongArray a) { for (int i = 0; i < OPS; i++) a.insertAt(a.size() / 2, song(i)); }
    static long midRemove(List<Song> l) { long s = 0; for (int i = 0; i < OPS; i++) s += l.remove(l.size() / 2).id(); return s; }
    static long midRemove(SongArray a) { long s = 0; for (int i = 0; i < OPS; i++) s += a.removeAt(a.size() / 2).id(); return s; }
    static void iterInsert(List<Song> l) { ListIterator<Song> it = l.listIterator(l.size() / 2); for (int i = 0; i < OPS; i++) it.add(song(i)); }
    static long getK(List<Song> l, int[] ks) { long s = 0; for (int k : ks) s += l.get(k).id(); return s; }
    static long getK(SongArray a, int[] ks) { long s = 0; for (int k : ks) s += a.get(k).id(); return s; }
    static long iterate(List<Song> l) { long s = 0; for (Song x : l) s += x.seconds(); return s; }
    static long iterate(SongArray a) { long s = 0; for (int i = 0; i < a.size(); i++) s += a.get(i).seconds(); return s; }
    static long iterate(Deque<Song> d) { long s = 0; for (Song x : d) s += x.seconds(); return s; }

    /** Measures one operation on one structure; returns ns per operation. */
    static double measure(String struct, String op, int n, Random rnd) {
        // build outside the timed region (except for "add at end", which IS the build)
        List<Song> list = null;
        SongArray arr = null;
        Deque<Song> dq = null;
        if (!op.equals("add at end")) {
            switch (struct) {
                case "Song[] manual" -> arr = makeArray(n);
                case "ArrayDeque" -> { dq = new ArrayDeque<>(); for (int i = 0; i < n; i++) dq.addLast(song(i)); }
                default -> list = make(struct, n);
            }
        }
        // each timed loop lives in its own small method so the JIT compiles every one separately
        long ops;
        long t0 = System.nanoTime();
        switch (op) {
            case "add at end" -> {
                switch (struct) {
                    case "Song[] manual" -> sink += buildArray(n).size();
                    case "ArrayDeque" -> sink += buildDeque(n).size();
                    default -> sink += buildList(struct, n).size();
                }
                ops = n;
            }
            case "insert at front" -> { if (arr != null) frontInsert(arr); else if (dq != null) frontInsert(dq); else frontInsert(list); ops = OPS; }
            case "remove at front" -> { sink += arr != null ? frontRemove(arr) : dq != null ? frontRemove(dq) : frontRemove(list); ops = OPS; }
            case "insert in middle" -> { if (arr != null) midInsert(arr); else midInsert(list); ops = OPS; }
            case "remove in middle" -> { sink += arr != null ? midRemove(arr) : midRemove(list); ops = OPS; }
            case "insert middle via ListIterator" -> { iterInsert(list); ops = OPS; }
            case "get k-th (random k)" -> {
                int[] ks = KS.computeIfAbsent(n, x -> { int[] a = new int[OPS]; for (int i = 0; i < OPS; i++) a[i] = rnd.nextInt(x); return a; });
                t0 = System.nanoTime();                       // index generation not timed
                sink += arr != null ? getK(arr, ks) : getK(list, ks);
                ops = OPS;
            }
            case "iterate whole list" -> { sink += arr != null ? iterate(arr) : dq != null ? iterate(dq) : iterate(list); ops = n; }
            default -> throw new IllegalArgumentException(op);
        }
        long t1 = System.nanoTime();
        return (t1 - t0) / (double) ops;
    }

    // ------------------------------------------------------------ Up Next queue workload
    /** Steady-state queue of size n; ops = 40% addLast, 20% addFirst, 40% pollFirst. */
    static double upNext(String struct, int n, int ops, long seed) {
        Deque<Song> dq = null;
        List<Song> list = null;
        if (struct.equals("ArrayDeque")) { dq = new ArrayDeque<>(); for (int i = 0; i < n; i++) dq.addLast(song(i)); }
        else list = make(struct, n);
        Random rnd = new Random(seed);
        long t0 = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            int r = rnd.nextInt(10);
            Song s = song(i);
            if (dq != null) {
                if (r < 4) dq.addLast(s); else if (r < 6) dq.addFirst(s); else if (!dq.isEmpty()) sink += dq.pollFirst().id();
            } else {
                if (r < 4) list.add(s); else if (r < 6) list.add(0, s); else if (!list.isEmpty()) sink += list.remove(0).id();
            }
        }
        return (System.nanoTime() - t0) / (double) ops;
    }

    // ------------------------------------------------------------ memory per element
    static long usedAfterGc() {
        for (int i = 0; i < 3; i++) System.gc();
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    static double bytesPerElement(String struct, int n) {
        Song s = song(0);                                 // same Song referenced n times: measures the CONTAINER only
        long before = usedAfterGc();
        Object holder;
        switch (struct) {
            case "ArrayList" -> { List<Song> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(s); holder = l; }
            case "LinkedList" -> { List<Song> l = new LinkedList<>(); for (int i = 0; i < n; i++) l.add(s); holder = l; }
            case "ArrayDeque" -> { Deque<Song> d = new ArrayDeque<>(); for (int i = 0; i < n; i++) d.add(s); holder = d; }
            default -> { SongArray a = new SongArray(); for (int i = 0; i < n; i++) a.addLast(s); holder = a; }
        }
        long after = usedAfterGc();
        sink += holder.hashCode() & 1;
        return (after - before) / (double) n;
    }

    public static void main(String[] args) throws IOException {
        // song library from the input file (cycled to reach large playlist sizes)
        List<Song> lib = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(args.length > 0 ? args[0] : "input/songs.csv"))) {
            if (line.isBlank() || line.startsWith("id")) continue;
            String[] p = line.split(",");
            lib.add(new Song(Integer.parseInt(p[0]), p[1], p[2], Integer.parseInt(p[3])));
        }
        library = lib.toArray(new Song[0]);
        Files.createDirectories(Path.of("data"));
        System.out.println("Song library loaded: " + library.length + " songs (cycled to build large playlists)");

        String[] ops = {"add at end", "insert at front", "remove at front", "insert in middle", "remove in middle",
                "insert middle via ListIterator", "get k-th (random k)", "iterate whole list"};
        String[] structs = {"ArrayList", "LinkedList", "Song[] manual", "ArrayDeque"};

        // global JIT warm-up: every kernel runs many times before anything is measured
        System.out.println("Warming up the JIT (every operation x structure, 30 rounds at n=10,000) ...");
        for (int w = 0; w < 30; w++)
            for (String op : ops)
                for (String st : structs) {
                    boolean ok = switch (op) {
                        case "insert in middle", "remove in middle", "get k-th (random k)" -> !st.equals("ArrayDeque");
                        case "insert middle via ListIterator" -> st.equals("LinkedList") || st.equals("ArrayList");
                        default -> true;
                    };
                    if (ok) measure(st, op, 10_000, new Random(w));
                }

        try (PrintWriter raw = new PrintWriter("data/q2_raw.csv");
             PrintWriter sum = new PrintWriter("data/q2_summary.csv")) {
            raw.println("operation,structure,size,run,ns_per_op");
            sum.println("operation,structure,size,mean_ns_per_op,min_ns_per_op");
            for (String op : ops) {
                System.out.printf("%n=== %s (ns per operation, mean of %d runs) ===%n", op, REPS);
                System.out.printf("%-15s", "structure");
                for (int n : SIZES) System.out.printf("%16s", "n=" + String.format("%,d", n));
                System.out.println();
                for (String st : structs) {
                    boolean supported = switch (op) {
                        case "insert in middle", "remove in middle", "get k-th (random k)" -> !st.equals("ArrayDeque");
                        case "insert middle via ListIterator" -> st.equals("LinkedList") || st.equals("ArrayList");
                        default -> true;
                    };
                    System.out.printf("%-15s", st);
                    for (int n : SIZES) {
                        if (!supported) { System.out.printf("%16s", "n/a"); continue; }
                        Random rnd = new Random(42);
                        for (int w = 0; w < 5; w++) measure(st, op, n, rnd);        // warm-up
                        double total = 0, min = Double.MAX_VALUE;
                        for (int r = 1; r <= REPS; r++) {
                            double v = measure(st, op, n, rnd);
                            raw.printf("%s,%s,%d,%d,%.2f%n", op, st, n, r, v);
                            total += v; min = Math.min(min, v);
                        }
                        sum.printf("%s,%s,%d,%.2f,%.2f%n", op, st, n, total / REPS, min);
                        System.out.printf("%16.1f", total / REPS);
                    }
                    System.out.println();
                }
            }
        }

        // ---------------- Up Next queue (changed condition) ----------------
        try (PrintWriter csv = new PrintWriter("data/q2_upnext.csv")) {
            csv.println("structure,size,run,ns_per_op");
            int qops = 200_000;
            System.out.printf("%n=== Up Next queue: %,d mixed ops (40%% addLast, 20%% addFirst, 40%% pollFirst), ns/op ===%n", qops);
            System.out.printf("%-15s", "structure");
            for (int n : SIZES) System.out.printf("%16s", "n=" + String.format("%,d", n));
            System.out.println();
            for (String st : new String[]{"ArrayList", "LinkedList", "ArrayDeque"}) {
                System.out.printf("%-15s", st);
                for (int n : SIZES) {
                    upNext(st, n, qops / 10, 1);                                   // warm-up
                    double total = 0;
                    for (int r = 1; r <= REPS; r++) {
                        double v = upNext(st, n, qops, r);
                        csv.printf("%s,%d,%d,%.2f%n", st, n, r, v);
                        total += v;
                    }
                    System.out.printf("%16.1f", total / REPS);
                }
                System.out.println();
            }
        }

        // ---------------- memory per element ----------------
        System.out.println("\n=== Container memory per element (n = 1,000,000 references, heap delta after GC) ===");
        try (PrintWriter csv = new PrintWriter("data/q2_memory.csv")) {
            csv.println("structure,bytes_per_element");
            for (String st : structs) {
                bytesPerElement(st, 1_000_000);                                     // warm-up
                double b = bytesPerElement(st, 1_000_000);
                csv.printf("%s,%.2f%n", st, b);
                System.out.printf("%-15s %6.1f bytes/element%n", st, b);
            }
        }
        System.out.println("\nRaw data: data/q2_raw.csv, data/q2_upnext.csv, data/q2_memory.csv  (checksum " + (sink & 0xff) + ")");
    }
}
