#!/bin/bash
# Runs strength-comparison matches against a jass-server already running on localhost.
# Watch progress:
#   tail -f /tmp/jass-*.log
# Watch server-side scores:
#   npm run start:tournament:ortho24  (in jass-server)
set -e
cd "$(dirname "$0")"

run_bot() {
    local name=$1 strength=$2 team=$3 mode=$4 scaling=$5 log=$6 cards_ep=${7:-} ucb=${8:-} puct=${9:-} puct_prior=${10:-}
    local args=("--name=$name" "--strength=$strength" "--team=$team" "--mode=$mode" "--runs-scaling=$scaling" "--quit")
    [[ -n "$cards_ep" ]] && args+=("--cards-estimator=$cards_ep")
    [[ -n "$ucb" ]] && args+=("--ucb=$ucb")
    # puct: pass "1" / "true" to just enable; pass a number to set --puct-alpha=N too.
    if [[ -n "$puct" ]]; then
        args+=("--puct")
        [[ "$puct" =~ ^[0-9]+(\.[0-9]+)?$ ]] && args+=("--puct-alpha=$puct")
        [[ -n "$puct_prior" ]] && args+=("--puct-prior=$puct_prior")
    fi
    build/install/JassTheRipper/bin/JassTheRipper "${args[@]}" > "$log" 2>&1 &
    echo "  started $name ($strength, $mode, $scaling${cards_ep:+, cards=$cards_ep}${ucb:+, ucb=$ucb}${puct:+, puct=$puct${puct_prior:+/$puct_prior}}) team=$team -> $log [pid $!]"
}

run_match() {
    local name1=$1 strength1=$2 scaling1=$3 name2=$4 strength2=$5 scaling2=$6 mode=${7:-RUNS} cards1=${8:-} cards2=${9:-} ucb1=${10:-} ucb2=${11:-} puct1=${12:-} puct2=${13:-} puct_prior1=${14:-} puct_prior2=${15:-}
    local slug1 slug2
    slug1=$(echo "$name1" | tr '[:upper:]' '[:lower:]' | tr -d '.')
    slug2=$(echo "$name2" | tr '[:upper:]' '[:lower:]' | tr -d '.')
    echo "=== $name1 (ucb=${ucb1:-sqrt2}${puct1:+, puct=$puct1${puct_prior1:+/$puct_prior1}}) vs $name2 (ucb=${ucb2:-sqrt2}${puct2:+, puct=$puct2${puct_prior2:+/$puct_prior2}}) [$mode] ==="
    run_bot "$name1" "$strength1" "0" "$mode" "$scaling1" "/tmp/jass-${slug1}-vs-${slug2}-1.log" "$cards1" "$ucb1" "$puct1" "$puct_prior1"
    run_bot "$name1" "$strength1" "0" "$mode" "$scaling1" "/tmp/jass-${slug1}-vs-${slug2}-2.log" "$cards1" "$ucb1" "$puct1" "$puct_prior1"
    run_bot "$name2" "$strength2" "1" "$mode" "$scaling2" "/tmp/jass-${slug2}-vs-${slug1}-1.log" "$cards2" "$ucb2" "$puct2" "$puct_prior2"
    run_bot "$name2" "$strength2" "1" "$mode" "$scaling2" "/tmp/jass-${slug2}-vs-${slug1}-2.log" "$cards2" "$ucb2" "$puct2" "$puct_prior2"
    wait
    echo "=== done: $name1 vs $name2 ==="
    echo
}

# Build once upfront. installDist produces a standalone binary so parallel
# bot processes don't race on the Gradle build directory.
./gradlew installDist

# Strength curve characterization: vs POWERFUL baseline, RUNS mode, FLAT scaling.
# Completed (results in IDEAS.md):
#run_match "Fast"    "FAST"    "FLAT" "Powerful" "POWERFUL" "FLAT" "RUNS"
#run_match "Strong"  "STRONG"  "FLAT" "Powerful" "POWERFUL" "FLAT" "RUNS"
#run_match "Extreme" "EXTREME" "FLAT" "Powerful" "POWERFUL" "FLAT" "RUNS"

# Continuing up the curve. Note: compute scales as factor×numRuns relative to POWERFUL (5×200=1k):
#   INSANE  7×500  = 3500  (~3.5×)
#   SUPERMAN 8×1000 = 8000  (~8×)
#   IRONMAN 9×2000 = 18000 (~18×)
run_match "Insane"   "INSANE"   "FLAT" "Powerful" "POWERFUL" "FLAT" "RUNS"
run_match "Superman" "SUPERMAN" "FLAT" "Powerful" "POWERFUL" "FLAT" "RUNS"
#run_match "Ironman"  "IRONMAN"  "FLAT" "Powerful" "POWERFUL" "FLAT" "RUNS"
