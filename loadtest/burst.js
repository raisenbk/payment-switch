// Burst: calm, then a sudden spike (e.g. a promotion), then calm again.
//   PEAK (per second, default 300)
import { buy, destination, fundDemoMerchant } from './lib/switch.js';

const PEAK = Number(__ENV.PEAK || 300);

export const options = {
  scenarios: {
    burst: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: PEAK * 2,
      stages: [
        { target: 10, duration: '30s' },
        { target: PEAK, duration: '10s' },
        { target: PEAK, duration: '30s' },
        { target: 10, duration: '10s' },
        { target: 10, duration: '30s' },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:POST /v1/transactions}': ['p(95)<1000'],
  },
};

export function setup() {
  fundDemoMerchant(50_000_000_000);
}

export default function () {
  buy(destination('90'));
}
