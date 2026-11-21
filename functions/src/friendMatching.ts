export type Friend = { uid: string; displayName: string; username: string };
const fold = (s: string) => s.normalize('NFKD').replace(/\p{M}/gu, '').toLowerCase().trim();
const words = (s: string): string[] => fold(s).match(/[\p{L}\p{N}]+/gu) ?? [];
const compact = (parts: string[]) => parts.join('');
const sorted = (parts: string[]) => [...parts].sort().join(' ');
function distance(a: string, b: string) {
  let row = Array.from({ length: b.length + 1 }, (_, i) => i);
  let older = row;
  for (let i = 1; i <= a.length; i++) {
    const next = [i];
    for (let j = 1; j <= b.length; j++) {
      next[j] = Math.min(next[j - 1] + 1, row[j] + 1, row[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1])
        next[j] = Math.min(next[j], older[j - 2] + 1);
    }
    older = row;
    row = next;
  }
  return row[b.length];
}
// Conservative English sound-alikes supplement spelling matches. Always confirm these.
function sound(s: string) {
  return s.replace(/ph/g, 'f').replace(/ck/g, 'k').replace(/qu/g, 'k')
    .replace(/c(?=[eiy])/g, 's').replace(/c/g, 'k').replace(/(.)\1+/g, '$1')
    .replace(/[aeiouy]+/g, '');
}

export function resolveFriend(name: string, friends: Friend[]): { matches: Friend[]; selected?: Friend } {
  const handle = fold(name).replace(/^@/, '');
  const parts = words(name);
  if (!parts.length || name.length > 100) return { matches: [] };
  const query = compact(parts);
  function strong(matches: Friend[]) {
    return { matches, selected: matches.length === 1 ? matches[0] : undefined };
  }
  const usernames = friends.filter(f => fold(f.username) === handle);
  if (name.trim().startsWith('@') && usernames.length) return strong(usernames);
  const exact = friends.filter(f => fold(f.username) === handle || words(f.displayName).join(' ') === parts.join(' '));
  if (exact.length) return strong(exact);
  const alternate = friends.filter(f => {
    const full = words(f.displayName);
    return sorted(full) === sorted(parts) || compact(full) === query || parts.every(part => full.includes(part));
  });
  if (alternate.length) return strong(alternate);
  const maxEdits = query.length >= 8 ? 2 : query.length >= 4 ? 1 : 0;
  const phonetic = sound(query);
  return { matches: friends.filter(f => {
    const full = words(f.displayName);
    const aliases = [compact(words(f.username)), compact(full), ...full];
    return aliases.some(alias => (maxEdits > 0 && distance(alias, query) <= maxEdits) ||
      (query.length >= 4 && phonetic.length >= 2 && sound(alias) === phonetic));
  }) };
}
