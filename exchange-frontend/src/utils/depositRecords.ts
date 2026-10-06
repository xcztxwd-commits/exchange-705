// Presentation only: preserve ledger records and their internal channel markers.
export function depositRecordTypeLabel(
  record: { type?: string },
  translate: (key: 'depositTypeManual' | 'depositTypeDigital' | 'depositTypeBank') => string
): string {
  switch (record.type?.toLowerCase()) {
    case 'manual': return translate('depositTypeManual')
    case 'digital': return translate('depositTypeDigital')
    case 'bank': return translate('depositTypeBank')
    default: return '—'
  }
}
