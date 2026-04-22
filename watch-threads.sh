#!/usr/bin/env bash
# =============================================================================
# watch-threads.sh — Live thread monitor for loom-benchmark
#
# Polls GET /api/threads/snapshot every second and renders a live table
# showing platform vs virtual thread counts and their breakdown by state.
#
# Run alongside a load test to observe the fundamental difference:
#
#   Classic load  → platform.TIMED_WAITING fills up to poolSize (20), then stalls
#   Loom load     → virtual.TIMED_WAITING spikes into the hundreds/thousands
#                   while platform counts barely move
#
# Usage:
#   chmod +x watch-threads.sh
#   ./watch-threads.sh
#
#   # With options:
#   HOST=http://localhost:8090 INTERVAL=0.5 ./watch-threads.sh
#
# Requirements:
#   - curl
#   - python3  (for JSON parsing; jq used as fallback if available)
#   - A terminal that supports ANSI escape codes (any modern terminal)
#
# Keyboard shortcuts:
#   Ctrl+C  — exit cleanly
#   r       — reset peak markers (not implemented here; use Ctrl+C and restart)
# =============================================================================

HOST="${HOST:-http://localhost:8080}"
INTERVAL="${INTERVAL:-1}"
ENDPOINT="${HOST}/api/threads/snapshot"

# ---- ANSI colour codes -------------------------------------------------------
BOLD='\033[1m'
DIM='\033[2m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
BLUE='\033[0;34m'
WHITE='\033[1;37m'
RESET='\033[0m'

# Box-drawing characters (UTF-8)
TL='╔' TR='╗' BL='╚' BR='╝'
ML='╠' MR='╣' MC='╦' MB='╩' MX='╬'
H='═' V='║' HT='╤' HB='╧' VL='├' VR='┤' VC='┼'

# ---- State to determine if python3 or jq is available -----------------------
HAS_PYTHON3=false
HAS_JQ=false
command -v python3 &>/dev/null && HAS_PYTHON3=true
command -v jq      &>/dev/null && HAS_JQ=true

if ! $HAS_PYTHON3 && ! $HAS_JQ; then
    echo "ERROR: Neither python3 nor jq found. Install one to parse JSON."
    exit 1
fi

# ---- JSON field extractor ---------------------------------------------------
# Usage: json_get <json_string> <dotted.key.path>
# e.g.:  json_get "$snap" platform.total
json_get() {
    local json="$1"
    local path="$2"

    if $HAS_PYTHON3; then
        # Build a python attribute-access chain from "a.b.c" → d["a"]["b"]["c"]
        local py_path
        py_path=$(echo "$path" | sed "s/\([^.]*\)/['\1']/g")
        echo "$json" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    val = d${py_path}
    print(val)
except Exception:
    print(0)
" 2>/dev/null
    else
        # jq fallback
        echo "$json" | jq -r ".${path} // 0" 2>/dev/null
    fi
}

# Reads a state count from the byState sub-object.
# Usage: state_count <json_string> <"platform"|"virtual"> <STATE_NAME>
state_count() {
    local json="$1"
    local section="$2"
    local state="$3"

    if $HAS_PYTHON3; then
        echo "$json" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    print(d['${section}']['byState'].get('${state}', 0))
except Exception:
    print(0)
" 2>/dev/null
    else
        echo "$json" | jq -r ".${section}.byState.${state} // 0" 2>/dev/null
    fi
}

# ---- Bar renderer -----------------------------------------------------------
# Renders a horizontal bar: [████████░░░░░░░░] value/max
# Usage: bar <value> <max> <width> <fill_colour>
bar() {
    local val="${1:-0}"
    local max="${2:-1}"
    local width="${3:-20}"
    local colour="${4:-$GREEN}"

    [[ "$max" -le 0 ]] && max=1
    local filled=$(( val * width / max ))
    [[ $filled -gt $width ]] && filled=$width
    local empty=$(( width - filled ))

    printf "${colour}"
    printf '%0.s█' $(seq 1 $filled) 2>/dev/null || printf '%'"$filled"'s' | tr ' ' '█'
    printf "${DIM}"
    printf '%0.s░' $(seq 1 $empty) 2>/dev/null  || printf '%'"$empty"'s'  | tr ' ' '░'
    printf "${RESET}"
}

# ---- Delta arrow ------------------------------------------------------------
# Prints ▲ in green if new > old, ▼ in red if new < old, · in dim if equal.
arrow() {
    local old="${1:-0}"
    local new="${2:-0}"
    if   [[ "$new" -gt "$old" ]]; then printf "${GREEN}▲${RESET}"
    elif [[ "$new" -lt "$old" ]]; then printf "${RED}▼${RESET}"
    else printf "${DIM}·${RESET}"
    fi
}

