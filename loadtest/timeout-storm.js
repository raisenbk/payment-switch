// Supplier timeout storm: a large share of top-ups hit a supplier that answers after the read
// timeout (destination suffix 22). What to watch (Grafana "Payment Switch" dashboard):
//   - the API keeps answering 202 quickly: supplier slowness never blocks acceptance;
//   - UNKNOWN climbs (money held, no refunds on a guess), then drains as inquiries confirm success;
//   - the circuit breaker opens for MOCKA and routing moves new work to MOCKB;
//   - zero failovers from UNKNOWN (ADR-008).
//   RATE (per second, default 30), DURATION (default 2m), STORM_SHARE (0..1, default 0.6)
import { buy, destination, fundDemoMerchant } from './lib/switch.js';

const RATE = Number(__ENV.RATE || 30);
const STORM_SHARE = Number(__ENV.STORM_SHARE || 0.6);

export const options = {
  scenarios: {
    storm: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '2m',
      preAllocatedVUs: Math.max(10, RATE),
      maxVUs: RATE * 4,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:POST /v1/transactions}': ['p(95)<300'],
  },
};

export function setup() {
  fundDemoMerchant(50_000_000_000);
}

export default function () {
  buy(destination(Math.random() < STORM_SHARE ? '22' : '90'));
}
