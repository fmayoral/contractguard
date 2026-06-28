import { describe, expect, it } from 'vitest';
import {
  changeTarget,
  classificationTone,
  eventKind,
  formatDuration,
  formatTimestamp,
  isTerminal,
  severityTone,
  shortHash,
  stateTone,
} from './format';

describe('state helpers', () => {
  it('identifies terminal states', () => {
    expect(isTerminal('SUCCEEDED')).toBe(true);
    expect(isTerminal('FAILED')).toBe(true);
    expect(isTerminal('REJECTED')).toBe(true);
    expect(isTerminal('CANCELLED')).toBe(true);
    expect(isTerminal('VALIDATING')).toBe(false);
  });

  it('maps run states to tones', () => {
    expect(stateTone('SUCCEEDED')).toBe('ok');
    expect(stateTone('FAILED')).toBe('bad');
    expect(stateTone('REJECTED')).toBe('warn');
    expect(stateTone('AWAITING_APPROVAL')).toBe('warn');
    expect(stateTone('DIFFING')).toBe('busy');
  });

  it('maps classifications and severities to tones', () => {
    expect(classificationTone('BREAKING')).toBe('bad');
    expect(classificationTone('POTENTIALLY_BREAKING')).toBe('warn');
    expect(classificationTone('NON_BREAKING')).toBe('ok');
    expect(classificationTone('UNKNOWN')).toBe('busy');
    expect(severityTone('HIGH')).toBe('bad');
    expect(severityTone('MEDIUM')).toBe('warn');
    expect(severityTone('LOW')).toBe('ok');
  });
});

describe('eventKind', () => {
  it('reads the kind flag from metadata JSON', () => {
    expect(eventKind('{"kind":"llm"}')).toBe('llm');
    expect(eventKind('{"kind":"tool","changes":4}')).toBe('tool');
  });

  it('defaults to system for missing or malformed metadata', () => {
    expect(eventKind(null)).toBe('system');
    expect(eventKind('not json')).toBe('system');
    expect(eventKind('{"other":1}')).toBe('system');
  });
});

describe('formatting', () => {
  it('formats durations at sensible precision', () => {
    expect(formatDuration(220)).toBe('220 ms');
    expect(formatDuration(2500)).toBe('2.5 s');
    expect(formatDuration(95000)).toBe('1m 35s');
  });

  it('formats timestamps and tolerates junk', () => {
    expect(formatTimestamp('2026-07-18T10:00:00Z')).toMatch(/\d{2}:\d{2}/);
    expect(formatTimestamp('garbage')).toBe('garbage');
  });

  it('labels change targets', () => {
    expect(changeTarget({ method: 'GET', path: '/customers/{id}', schema: null, property: null }))
      .toBe('GET /customers/{id}');
    expect(changeTarget({ method: null, path: null, schema: 'Customer', property: 'fullName' }))
      .toBe('Customer.fullName');
    expect(changeTarget({ method: null, path: null, schema: 'Customer', property: null }))
      .toBe('Customer');
    expect(changeTarget({ method: null, path: null, schema: null, property: null })).toBe('—');
  });

  it('shortens hashes', () => {
    expect(shortHash('abcdef1234567890')).toBe('abcdef123456');
    expect(shortHash('short')).toBe('short');
  });
});
