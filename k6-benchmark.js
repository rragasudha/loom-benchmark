/**
 * k6 load test — loom-benchmark
 *
 * Runs two scenarios back-to-back so you can compare classic vs Loom
 * under identical load. Each scenario hammers a single endpoint for 30s
 * with 200 virtual users, then the summary shows the side-by-side diff.
 *
 * Run:
 *   k6 run k6-benchmark.js
 *
 * Customise:
 *   k6 run -e VUS=100 -e DURATION=20s -e SOURCES=50 -e HOST=http://localhost:9090 k6-benchmark.js
 *
 * What to observe in the summary:
 *   http_req_duration{scenario:classic}  — p95/p99 latency under thread-pool queuing
 *   http_req_duration{scenario:loom}     — p95/p99 latency with virtual threads (should be ~800ms flat)
 *   http_req_failed{scenario:classic}    — non-zero if queue overflows (RejectedExecutionException → 500)
 *   http_req_failed{scenario:loom}       — should be 0
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// ---- Config (override with -e VUS=N etc.) -----------------------------------
const HOST     = __ENV.HOST     || 'http://localhost:9090';
const SOURCES  = __ENV.SOURCES  || '20';
const VUS      = parseInt(__ENV.VUS      || '200');
const DURATION = __ENV.DURATION || '30s';

const CLASSIC_URL = `${HOST}/api/aggregate/classic?sources=${SOURCES}`;
const LOOM_URL    = `${HOST}/api/aggregate/loom?sources=${SOURCES}`;

// ---- Custom metrics ---------------------------------------------------------
// Separate duration trends so the end-of-run summary shows them labelled.
const classicDuration = new Trend('classic_req_duration', true);
const loomDuration    = new Trend('loom_req_duration',    true);
const classicErrors   = new Rate('classic_error_rate');
const loomErrors      = new Rate('loom_error_rate');

// ---- Scenarios --------------------------------------------------------------
// Two named scenarios run sequentially (startTime staggers them).
// Each warms up with a 5-second ramp then holds at VUS for DURATION.
export const options = {
  scenarios: {
    // Phase 1: classic fixed thread pool
    classic: {
      executor:  'constant-vus',
      vus:       VUS,
      duration:  DURATION,
      startTime: '0s',
      tags:      { scenario: 'classic' },
    },
    // Phase 2: Project Loom virtual threads
    // startTime = DURATION + 10s gap so the two runs don't interfere
    loom: {
      executor:  'constant-vus',
      vus:       VUS,
      duration:  DURATION,
      startTime: '40s',   // adjust if you change DURATION
      tags:      { scenario: 'loom' },
    },
  },

  // Fail the test if error rate exceeds 1% on loom (classic may have some under extreme load)
  thresholds: {
    'loom_error_rate':         ['rate<0.01'],
    'loom_req_duration':       ['p(95)<2000'],  // loom p95 should stay under 2s even at 200 VUs
    'classic_req_duration':    ['p(95)<10000'], // classic queues under load — give it more room
  },
};

// ---- Test function ----------------------------------------------------------
// k6 routes each VU to the scenario that owns the current time window.
// We read the scenario tag set on the VU to decide which URL to hit.
export default function () {
  const scenario = __ENV.K6_SCENARIO_NAME || 'classic';

  if (scenario === 'classic') {
    runRequest(CLASSIC_URL, classicDuration, classicErrors);
  } else {
    runRequest(LOOM_URL, loomDuration, loomErrors);
  }
}

function runRequest(url, durationMetric, errorMetric) {
  const res = http.get(url, {
    timeout: '15s',   // single request timeout — classic may queue under heavy load
  });

  const ok = check(res, {
    'status is 200': (r) => r.status === 200,
  });

  durationMetric.add(res.timings.duration);
  errorMetric.add(!ok);

  // No sleep: we want maximum throughput pressure.
  // Add sleep(0.1) here if you want to model think-time between requests.
}

// ---- Console summary --------------------------------------------------------
export function handleSummary(data) {
  const fmt = (ms) => ms === undefined ? 'N/A' : `${Math.round(ms)}ms`;

  const cDur  = data.metrics['classic_req_duration']?.values;
  const lDur  = data.metrics['loom_req_duration']?.values;
  const cErr  = data.metrics['classic_error_rate']?.values;
  const lErr  = data.metrics['loom_error_rate']?.values;
  const cReqs = data.metrics['http_reqs']?.values;   // total across both

  const summary = `
╔══════════════════════════════════════════════════════════════╗
║          loom-benchmark  —  k6 Load Test Summary            ║
╠══════════════════╦══════════════════╦═══════════════════════╣
║ Metric           ║ CLASSIC          ║ LOOM                  ║
╠══════════════════╬══════════════════╬═══════════════════════╣
║ p50 latency      ║ ${pad(fmt(cDur?.['p(50)']))} ║ ${pad(fmt(lDur?.['p(50)']))}        ║
║ p95 latency      ║ ${pad(fmt(cDur?.['p(95)']))} ║ ${pad(fmt(lDur?.['p(95)']))}        ║
║ p99 latency      ║ ${pad(fmt(cDur?.['p(99)']))} ║ ${pad(fmt(lDur?.['p(99)']))}        ║
║ max latency      ║ ${pad(fmt(cDur?.max))}        ║ ${pad(fmt(lDur?.max))}             ║
║ error rate       ║ ${pad(pct(cErr?.rate))}        ║ ${pad(pct(lErr?.rate))}            ║
╚══════════════════╩══════════════════╩═══════════════════════╝
  VUs: ${VUS}  |  sources per request: ${SOURCES}  |  duration each: ${DURATION}

  What this shows:
  - Classic queues ${VUS} × ${SOURCES} = ${VUS * SOURCES} tasks against pool=20 → latency climbs
  - Loom spawns one virtual thread per task → latency stays flat (~800ms max)
  - Classic error rate > 0 means the queue was still too small (raise queue-capacity in application.yml)
`;

  console.log(summary);

  return {
    stdout: summary,
  };
}

function pad(s) { return String(s).padEnd(14); }
function pct(r) { return r === undefined ? 'N/A' : `${(r * 100).toFixed(2)}%`; }
