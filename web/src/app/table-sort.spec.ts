import { describe, expect, it } from 'vitest';
import { TableSort, compareNullable } from './table-sort';

describe('compareNullable', () => {
  const values = [3, null, -1, 10, null];

  it('puts nulls last going down', () => {
    expect([...values].sort((a, b) => compareNullable(a, b, -1))).toEqual([10, 3, -1, null, null]);
  });

  /** The bug this replaced: nulls read as minus infinity and led an ascending list. */
  it('puts nulls last going up too', () => {
    expect([...values].sort((a, b) => compareNullable(a, b, 1))).toEqual([-1, 3, 10, null, null]);
  });
});

describe('TableSort', () => {
  it('starts numbers biggest first and text A to Z', () => {
    const s = new TableSort<'name' | 'value'>('value', ['name']);
    expect(s.dir()).toBe(-1);
    s.by('name');
    expect(s.dir()).toBe(1);
  });

  it('reverses on a second click and says so for screen readers', () => {
    const s = new TableSort<'name' | 'value'>('value', ['name']);
    expect(s.aria('value')).toBe('descending');
    s.by('value');
    expect(s.aria('value')).toBe('ascending');
    expect(s.aria('name')).toBeNull();
  });
});
