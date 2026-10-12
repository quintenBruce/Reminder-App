function comparable(value: string): string {
  return value.trim().replace(/\s+/g, ' ').toLowerCase();
}

export function reminderPushData(title: string, notes: string, reminderId: string, occurrenceId: string) {
  const body = notes.trim();
  return {
    title,
    ...(body && comparable(body) !== comparable(title) ? { body } : {}),
    reminderId,
    occurrenceId,
    url: `/?reminder=${encodeURIComponent(reminderId)}`,
  };
}