# ---- State row printer -------------------------------------------------------
# Prints one row of the state breakdown table.
# Usage: state_row <label> <platform_count> <virtual_count> <bar_max>
state_row() {
    local label="$1"
    local p_count="${2:-0}"
    local v_count="${3:-0}"
    local bar_max="${4:-1}"
    local p_colour="${5:-$CYAN}"
    local v_colour="${6:-$MAGENTA}"

    printf "  ${BOLD}%-16s${RESET}" "$label"
    printf "  %5d  " "$p_count"
    bar "$p_count" "$bar_max" 12 "$p_colour"
    printf "     %5d  " "$v_count"
    bar "$v_count" "$bar_max" 12 "$v_colour"
    printf "\n"
}

# ---- Previous values (for delta arrows) ------------------------------------
prev_p_total=0
prev_v_total=0
prev_p_runnable=0
prev_v_runnable=0
prev_p_timed_wait=0
prev_v_timed_wait=0

# ---- Tick counter -----------------------------------------------------------
tick=0
errors=0

# ---- Clean exit on Ctrl+C ---------------------------------------------------
trap 'printf "\n${RESET}${DIM}Stopped.${RESET}\n"; exit 0' INT TERM

# =============================================================================
# Main poll loop
# =============================================================================
while true; do
    tick=$(( tick + 1 ))

    # Fetch snapshot (2-second timeout so slow responses don't stall the display)
    SNAP=$(curl -sf --max-time 2 "$ENDPOINT" 2>/dev/null)

    if [[ -z "$SNAP" ]]; then
        errors=$(( errors + 1 ))
        clear
        printf "${RED}${BOLD}  ✗ No response from ${ENDPOINT}${RESET}\n"
        printf "${DIM}  Is the app running?  (errors: ${errors}, tick: ${tick})${RESET}\n\n"
        printf "  ${YELLOW}Start the app with:${RESET}\n"
        printf "    mvn spring-boot:run\n"
        printf "  ${YELLOW}Or JAR:${RESET}\n"
        printf "    java -jar target/loom-benchmark-0.0.1-SNAPSHOT.jar\n"
        sleep "$INTERVAL"
        continue
    fi

    errors=0

    # Parse all fields up front
    TIMESTAMP=$(json_get "$SNAP" "timestamp")
    P_TOTAL=$(json_get "$SNAP" "platform.total")
    P_DAEMON=$(json_get "$SNAP" "platform.daemon")
    P_NONDAEMON=$(json_get "$SNAP" "platform.nonDaemon")
    P_PEAK=$(json_get "$SNAP" "platform.peak")
    P_STARTED=$(json_get "$SNAP" "platform.totalStartedEver")
    V_TOTAL=$(json_get "$SNAP" "virtual.active")
    GRAND=$(json_get "$SNAP" "grandTotal")

    P_RUNNABLE=$(state_count   "$SNAP" "platform" "RUNNABLE")
    P_WAITING=$(state_count    "$SNAP" "platform" "WAITING")
    P_TIMED=$(state_count      "$SNAP" "platform" "TIMED_WAITING")
    P_BLOCKED=$(state_count    "$SNAP" "platform" "BLOCKED")

    V_STARTED=$(json_get   "$SNAP" "virtual.startedEver")
    V_COMPLETED=$(json_get "$SNAP" "virtual.completedEver")

    # Compute bar scale: use the larger of platform total and virtual total,
    # with a minimum of 20 so the bars don't look absurdly full when counts are low.
    BAR_MAX=$(( P_TOTAL > V_TOTAL ? P_TOTAL : V_TOTAL ))
    [[ $BAR_MAX -lt 20 ]] && BAR_MAX=20

    # --------------------------------------------------------------------------
    clear
    W=70   # table width

    # ---- Header --------------------------------------------------------------
    printf "${CYAN}${BOLD}"
    printf '╔'; printf '%0.s═' $(seq 1 $((W-2))); printf '╗\n'
    printf "║${RESET}${WHITE}${BOLD}  loom-benchmark :: Live Thread Monitor${RESET}${CYAN}${BOLD}"
    printf "%$(( W - 41 ))s║\n" ""
    printf "║${RESET}  ${DIM}${TIMESTAMP}   tick=${tick}${RESET}${CYAN}${BOLD}"
    printf "%$(( W - 2 - 11 - ${#TIMESTAMP} - ${#tick} ))s║\n" ""
    printf '╠'; printf '%0.s═' $(seq 1 $((W-2))); printf '╣\n'
    printf "${RESET}"

    # ---- Summary row ---------------------------------------------------------
    printf "${CYAN}${BOLD}║${RESET}"
    printf "  ${BOLD}PLATFORM${RESET} total: ${WHITE}%4d${RESET}" "$P_TOTAL"
    printf "  $(arrow $prev_p_total $P_TOTAL)"
    printf "    ${BOLD}${MAGENTA}VIRTUAL${RESET}  active: ${WHITE}%4d${RESET}" "$V_TOTAL"
    printf "  $(arrow $prev_v_total $V_TOTAL)"
    printf "    ${DIM}grand: %d${RESET}" "$GRAND"
    printf "${CYAN}${BOLD}%$(( W - 55 - ${#GRAND} ))s║\n${RESET}" ""

    printf "${CYAN}${BOLD}║${RESET}"
    printf "  ${DIM}daemon: %-4d  non-daemon: %-4d  peak: %-4d  started-ever: %-6d${RESET}" \
           "$P_DAEMON" "$P_NONDAEMON" "$P_PEAK" "$P_STARTED"
    printf "${CYAN}${BOLD}%$(( W - 56 - ${#P_STARTED} ))s║\n${RESET}" ""

    printf "${CYAN}${BOLD}║${RESET}"
    printf "  ${DIM}virtual started-ever: %-8d  completed-ever: %-8d${RESET}" \
           "$V_STARTED" "$V_COMPLETED"
    printf "${CYAN}${BOLD}%$(( W - 52 - ${#V_STARTED} - ${#V_COMPLETED} ))s║\n${RESET}" ""

    # ---- Column headers for platform state breakdown -------------------------
    printf "${CYAN}${BOLD}╠"; printf '%0.s═' $(seq 1 $((W-2))); printf "╣\n${RESET}"
    printf "${CYAN}${BOLD}║${RESET}"
    printf "  ${BOLD}%-20s  %5s  %-16s${RESET}" \
           "PLATFORM STATE" "COUNT" "(bar — max ${BAR_MAX})"
    printf "${CYAN}${BOLD}%$(( W - 48 - ${#BAR_MAX} ))s║\n${RESET}" ""
    printf "${CYAN}${BOLD}╠"; printf '%0.s═' $(seq 1 $((W-2))); printf "╣\n${RESET}"

    # ---- Platform state rows -------------------------------------------------
    # Virtual threads don't have per-state breakdown (ThreadMXBean excludes them).
    # Instead we show the platform state histogram and the virtual active count as a bar.
    plat_state_row() {
        local label="$1"
        local count="${2:-0}"
        local bmax="${3:-1}"
        local colour="${4:-$CYAN}"
        printf "  ${BOLD}%-20s${RESET}  %5d  " "$label" "$count"
        bar "$count" "$bmax" 20 "$colour"
        printf "\n"
    }
    plat_state_row "RUNNABLE"      "$P_RUNNABLE" "$BAR_MAX" "$GREEN"
    plat_state_row "TIMED_WAITING" "$P_TIMED"    "$BAR_MAX" "$YELLOW"
    plat_state_row "WAITING"       "$P_WAITING"  "$BAR_MAX" "$BLUE"
    plat_state_row "BLOCKED"       "$P_BLOCKED"  "$BAR_MAX" "$RED"

    # ---- Virtual active bar (single summary row) ----------------------------
    printf "${CYAN}${BOLD}╠"; printf '%0.s═' $(seq 1 $((W-2))); printf "╣\n${RESET}"
    printf "  ${BOLD}${MAGENTA}%-20s${RESET}  %5d  " "VIRTUAL active" "$V_TOTAL"
    bar "$V_TOTAL" "$BAR_MAX" 20 "$MAGENTA"
    printf "\n"

    # ---- Footer / hints ------------------------------------------------------
    printf "${CYAN}${BOLD}╠"; printf '%0.s═' $(seq 1 $((W-2))); printf "╣\n${RESET}"
    printf "${CYAN}${BOLD}║${RESET}  ${DIM}Polling %s every %ss   Ctrl+C to stop${RESET}" \
           "$ENDPOINT" "$INTERVAL"
    printf "${CYAN}${BOLD}%$(( W - 36 - ${#ENDPOINT} - ${#INTERVAL} ))s║\n${RESET}" ""
    printf "${CYAN}${BOLD}║${RESET}  ${DIM}Run a load test in another terminal to watch counts change:${RESET}"
    printf "${CYAN}${BOLD}%$(( W - 59 ))s║\n${RESET}" ""
    printf "${CYAN}${BOLD}║${RESET}  ${DIM}  ab -n 300 -c 80 '${HOST}/api/aggregate/classic?sources=50'${RESET}"
    printf "${CYAN}${BOLD}%$(( W - 57 - ${#HOST} ))s║\n${RESET}" ""
    printf "${CYAN}${BOLD}║${RESET}  ${DIM}  ab -n 300 -c 80 '${HOST}/api/aggregate/loom?sources=50'${RESET}"
    printf "${CYAN}${BOLD}%$(( W - 54 - ${#HOST} ))s║\n${RESET}" ""
    printf "${CYAN}${BOLD}╚"; printf '%0.s═' $(seq 1 $((W-2))); printf "╝\n${RESET}"

    # ---- Persist counts for next iteration's delta arrows --------------------
    prev_p_total=$P_TOTAL
    prev_v_total=$V_TOTAL

    sleep "$INTERVAL"
done
