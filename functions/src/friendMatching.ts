export type Friend = { uid: string; displayName: string; username: string };
const normalize = (s: string) => s.normalize('NFKD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/^@/, '').trim();
function distance(a: string, b: string) {
  let row = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const next = [i];
    for (let j = 1; j <= b.length; j++) next[j] = Math.min(next[j - 1] + 1, row[j] + 1, row[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    row = next;
  }
  return row[b.length];
}
export function matchFriend(name: string, friends: Friend[]) {
  const query = normalize(name);
  const exact = friends.filter(f => [f.username, f.displayName].some(n => normalize(n) === query));
  if (exact.length) return exact;
  return friends.filter(f => [f.username, f.displayName, f.displayName.split(' ')[0]].some(n =>
    normalize(n) === query || (query.length >= 4 && distance(normalize(n), query) <= 1)));
}
