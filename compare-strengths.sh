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
    local cards_arg="" ucb_arg="" puct_arg=""
    [[ -n "$cards_ep" ]] && cards_arg=",--cards-estimator=$cards_ep"
    [[ -n "$ucb" ]] && ucb_arg=",--ucb=$ucb"
    # puct: pass "1" / "true" to just enable; pass a number to set --puct-alpha=N too.
    if [[ -n "$puct" ]]; then
        puct_arg=",--puct"
        if [[ "$puct" =~ ^[0-9]+(\.[0-9]+)?$ ]]; then
            puct_arg="${puct_arg},--puct-alpha=$puct"
        fi
        [[ -n "$puct_prior" ]] && puct_arg="${puct_arg},--puct-prior=$puct_prior"
    fi
    # < /dev/null: Gradle reads stdin, which suspends a backgrounded process (SIGTTIN) without it.
    ./gradlew run -Pmyargs="--name=$name,--strength=$strength,--team=$team,--mode=$mode,--runs-scaling=$scaling,--quit${cards_arg}${ucb_arg}${puct_arg}" < /dev/null > "$log" 2>&1 &
    echo "  started $name ($strength, $mode, $scaling${cards_ep:+, cards=$cards_ep}${ucb:+, ucb=$ucb}${puct:+, puct=$puct${puct_prior:+/$puct_prior}}) team=$team -> $log [pid $!]"
}

run_match() {
    local name1=$1 strength1=$2 scaling1=$3 name2=$4 strength2=$5 scaling2=$6 mode=${7:-RUNS} cards1=${8:-} cards2=${9:-} ucb1=${10:-} ucb2=${11:-} puct1=${12:-} puct2=${13:-} puct_prior1=${14:-} puct_prior2=${15:-}
    local slug1 slug2
    slug1=$(echo "$name1" | tr '[:upper:]' '[:lower:]' | tr -d '.')
    slug2=$(echo "$name2" | tr '[:upper:]' '[:lower:]' | tr -d '.')
    echo "=== $name1 (ucb=${ucb1:-sqrt2}${puct1:+, puct=$puct1${puct_prior1:+/$puct_prior1}}) vs $name2 (ucb=${ucb2:-sqrt2}${puct2:+, puct=$puct2${puct_prior2:+/$puct_prior2}}) [$mode] ==="
    run_bot "$name1" "$strength1" "0" "$mode" "$scaling1" "/tmp/jass-${slug1}-1.log" "$cards1" "$ucb1" "$puct1" "$puct_prior1"
    run_bot "$name1" "$strength1" "0" "$mode" "$scaling1" "/tmp/jass-${slug1}-2.log" "$cards1" "$ucb1" "$puct1" "$puct_prior1"
    run_bot "$name2" "$strength2" "1" "$mode" "$scaling2" "/tmp/jass-${slug2}-1.log" "$cards2" "$ucb2" "$puct2" "$puct_prior2"
    run_bot "$name2" "$strength2" "1" "$mode" "$scaling2" "/tmp/jass-${slug2}-2.log" "$cards2" "$ucb2" "$puct2" "$puct_prior2"
    wait
    echo "=== done: $name1 vs $name2 ==="
    echo
}

# Build once upfront so parallel gradle runs don't race on compilation.
./gradlew classes

# Alpha sweep with HeavyJassPlayoutSelectionPolicy as PUCT prior (now Application's default).
# Comparison to the Light sweep (alpha=0 wash, 0.1-0.5 plateau at ~-8 pts/game, 0.7 at -30 pts/game)
# tests whether heuristic quality changes the curve, or whether the harm is structural (non-uniform
# prior amplified by Q-sum aggregation regardless of which heuristic).
for alpha in 0.7; do
    run_match "PuctH${alpha}" "POWERFUL" "FLAT" "Powerful" "POWERFUL" "FLAT" "RUNS" "" "" "" "" "$alpha" ""
done
