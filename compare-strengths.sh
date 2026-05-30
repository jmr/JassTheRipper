#!/bin/bash
# Runs strength-comparison matches against a jass-server already running on localhost.
# Watch progress:
#   tail -f /tmp/jass-*.log
# Watch server-side scores:
#   npm run start:tournament:ortho24  (in jass-server)
set -e
cd "$(dirname "$0")"

run_bot() {
    local name=$1 strength=$2 team=$3 mode=$4 scaling=$5 log=$6 cards_ep=${7:-}
    local cards_arg=""
    [[ -n "$cards_ep" ]] && cards_arg=",--cards-estimator=$cards_ep"
    # < /dev/null: Gradle reads stdin, which suspends a backgrounded process (SIGTTIN) without it.
    ./gradlew run -Pmyargs="--name=$name,--strength=$strength,--team=$team,--mode=$mode,--runs-scaling=$scaling,--quit${cards_arg}" < /dev/null > "$log" 2>&1 &
    echo "  started $name ($strength, $mode, $scaling${cards_ep:+, cards=$cards_ep}) team=$team -> $log [pid $!]"
}

run_match() {
    local name1=$1 strength1=$2 scaling1=$3 name2=$4 strength2=$5 scaling2=$6 mode=${7:-RUNS} cards1=${8:-} cards2=${9:-}
    local slug1 slug2
    slug1=$(echo "$name1" | tr '[:upper:]' '[:lower:]')
    slug2=$(echo "$name2" | tr '[:upper:]' '[:lower:]')
    echo "=== $name1 ($scaling1) vs $name2 ($scaling2) [$mode] ==="
    run_bot "$name1" "$strength1" "0" "$mode" "$scaling1" "/tmp/jass-${slug1}-1.log" "$cards1"
    run_bot "$name1" "$strength1" "0" "$mode" "$scaling1" "/tmp/jass-${slug1}-2.log" "$cards1"
    run_bot "$name2" "$strength2" "1" "$mode" "$scaling2" "/tmp/jass-${slug2}-1.log" "$cards2"
    run_bot "$name2" "$strength2" "1" "$mode" "$scaling2" "/tmp/jass-${slug2}-2.log" "$cards2"
    wait
    echo "=== done: $name1 vs $name2 ==="
    echo
}

# Build once upfront so parallel gradle runs don't race on compilation.
./gradlew classes

# POWERFUL+CardsEstimator vs plain POWERFUL: does the card prediction network improve determinization quality?
run_match "PowerfulCards" "POWERFUL" "FLAT" "Powerful" "POWERFUL" "FLAT" "RUNS" "0" ""
