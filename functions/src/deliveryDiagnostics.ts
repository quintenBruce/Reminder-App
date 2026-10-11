export type DeliveryDiagnostic = {
  reason: 'accepted' | 'no_devices' | 'all_rejected' | 'send_error';
  deviceCount: number;
  acceptedCount: number;
  rejectedCount: number;
  failureCodes: string[];
};

export function deliveryDiagnostic(
  deviceCount: number,
  responses?: ReadonlyArray<{ success: boolean; error?: { code?: string } }>,
  sendErrorCode?: string,
): DeliveryDiagnostic {
  if (!deviceCount) return { reason: 'no_devices', deviceCount: 0, acceptedCount: 0, rejectedCount: 0, failureCodes: [] };
  if (sendErrorCode) return {
    reason: 'send_error', deviceCount, acceptedCount: 0, rejectedCount: deviceCount, failureCodes: [sendErrorCode],
  };
  const acceptedCount = responses?.filter(response => response.success).length ?? 0;
  const rejectedCount = deviceCount - acceptedCount;
  const failureCodes = [...new Set((responses ?? [])
    .filter(response => !response.success)
    .map(response => response.error?.code || 'unknown'))];
  return {
    reason: acceptedCount ? 'accepted' : 'all_rejected', deviceCount, acceptedCount, rejectedCount, failureCodes,
  };
}
