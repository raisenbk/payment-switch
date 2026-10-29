// Steady load: a constant arrival rate of healthy top-ups.
//   RATE (per second, default 50), DURATION (default 2m)
import { buy, destination, fundDemoMerchant } from './lib/switch.js';

const RATE = Number(__ENV.RATE || 50);

export const options = {
  scenarios: {
    steady: {
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
    'http_req_duration{name:POST /v1/transactions}': ['p(95)<300', 'p(99)<800'],
  },
};

export function setup() {
  fundDemoMerchant(50_000_000_000);
}

export default function () {
  buy(destination('90'));
}
