#!/bin/bash
# Runs strength-comparison matches against a jass-server already running on localhost.
# Watch progress:
#   tail -f /tmp/jass-*.log
# Watch server-side scores:
#   npm run start:tournament:ortho24  (in jass-server)
set -e
cd "$(dirname "$0")"

run_bot() {
    local name=$1 strength=$2 team=$3 mode=$4 scaling=$5 log=$6 cards_ep=${7:-} ucb=${8:-}
    local cards_arg="" ucb_arg=""
    [[ -n "$cards_ep" ]] && cards_arg=",--cards-estimator=$cards_ep"
    [[ -n "$ucb" ]] && ucb_arg=",--ucb=$ucb"
    # < /dev/null: Gradle reads stdin, which suspends a backgrounded process (SIGTTIN) without it.
    ./gradlew run -Pmyargs="--name=$name,--strength=$strength,--team=$team,--mode=$mode,--runs-scaling=$scaling,--quit${cards_arg}${ucb_arg}" < /dev/null > "$log" 2>&1 &
    echo "  started $name ($strength, $mode, $scaling${cards_ep:+, cards=$cards_ep}${ucb:+, ucb=$ucb}) team=$team -> $log [pid $!]"
}

run_match() {
    local name1=$1 strength1=$2 scaling1=$3 name2=$4 strength2=$5 scaling2=$6 mode=${7:-RUNS} cards1=${8:-} cards2=${9:-} ucb1=${10:-} ucb2=${11:-}
    local slug1 slug2
    slug1=$(echo "$name1" | tr '[:upper:]' '[:lower:]' | tr -d '.')
    slug2=$(echo "$name2" | tr '[:upper:]' '[:lower:]' | tr -d '.')
    echo "=== $name1 (ucb=${ucb1:-sqrt2}) vs $name2 (ucb=${ucb2:-sqrt2}) [$mode] ==="
    run_bot "$name1" "$strength1" "0" "$mode" "$scaling1" "/tmp/jass-${slug1}-1.log" "$cards1" "$ucb1"
    run_bot "$name1" "$strength1" "0" "$mode" "$scaling1" "/tmp/jass-${slug1}-2.log" "$cards1" "$ucb1"
    run_bot "$name2" "$strength2" "1" "$mode" "$scaling2" "/tmp/jass-${slug2}-1.log" "$cards2" "$ucb2"
    run_bot "$name2" "$strength2" "1" "$mode" "$scaling2" "/tmp/jass-${slug2}-2.log" "$cards2" "$ucb2"
    wait
    echo "=== done: $name1 vs $name2 ==="
    echo
}

# Build once upfront so parallel gradle runs don't race on compilation.
./gradlew classes

# A/A baseline for tree-stats instrumentation: both teams are vanilla POWERFUL.
# Expected wash on score; the value is the [mcts-stats] log lines (depth, root-visits).
# Extract stats only:
#   grep "\[mcts-stats\]" /tmp/jass-powerful1-1.log | head
run_match "Powerful1" "POWERFUL" "FLAT" "Powerful2" "POWERFUL" "FLAT" "RUNS"
