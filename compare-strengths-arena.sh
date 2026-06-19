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

# run_pgx_match name1 pgx_model1 name2 pgx_model2 [strength] [games] [policy]
# Runs both teams with the pgx value head as MCTS leaf evaluator.
# Pass any non-empty string as 7th arg to also enable the pgx policy head as PUCT prior.
run_pgx_match() {
    local name1=$1 model1=$2 name2=$3 model2=$4 strength=${5:-POWERFUL} games=${6:-1000} policy=${7:-}
    local args=(
        "--name1=$name1" "--strength1=$strength" "--scaling1=FLAT"
        "--name2=$name2" "--strength2=$strength" "--scaling2=FLAT"
        "--mode=RUNS" "--games=$games"
    )
    [[ -n "$model1" ]] && args+=("--pgx-model1=$model1")
    [[ -n "$model2" ]] && args+=("--pgx-model2=$model2")
    [[ -n "$policy" ]] && args+=("--pgx-policy1" "--pgx-policy2")
    build/install/JassTheRipper/bin/JassTheRipperArena "${args[@]}"
}

# Build once upfront.
./gradlew installDist

# ── pgx model arena matches ──────────────────────────────────────────────────
# gen2 vs gen3: expected ~+14 pts/game in the pgx self-arena (gen3 stronger).
# Here both use JTR's DMCTS with the pgx value head as leaf evaluator.
# Models are gitignored; run ./scripts/import_pgx_models.sh to generate them.
PGX_GEN2=src/main/resources/models/pv_gen2_s128/export
PGX_GEN3=src/main/resources/models/pv_gen3_s128/export

# Value head only (leaf scorer); gen-to-gen gains are in policy so expect a wash.
#run_pgx_match "pgx-gen2" "$PGX_GEN2" "pgx-gen3" "$PGX_GEN3" "POWERFUL" 100
# Value + policy head (PUCT prior); this is where gen-to-gen gains should show.
run_pgx_match "pgx-gen2" "$PGX_GEN2" "pgx-gen3" "$PGX_GEN3" "POWERFUL" 100 policy

# ── JTR strength curve (uncomment as needed) ─────────────────────────────────
#run_match "Fast"    "FAST"    "FLAT" "Powerful" "POWERFUL" "FLAT" "RUNS"
#run_match "Strong"  "STRONG"  "FLAT" "Powerful" "POWERFUL" "FLAT" "RUNS"
#run_match "Extreme" "EXTREME" "FLAT" "Powerful" "POWERFUL" "FLAT" "RUNS"
#run_match "Insane"  "INSANE"  "FLAT" "Powerful" "POWERFUL" "FLAT" "RUNS"
#run_match "Superman" "SUPERMAN" "FLAT" "Powerful" "POWERFUL" "FLAT" "RUNS"
#run_match "Ironman"  "IRONMAN"  "FLAT" "Powerful" "POWERFUL" "FLAT" "RUNS"

# ── pgx vs JTR baselines (Step 4 of jass_plan.md; uncomment when ready) ─────
#run_pgx_match "pgx-gen3" "$PGX_GEN3" "Powerful" "" "POWERFUL" 1000
#run_pgx_match "pgx-gen3" "$PGX_GEN3" "Insane"   "" "INSANE"   1000
