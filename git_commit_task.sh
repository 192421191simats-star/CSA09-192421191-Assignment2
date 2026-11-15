#!/usr/bin/env bash
# Helper for committing the assignment progressively (at least one commit per task).
#
# First time:   bash git_commit_task.sh init https://github.com/<user>/CSA09-<RegNo>-Assignment2.git
# Each task:    bash git_commit_task.sh 1        (commits Q1 and pushes)
#               bash git_commit_task.sh 2        ... up to 7, ideally on different days
# Report:       bash git_commit_task.sh report
set -e
cd "$(dirname "$0")"

case "${1:-}" in
  init)
    [ -n "${2:-}" ] || { echo "usage: bash git_commit_task.sh init <repo-url>"; exit 1; }
    git init -b main
    git add README.md .gitignore run_all.sh git_commit_task.sh tools
    git commit -m "Set up repository: README, run script and tools"
    git remote add origin "$2"
    git push -u origin main
    ;;
  report)
    git add report README.md
    git commit -m "Add assignment report with predictions, measurements, graphs and complexity analysis"
    git push
    ;;
  [1-7])
    dir="Q$1"
    git add "$dir"
    git commit -m "$dir: source, input files, raw data, outputs, graphs and screenshots"
    git push
    ;;
  *)
    echo "usage: bash git_commit_task.sh init <repo-url> | 1..7 | report"; exit 1 ;;
esac
