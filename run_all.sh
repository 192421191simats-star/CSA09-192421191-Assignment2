#!/usr/bin/env bash
# Compiles and runs every program of Assignment 2. Each console output is saved to
# QN/output/<program>.txt (with the commands at the top); raw measurements go to QN/data/*.csv.
# Usage: bash run_all.sh            (from the repository root; takes about 8-10 minutes)
#        bash run_all.sh Q3 Q5      (only the listed questions)
set -u
ROOT="$(cd "$(dirname "$0")" && pwd)"
unset JAVA_TOOL_OPTIONS 2>/dev/null || true
ONLY="${*:-Q1 Q2 Q3 Q4 Q5 Q6 Q7}"

run() {   # run <folder> <output-name> <compile> <run>
  case " $ONLY " in *" $1 "*) ;; *) return ;; esac
  cd "$ROOT/$1" || exit 1
  mkdir -p output
  { echo "\$ cd $1"; echo "\$ $3"; eval "$3" 2>&1; echo "\$ $4"; eval "$4" 2>&1; } > "output/$2.txt"
  echo "  $1/output/$2.txt"
}

echo "Java: $(java -version 2>&1 | head -1)"
run Q1 TicketBenchmark   "javac -d out TicketBenchmark.java"  "java -Xms512m -Xmx512m -cp out TicketBenchmark input/workloads.txt"
run Q1 SharedLogDemo     "javac -d out SharedLogDemo.java"    "java -cp out SharedLogDemo 4 50000 10"
run Q2 PlaylistBenchmark "javac -d out PlaylistBenchmark.java" "java -Xms1g -Xmx1g -cp out PlaylistBenchmark input/songs.csv"
run Q3 Attendance_before_TODO "javac -d out_before before_todo/Attendance.java" "java -cp out_before Attendance"
run Q3 Attendance_after_TODO  "javac -d out Attendance.java"  "java -cp out Attendance"
run Q3 ContractLab       "javac -d out ContractLab.java"      "java -Xms1g -Xmx1g -cp out ContractLab input/scans.csv"
run Q4 PharmacyInventory "javac -d out PharmacyInventory.java" "java -Xms1g -Xmx1g -cp out PharmacyInventory input/medicines_sample.csv"
run Q5 LeaderboardDemo   "javac -d out LeaderboardDemo.java"  "java -cp out LeaderboardDemo"
run Q5 RankingLab        "javac -d out RankingLab.java"       "java -Xms1g -Xmx1g -cp out RankingLab input/teams_ties.csv input/expected_ties.txt input/teams_adversarial.csv input/expected_adversarial.txt"
run Q6 Triage            "javac -d out Triage.java"           "java -cp out Triage"
run Q6 TriageLab         "javac -d out TriageLab.java"        "java -Xms1g -Xmx1g -cp out TriageLab input/patients_8.csv input/expected_8.txt"
run Q7 LeaderboardRedesign "javac -d out LeaderboardRedesign.java" "java -Xms2g -Xmx2g -cp out LeaderboardRedesign input/experiment.txt"
echo "Done. Graphs: python3 tools/make_graphs.py   Screenshots: python3 tools/render_screenshots.py"
