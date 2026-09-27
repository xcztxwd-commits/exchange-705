import l0 from 'element-plus/es/locale/lang/zh-tw'
import l1 from 'element-plus/es/locale/lang/en'
import l2 from 'element-plus/es/locale/lang/fr'
import l3 from 'element-plus/es/locale/lang/de'
import l4 from 'element-plus/es/locale/lang/ru'
import l5 from 'element-plus/es/locale/lang/es'
import l6 from 'element-plus/es/locale/lang/pt'
import l7 from 'element-plus/es/locale/lang/it'
import l8 from 'element-plus/es/locale/lang/ar'
import l9 from 'element-plus/es/locale/lang/tr'
import l10 from 'element-plus/es/locale/lang/id'
import l11 from 'element-plus/es/locale/lang/my'
import l12 from 'element-plus/es/locale/lang/hi'
import l13 from 'element-plus/es/locale/lang/cs'
import l14 from 'element-plus/es/locale/lang/pl'
import l15 from 'element-plus/es/locale/lang/ja'
import l16 from 'element-plus/es/locale/lang/ko'
import l17 from 'element-plus/es/locale/lang/th'
import l18 from 'element-plus/es/locale/lang/vi'
export const elementLocales = {
  'zh-TW': l0,
  'en': l1,
  'fr': l2,
  'de': l3,
  'ru': l4,
  'es': l5,
  'pt': l6,
  'it': l7,
  'ar': l8,
  'tr': l9,
  'id': l10,
  'my': l11,
  'hi': l12,
  'cs': l13,
  'pl': l14,
  'ja': { ...l15, el: { ...l15.el,
    dialog: { close: 'ダイアログを閉じる' }, drawer: { close: 'パネルを閉じる' },
    messagebox: { ...l15.el.messagebox, close: 'ダイアログを閉じる' },
    inputNumber: { decrease: '数値を減らす', increase: '数値を増やす' },
    dropdown: { toggleDropdown: '選択メニューを開閉' },
    slider: { defaultLabel: '{min}から{max}までのスライダー', defaultRangeStartLabel: '開始値を選択', defaultRangeEndLabel: '終了値を選択' },
    tag: { close: 'タグを閉じる' },
  } },
  'ko': l16,
  'th': l17,
  'vi': l18
}
