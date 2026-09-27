import type { Locales } from 'klinecharts'
const labels: Record<string, [string, string, string]> = {
  "zh-TW": [
    "成交量",
    "成交金額",
    "漲跌幅"
  ],
  "en": [
    "Volume",
    "Turnover",
    "Change"
  ],
  "fr": [
    "Volume",
    "Montant échangé",
    "Variation"
  ],
  "de": [
    "Volumen",
    "Umsatz",
    "Veränderung"
  ],
  "ru": [
    "Объём",
    "Оборот",
    "Изменение"
  ],
  "es": [
    "Volumen",
    "Importe negociado",
    "Variación"
  ],
  "pt": [
    "Volume",
    "Montante negociado",
    "Variação"
  ],
  "it": [
    "Volume",
    "Controvalore",
    "Variazione"
  ],
  "ar": [
    "حجم التداول",
    "قيمة التداول",
    "التغير"
  ],
  "tr": [
    "İşlem miktarı",
    "İşlem hacmi",
    "Değişim"
  ],
  "id": [
    "Volume",
    "Nilai transaksi",
    "Perubahan"
  ],
  "my": [
    "အရောင်းအဝယ်ပမာဏ",
    "အရောင်းအဝယ်တန်ဖိုး",
    "ပြောင်းလဲမှု"
  ],
  "hi": [
    "वॉल्यूम",
    "कारोबार मूल्य",
    "परिवर्तन"
  ],
  "cs": [
    "Objem",
    "Obrat",
    "Změna"
  ],
  "pl": [
    "Wolumen",
    "Obrót",
    "Zmiana"
  ],
  "ja": [
    "出来高",
    "売買代金",
    "騰落率"
  ],
  "ko": [
    "거래량",
    "거래대금",
    "등락률"
  ],
  "th": [
    "ปริมาณซื้อขาย",
    "มูลค่าซื้อขาย",
    "การเปลี่ยนแปลง"
  ],
  "vi": [
    "Khối lượng",
    "Giá trị giao dịch",
    "Thay đổi"
  ]
}
export function chartLocale(locale: string, t: (key: 'chartTime' | 'chartOpen' | 'chartHigh' | 'chartLow' | 'chartClose') => string): Locales {
  const [volume = 'Volume', turnover = 'Turnover', change = 'Change'] = labels[locale] || labels.en! || ['Volume', 'Turnover', 'Change']
  const unit = (name: string) => new Intl.NumberFormat(locale, { style: 'unit', unit: name, unitDisplay: 'short' })
    .formatToParts(1).filter(part => part.type === 'unit').map(part => part.value).join('')
  return { time: t('chartTime') + ': ', open: t('chartOpen') + ': ', high: t('chartHigh') + ': ', low: t('chartLow') + ': ', close: t('chartClose') + ': ',
    volume: volume + ': ', turnover: turnover + ': ', change: change + ': ', second: unit('second'), minute: unit('minute'), hour: unit('hour'), day: unit('day'), week: unit('week'), month: unit('month'), year: unit('year') }
}
