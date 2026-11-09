// Run: node --test docs/api/monetary-validation.test.cjs
// Uses Node's test runner and Ruby's standard-library YAML parser; no npm dependencies.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const path = require('node:path');

const spec = JSON.parse(execFileSync('ruby', [
  '-ryaml', '-rjson', '-e',
  'puts JSON.generate(YAML.safe_load(File.read(ARGV.fetch(0)), aliases: true))',
  path.join(__dirname, 'openapi.yaml'),
], { encoding: 'utf8' }));
const schemas = spec.components.schemas;

// Evaluate the actual YAML schemas with ECMAScript regex semantics, as used by OpenAPI tooling.
function accepts(schema, value) {
  return schema.type === 'string' && typeof value === 'string'
    && (schema.maxLength === undefined || value.length <= schema.maxLength)
    && new RegExp(schema.pattern).test(value);
}

const malformed = [
  '', '1x00', '0x01', '1,00', '1.0', '1.000', '1', '.01', '1.',
  '01.00', '00.01', '+1.00', '1e2', '1e2.00', ' 1.00', '1.00 ',
  '1.00\n', '1.00\r', '1.00\r\n', '1.00\u2028', '1.00\u2029',
  '\n1.00', '1.00\t', '１.00', '1.٠٠', null, 1, 0.01, {}, [],
];
const cases = {
  Amount: {
    valid: ['0.01', '0.10', '1.00', '1250.00', '9999999999999999.99'],
    invalid: [...malformed, '0.00', '-0.00', '-0.01', '-1.00',
      '10000000000000000.00', '99999999999999999.99'],
  },
  AmountTotal: {
    valid: ['0.00', '0.01', '9999999999999999.99', '10000000000000000.00',
      '999999999999999999999999999999.99'],
    invalid: [...malformed, '-0.00', '-0.01', '-10000000000000000.00'],
  },
  Balance: {
    valid: ['0.00', '0.01', '-0.01', '9999999999999999.99',
      '10000000000000000.00', '-10000000000000000.00',
      '999999999999999999999999999999.99'],
    invalid: [...malformed, '-0.00', '-01.00', '-1x00', '-1.00\n'],
  },
};

for (const [name, values] of Object.entries(cases)) {
  for (const [expected, inputs] of Object.entries(values)) {
    for (const value of inputs) {
      test(`${name} ${expected}: ${JSON.stringify(value)}`, () => {
        assert.equal(accepts(schemas[name], value), expected === 'valid');
      });
    }
  }
}

test('DECIMAL(18,2) transaction precision and report schema wiring', () => {
  assert.equal(spec.openapi, '3.0.3');
  assert.equal(schemas.Amount.maxLength, 19); // 16 integer digits + dot + 2 fractional digits
  for (const name of ['TransactionInput', 'Transaction']) {
    assert.equal(schemas[name].properties.amount.$ref, '#/components/schemas/Amount');
  }
  for (const name of ['FundReport', 'InvestorReport']) {
    assert.equal(schemas[name].properties.totals.$ref, '#/components/schemas/ReportTotals');
  }
  for (const name of ['openingBalance', 'netMovement', 'closingBalance']) {
    assert.equal(schemas.ReportTotals.properties[name].$ref, '#/components/schemas/Balance');
  }
  for (const name of ['totalCredits', 'totalDebits']) {
    assert.equal(schemas.ReportTotals.properties[name].$ref, '#/components/schemas/AmountTotal');
  }
  assert.equal(schemas.ReportTotals.properties.byType.items.properties.amount.$ref,
    '#/components/schemas/AmountTotal');
});

test('aggregate sums exceed individual precision without floating-point loss', () => {
  const cents = BigInt('999999999999999999') * 2n;
  const total = `${cents / 100n}.${String(cents % 100n).padStart(2, '0')}`;
  assert.equal(total, '19999999999999999.98');
  assert.equal(accepts(schemas.Amount, total), false);
  assert.equal(accepts(schemas.AmountTotal, total), true);
  assert.equal(accepts(schemas.Balance, total), true);
  assert.equal(accepts(schemas.Balance, `-${total}`), true);
  assert.equal(accepts(schemas.AmountTotal, `-${total}`), false);
});

test('monetary examples conform to their referenced schemas', () => {
  for (const name of ['Amount', 'AmountTotal', 'Balance']) {
    assert(accepts(schemas[name], schemas[name].example), name);
  }
  assert(accepts(schemas.Amount, schemas.Transaction.example.amount));
  for (const name of ['FundReport', 'InvestorReport']) {
    const totals = schemas[name].example.totals;
    for (const field of ['openingBalance', 'netMovement', 'closingBalance']) {
      assert(accepts(schemas.Balance, totals[field]), `${name}.${field}`);
    }
    for (const field of ['totalCredits', 'totalDebits']) {
      assert(accepts(schemas.AmountTotal, totals[field]), `${name}.${field}`);
    }
    for (const bucket of totals.byType) assert(accepts(schemas.AmountTotal, bucket.amount));
  }
});

test('report name fields are required and only the Investor Fund filter is nullable', () => {
  for (const [report, fields] of [
    ['FundReport', ['fundName']],
    ['InvestorReport', ['investorName', 'fundName']],
  ]) {
    for (const field of fields) {
      const schema = schemas[report];
      assert(schema.required.includes(field), report + '.' + field);
      assert.equal(schema.properties[field].type, 'string');
      assert.equal(schema.properties[field].nullable === true,
        report === 'InvestorReport' && field === 'fundName');
      assert(Object.hasOwn(schema.example, field), report + ' example.' + field);
    }
  }
  assert.equal(schemas.InvestorReport.example.fundId, null);
  assert.equal(schemas.InvestorReport.example.fundName, null);
  assert.equal(typeof schemas.InvestorReport.example.investorName, 'string');
  assert.equal(typeof schemas.FundReport.example.fundName, 'string');
});
