import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Q3 - Experiments on the hashCode-equals contract (event attendance de-duplication).
 * Every test prints the PREDICTION (written before running) and the OBSERVED size.
 *
 * Compile: javac -d out ContractLab.java
 * Run    : java -cp out ContractLab input/scans.csv
 */
public class ContractLab {

    /** The junior developer's class: equals() on regNo, hashCode() NOT overridden. */
    static class BrokenStudent {
        String regNo; String name;
        BrokenStudent(String regNo, String name) { this.regNo = regNo; this.name = name; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof BrokenStudent)) return false;
            return regNo.equals(((BrokenStudent) o).regNo);
        }
        @Override public String toString() { return regNo + " " + name; }
    }

    /** Corrected class: immutable key field, hashCode consistent with equals. */
    static final class Student {
        private final String regNo; private final String name;
        Student(String regNo, String name) {
            this.regNo = Objects.requireNonNull(regNo); this.name = name;
        }
        String regNo() { return regNo; }
        String name() { return name; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Student s)) return false;
            return regNo.equals(s.regNo);
        }
        @Override public int hashCode() { return regNo.hashCode(); }
        @Override public String toString() { return regNo + " " + name; }
    }

    /** Correct hashCode, but the key field can be changed after insertion. */
    static class MutableStudent {
        String regNo; final String name;
        MutableStudent(String regNo, String name) { this.regNo = regNo; this.name = name; }
        void setRegNo(String r) { regNo = r; }
        @Override public boolean equals(Object o) {
            return o instanceof MutableStudent m && regNo.equals(m.regNo);
        }
        @Override public int hashCode() { return regNo.hashCode(); }
        @Override public String toString() { return regNo + " " + name; }
    }

    /** Bucket index HashMap uses for a key (table size 16): (h ^ h>>>16) & 15. */
    static int bucket(Object o) { int h = o.hashCode(); return (h ^ (h >>> 16)) & 15; }

    static void verdict(String label, int predicted, int observed, String why) {
        System.out.printf("  %-44s predicted %-3d observed %-3d %s%n", label, predicted, observed,
                predicted == observed ? "[prediction confirmed]" : "[prediction WRONG]");
        System.out.println("      reason: " + why);
    }

    public static void main(String[] args) throws IOException {
        Map<String, List<String[]>> tests = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(args.length > 0 ? args[0] : "input/scans.csv"))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split(",");
            tests.computeIfAbsent(p[0], k -> new ArrayList<>()).add(p);
        }

        // ------------------------------------------------ TEST 1
        System.out.println("=== TEST 1: HashSet<BrokenStudent>, re-scans reuse the SAME object ===");
        Set<BrokenStudent> t1 = new HashSet<>();
        Map<String, BrokenStudent> readerCache = new HashMap<>();
        for (String[] p : tests.get("T1")) {
            BrokenStudent s;
            if (p[2].equals("REPEAT-OBJECT")) s = readerCache.get(p[3]);          // same reference again
            else { s = new BrokenStudent(p[2], p[3]); readerCache.put(p[2], s); }
            boolean added = t1.add(s);
            System.out.printf("  %s scan %-22s identityHash=%-10d bucket=%-2d added=%b%n", p[1], s,
                    System.identityHashCode(s), bucket(s), added);
        }
        verdict("4 distinct students, 6 scans", 4, t1.size(),
                "same reference -> same identity hashCode -> same bucket -> equals() true -> rejected");

        // ------------------------------------------------ TEST 2
        System.out.println("\n=== TEST 2: HashSet<BrokenStudent>, every scan is a NEW object ===");
        Set<BrokenStudent> t2 = new HashSet<>();
        for (String[] p : tests.get("T2")) {
            BrokenStudent s = new BrokenStudent(p[2], p[3]);
            boolean added = t2.add(s);
            System.out.printf("  %s scan %-22s identityHash=%-10d bucket=%-2d added=%b%n", p[1], s,
                    System.identityHashCode(s), bucket(s), added);
        }
        verdict("3 distinct students, 6 scans", 6, t2.size(),
                "Object.hashCode() differs per object -> different buckets -> equals() is never even called");
        BrokenStudent probe = new BrokenStudent("192311001", "Arun");
        System.out.println("  contains(new BrokenStudent(192311001)) = " + t2.contains(probe)
                + "   although equals() would say true for 3 elements");
        System.out.println("  a.equals(b) = " + probe.equals(new BrokenStudent("192311001", "Arun"))
                + " but a.hashCode()==b.hashCode() = " + (probe.hashCode() == new BrokenStudent("192311001", "Arun").hashCode())
                + "  -> CONTRACT BROKEN");

        System.out.println("\n    same scans with the CORRECTED Student:");
        Set<Student> t2fixed = new HashSet<>();
        for (String[] p : tests.get("T2")) {
            Student s = new Student(p[2], p[3]);
            System.out.printf("  %s scan %-22s hashCode=%-11d bucket=%-2d added=%b%n", p[1], s, s.hashCode(), bucket(s), t2fixed.add(s));
        }
        verdict("corrected class, 6 scans", 3, t2fixed.size(), "equal regNo -> equal hashCode -> same bucket -> equals() finds it");

        // ------------------------------------------------ TEST 3a: mutate key after insertion
        System.out.println("\n=== TEST 3a (own): regNo corrected AFTER the student was added ===");
        Set<MutableStudent> t3 = new HashSet<>();
        MutableStudent typo = new MutableStudent("192311O01", "Arun");     // letter O typed instead of zero
        t3.add(typo);
        System.out.println("  added " + typo + " in bucket " + bucket(typo));
        typo.setRegNo("192311001");                                       // desk corrects the typo
        System.out.println("  regNo changed to " + typo.regNo + " -> its hash now points to bucket " + bucket(typo)
                + ", but the entry is still stored in the old bucket");
        System.out.println("  contains(same object)               = " + t3.contains(typo));
        System.out.println("  contains(new 192311001)             = " + t3.contains(new MutableStudent("192311001", "Arun")));
        System.out.println("  remove(same object)                 = " + t3.remove(typo));
        t3.add(new MutableStudent("192311001", "Arun"));                  // Arun scans again
        verdict("1 student, corrected regNo, re-scanned", 2, t3.size(),
                "stale entry is unreachable through hashing, so the re-scan is stored as a second copy");
        System.out.println("  set now: " + t3);

        // ------------------------------------------------ TEST 3b: TreeSet inconsistent with equals
        System.out.println("\n=== TEST 3b (own): TreeSet<Student> ordered by NAME (compareTo inconsistent with equals) ===");
        Set<Student> byName = new TreeSet<>(Comparator.comparing(Student::name));
        Set<Student> byNameIgnoreCase = new TreeSet<>(Comparator.comparing(Student::name, String.CASE_INSENSITIVE_ORDER));
        Set<Student> byReg = new TreeSet<>(Comparator.comparing(Student::regNo));
        for (String[] p : tests.get("T3")) {
            Student s = new Student(p[2], p[3]);
            System.out.printf("  %s scan %-20s byName.add=%-5b byReg.add=%b%n", p[1], s, byName.add(s), byReg.add(s));
            byNameIgnoreCase.add(s);
        }
        verdict("TreeSet by name (2 Aruns, Divya twice)", 3, byName.size(),
                "TreeSet uses ONLY compare(); two different students named Arun compare 0 -> one is lost; 'Divya' vs 'DIVYA' differ -> counted twice");
        verdict("TreeSet by name, case-insensitive", 2, byNameIgnoreCase.size(), "now both Divyas merge, but the second Arun is still lost");
        verdict("TreeSet by regNo (consistent with equals)", 3, byReg.size(), "compare()==0 exactly when equals() is true");

        // ------------------------------------------------ verification of the correction
        System.out.println("\n=== Verification of the corrected Student (randomised property test) ===");
        Random rnd = new Random(2026);
        int uniques = 2_000, scans = 50_000, contractViolations = 0;
        Set<Student> hs = new HashSet<>();
        Set<String> truth = new HashSet<>();
        for (int i = 0; i < scans; i++) {
            String reg = String.valueOf(192_310_000 + rnd.nextInt(uniques));
            Student a = new Student(reg, "N" + rnd.nextInt(5)), b = new Student(reg, "X");
            if (a.equals(b) && a.hashCode() != b.hashCode()) contractViolations++;
            hs.add(a);
            truth.add(reg);
        }
        System.out.printf("  %,d scans of %,d possible students: set size %,d, distinct regNos %,d, contract violations %d -> %s%n",
                scans, uniques, hs.size(), truth.size(), contractViolations,
                hs.size() == truth.size() && contractViolations == 0 ? "PASS" : "FAIL");

        // ------------------------------------------------ alternative concept: timing
        System.out.println("\n=== Alternative concept: time per scan and memory for n unique students ===");
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(dataDir().resolve("q3_dedup.csv")))) {
            csv.println("structure,unique_students,scans,run,ns_per_scan");
            System.out.printf("%-34s %14s %14s %14s %16s%n", "structure", "n=10,000", "n=100,000", "n=1,000,000", "bytes/student*");
            String[] names = {"HashSet<Student>", "TreeSet<Student>(by regNo)", "HashMap<String,Student>"};
            int[] sizes = {10_000, 100_000, 1_000_000};
            for (int k = 0; k < 3; k++) {
                System.out.printf("%-34s", names[k]);
                for (int n : sizes) {
                    Student[] stream = scanStream(n, 2 * n, new Random(n));
                    dedup(k, stream);                                     // warm-up
                    double total = 0;
                    int runs = 5;
                    for (int r = 1; r <= runs; r++) {
                        long t0 = System.nanoTime();
                        int size = dedup(k, stream);
                        double ns = (System.nanoTime() - t0) / (double) stream.length;
                        if (size != n) throw new IllegalStateException("wrong size " + size);
                        csv.printf("\"%s\",%d,%d,%d,%.2f%n", names[k], n, stream.length, r, ns);
                        total += ns;
                    }
                    System.out.printf(" %11.1f ns", total / runs);
                }
                System.out.printf(" %13.1f%n", bytesPerStudent(k, 500_000));
            }
        }
        System.out.println("  (each stream has 2n scans: every student scanned twice in random order)");
        System.out.println("  * container overhead only, measured as heap delta after GC for 500,000 students");
        System.out.println("Raw data: data/q3_dedup.csv");
    }

    static Path dataDir() throws IOException { return Files.createDirectories(Path.of("data")); }

    static Student[] scanStream(int n, int scans, Random rnd) {
        Student[] base = new Student[n];
        for (int i = 0; i < n; i++) base[i] = new Student(String.valueOf(190_000_000 + i), "S" + i);
        Student[] s = new Student[scans];
        for (int i = 0; i < scans; i++) {
            Student b = base[i % n];
            s[i] = new Student(b.regNo(), b.name());                   // a new object per scan
        }
        for (int i = scans - 1; i > 0; i--) { int j = rnd.nextInt(i + 1); Student t = s[i]; s[i] = s[j]; s[j] = t; }
        return s;
    }

    static int dedup(int kind, Student[] stream) {
        switch (kind) {
            case 0 -> { Set<Student> set = new HashSet<>(); for (Student s : stream) set.add(s); return set.size(); }
            case 1 -> { Set<Student> set = new TreeSet<>(Comparator.comparing(Student::regNo));
                        for (Student s : stream) set.add(s); return set.size(); }
            default -> { Map<String, Student> m = new HashMap<>(); for (Student s : stream) m.putIfAbsent(s.regNo(), s); return m.size(); }
        }
    }

    static long used() { for (int i = 0; i < 3; i++) System.gc(); Runtime r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory(); }

    static double bytesPerStudent(int kind, int n) {
        Student[] base = new Student[n];
        for (int i = 0; i < n; i++) base[i] = new Student(String.valueOf(190_000_000 + i), "S");
        long before = used();
        Object holder;
        switch (kind) {
            case 0 -> { Set<Student> s = new HashSet<>(); for (Student x : base) s.add(x); holder = s; }
            case 1 -> { Set<Student> s = new TreeSet<>(Comparator.comparing(Student::regNo)); for (Student x : base) s.add(x); holder = s; }
            default -> { Map<String, Student> m = new HashMap<>(); for (Student x : base) m.put(x.regNo(), x); holder = m; }
        }
        long after = used();
        if (holder.hashCode() == 42) System.out.print("");
        return (after - before) / (double) n;
    }
}
