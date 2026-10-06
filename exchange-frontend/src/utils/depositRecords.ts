// Presentation only: preserve ledger records and their internal channel markers.
export function depositRecordTypeLabel(
  record: { type?: string },
  translate: (key: 'depositTypeDigital' | 'depositTypeBank') => string
): string {
  switch (record.type?.toLowerCase()) {
    // Keep manual credits under the existing bank-card label; this is display only.
    case 'manual': return translate('depositTypeBank')
    case 'digital': return translate('depositTypeDigital')
    case 'bank': return translate('depositTypeBank')
    default: return '—'
  }
}
