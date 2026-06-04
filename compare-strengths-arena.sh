#!/bin/bash
# Runs strength-comparison matches in-process using Arena (no server needed).
# Output goes to stdout; stats (paired t-test, sign test) printed at end of each match.
set -e
cd "$(dirname "$0")"

run_match() {
    local name1=$1 strength1=$2 scaling1=$3 name2=$4 strength2=$5 scaling2=$6 mode=${7:-RUNS} games=${8:-10240} ucb1=${9:-} ucb2=${10:-} puct1=${11:-} puct2=${12:-} puct_prior1=${13:-} puct_prior2=${14:-}
    local args=(
        "--name1=$name1" "--strength1=$strength1" "--scaling1=$scaling1"
        "--name2=$name2" "--strength2=$strength2" "--scaling2=$scaling2"
        "--mode=$mode" "--games=$games"
    )
    [[ -n "$ucb1" ]] && args+=("--ucb1=$ucb1")
    [[ -n "$ucb2" ]] && args+=("--ucb2=$ucb2")
    if [[ -n "$puct1" ]]; then
        args+=("--puct1")
        [[ "$puct1" =~ ^[0-9]+(\.[0-9]+)?$ ]] && args+=("--puct-alpha1=$puct1")
        [[ -n "$puct_prior1" ]] && args+=("--puct-prior1=$puct_prior1")
    fi
    if [[ -n "$puct2" ]]; then
        args+=("--puct2")
        [[ "$puct2" =~ ^[0-9]+(\.[0-9]+)?$ ]] && args+=("--puct-alpha2=$puct2")
        [[ -n "$puct_prior2" ]] && args+=("--puct-prior2=$puct_prior2")
    fi
    build/install/JassTheRipper/bin/JassTheRipperArena "${args[@]}"
}

# Build once upfront.
./gradlew installDist

# Strength curve: edit the matches below as needed.
#run_match "Fast"    "FAST"    "FLAT" "Powerful" "POWERFUL" "FLAT" "RUNS"
#run_match "Strong"  "STRONG"  "FLAT" "Powerful" "POWERFUL" "FLAT" "RUNS"
#run_match "Extreme" "EXTREME" "FLAT" "Powerful" "POWERFUL" "FLAT" "RUNS"
#run_match "Insane"  "INSANE"  "FLAT" "Powerful" "POWERFUL" "FLAT" "RUNS"
#run_match "Superman" "SUPERMAN" "FLAT" "Powerful" "POWERFUL" "FLAT" "RUNS"
#run_match "Ironman"  "IRONMAN"  "FLAT" "Powerful" "POWERFUL" "FLAT" "RUNS"
