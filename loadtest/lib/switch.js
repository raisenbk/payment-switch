// Shared helpers: HMAC request signing (same scheme as scripts/signed-curl.sh) and demo set-up.
import http from 'k6/http';
import crypto from 'k6/crypto';
import { check } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'pk_demo_local';
const API_SECRET = __ENV.API_SECRET || 'sk_demo_local_only_not_secret';
const ADMIN_TOKEN = __ENV.ADMIN_TOKEN || 'local-admin-token-not-secret';
const MERCHANT_ID = __ENV.MERCHANT_ID || '00000000-0000-0000-0000-00000000d3e0';

/** METHOD \n PATH \n TIMESTAMP \n hex(sha256(body)), HMAC-SHA256 with the merchant's API secret. */
export function signedRequest(method, path, body, extraHeaders, tagName) {
  const timestamp = Math.floor(Date.now() / 1000).toString();
  const canonical = `${method}\n${path}\n${timestamp}\n${crypto.sha256(body || '', 'hex')}`;
  const headers = Object.assign({
    'X-Api-Key': API_KEY,
    'X-Timestamp': timestamp,
    'X-Signature': crypto.hmac('sha256', API_SECRET, canonical, 'hex'),
  }, extraHeaders || {});
  if (body) {
    headers['Content-Type'] = 'application/json';
  }
  return http.request(method, `${BASE_URL}${path}`, body || null, { headers, tags: { name: tagName || path } });
}

/** Gives the demo merchant enough balance for a long run (idempotent per reference). */
export function fundDemoMerchant(amount) {
  const res = http.post(`${BASE_URL}/admin/merchants/${MERCHANT_ID}/top-ups`,
    JSON.stringify({ amount, currency: 'IDR', reference: `k6-${Date.now()}` }),
    { headers: { 'Content-Type': 'application/json', 'X-Admin-Token': ADMIN_TOKEN } });
  check(res, { 'top-up booked (201)': (r) => r.status === 201 });
  if (res.status !== 201) {
    throw new Error(`top-up failed: ${res.status} ${res.body}`);
  }
}

/** POST /v1/transactions; the merchant reference doubles as idempotency key. */
export function buy(destination, productCode) {
  const reference = `k6-${__VU}-${__ITER}-${Date.now()}`;
  const body = JSON.stringify({ merchantRef: reference, productCode: productCode || 'PULSA10', destination });
  const res = signedRequest('POST', '/v1/transactions', body, { 'Idempotency-Key': reference }, 'POST /v1/transactions');
  check(res, { 'accepted (202)': (r) => r.status === 202 });
  return res;
}

/** A destination number; the mock supplier picks its behaviour from the last two digits. */
export function destination(suffix) {
  const middle = Math.floor(Math.random() * 1e6).toString().padStart(6, '0');
  return `0812${middle}${suffix}`;
}
