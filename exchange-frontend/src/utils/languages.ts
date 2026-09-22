import type { useLocaleStore } from '@/store/locale'

type LocaleValue = Parameters<ReturnType<typeof useLocaleStore>['setLocale']>[0]

// flag-icons 7.3.2 (MIT), bundled locally in public/img/language-flags.
export const languages: { label: string; locale: LocaleValue; flag: string }[] = [
  { label: 'English', locale: 'en', flag: `${import.meta.env.BASE_URL}img/language-flags/us.svg` },
  { label: 'français', locale: 'fr', flag: `${import.meta.env.BASE_URL}img/language-flags/fr.svg` },
  { label: 'Deutsche', locale: 'de', flag: `${import.meta.env.BASE_URL}img/language-flags/de.svg` },
  { label: 'Русский язык', locale: 'ru', flag: `${import.meta.env.BASE_URL}img/language-flags/ru.svg` },
  { label: 'Español', locale: 'es', flag: `${import.meta.env.BASE_URL}img/language-flags/es.svg` },
  { label: 'Português', locale: 'pt', flag: `${import.meta.env.BASE_URL}img/language-flags/pt.svg` },
  { label: 'Italiano', locale: 'it', flag: `${import.meta.env.BASE_URL}img/language-flags/it.svg` },
  { label: 'عربي', locale: 'ar', flag: `${import.meta.env.BASE_URL}img/language-flags/sa.svg` },
  { label: 'Türkçe', locale: 'tr', flag: `${import.meta.env.BASE_URL}img/language-flags/tr.svg` },
  { label: 'Indonesia', locale: 'id', flag: `${import.meta.env.BASE_URL}img/language-flags/id.svg` },
  { label: 'မြန်မာ', locale: 'my', flag: `${import.meta.env.BASE_URL}img/language-flags/mm.svg` },
  { label: 'हिंदी', locale: 'hi', flag: `${import.meta.env.BASE_URL}img/language-flags/in.svg` },
  { label: 'čeština', locale: 'cs', flag: `${import.meta.env.BASE_URL}img/language-flags/cz.svg` },
  { label: 'Polska', locale: 'pl', flag: `${import.meta.env.BASE_URL}img/language-flags/pl.svg` },
  { label: '日本語', locale: 'ja', flag: `${import.meta.env.BASE_URL}img/language-flags/jp.svg` },
  { label: '한국어', locale: 'ko', flag: `${import.meta.env.BASE_URL}img/language-flags/kr.svg` },
  { label: '繁體中文', locale: 'zh-TW', flag: `${import.meta.env.BASE_URL}img/language-flags/tw.svg` },
  { label: 'ไทย', locale: 'th', flag: `${import.meta.env.BASE_URL}img/language-flags/th.svg` },
  { label: 'Tiếng Việt', locale: 'vi', flag: `${import.meta.env.BASE_URL}img/language-flags/vn.svg` },
]
