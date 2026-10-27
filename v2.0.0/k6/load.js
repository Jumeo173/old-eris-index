import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 20,
  duration: '1m',
  thresholds: {
    'http_req_duration': ['p(95)<3000'],
    'http_req_failed':   ['rate<0.01'],
  },
};

const URL = 'http://localhost:8080/api/v1/research';
const H   = { 'Content-Type': 'application/json' };

export default function () {
  const r = http.post(URL, JSON.stringify({ an: 'ACC12345' }), { headers: H });
  check(r, { 'ok or notfound': s => s.status === 200 || s.status === 404 });
}
