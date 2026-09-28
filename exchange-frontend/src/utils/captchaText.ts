// Ordered strings keep both registration clients in the same language as the surrounding form.
const keys = ['label', 'placeholder', 'refresh', 'optional', 'loading', 'retry', 'expired', 'invalid', 'unavailable', 'limited'] as const
const translations: Record<string, string[]> = {
  'zh-TW': ['圖形驗證碼', '請輸入驗證碼', '看不清楚？換一張', '可選', '載入中', '重新載入', '驗證碼已過期，請換一張', '驗證碼錯誤或失效，請重新輸入', '安全驗證暫不可用，請稍後重試', '操作頻繁，請稍後重試'],
  en: ['CAPTCHA', 'Enter code', 'Can’t read it? Refresh', 'Optional', 'Loading', 'Retry', 'Code expired. Refresh it.', 'Invalid code. Please try again.', 'Security verification unavailable. Try later.', 'Too many requests. Please wait.'],
  ja: ['画像認証', '認証コード', '別の画像に変更', '任意', '読込中', '再読込', '期限切れです。画像を更新してください。', '認証コードが無効です。', '認証を利用できません。後でお試しください。', '操作が多すぎます。しばらくお待ちください。'],
  ko: ['이미지 인증', '인증 코드 입력', '다른 이미지 보기', '선택', '로딩 중', '다시 시도', '코드가 만료되었습니다. 새로고침하세요.', '인증 코드가 올바르지 않습니다.', '인증을 사용할 수 없습니다. 나중에 시도하세요.', '요청이 너무 많습니다. 잠시 기다리세요.'],
  fr: ['Code visuel', 'Saisir le code', 'Illisible ? Actualiser', 'Facultatif', 'Chargement', 'Réessayer', 'Code expiré. Actualisez.', 'Code invalide. Réessayez.', 'Vérification indisponible. Réessayez plus tard.', 'Trop de demandes. Veuillez patienter.'],
  de: ['Bildcode', 'Code eingeben', 'Neues Bild laden', 'Optional', 'Lädt', 'Erneut laden', 'Code abgelaufen. Bitte neu laden.', 'Ungültiger Code. Erneut versuchen.', 'Prüfung nicht verfügbar. Später versuchen.', 'Zu viele Anfragen. Bitte warten.'],
  es: ['Código visual', 'Introduce el código', '¿No se ve? Actualizar', 'Opcional', 'Cargando', 'Reintentar', 'Código caducado. Actualiza.', 'Código no válido. Inténtalo de nuevo.', 'Verificación no disponible. Inténtalo más tarde.', 'Demasiadas solicitudes. Espera.'],
  pt: ['Código visual', 'Digite o código', 'Não consegue ler? Atualizar', 'Opcional', 'Carregando', 'Tentar novamente', 'Código expirado. Atualize.', 'Código inválido. Tente novamente.', 'Verificação indisponível. Tente mais tarde.', 'Muitas solicitações. Aguarde.'],
  it: ['Codice visivo', 'Inserisci il codice', 'Illeggibile? Aggiorna', 'Facoltativo', 'Caricamento', 'Riprova', 'Codice scaduto. Aggiorna.', 'Codice non valido. Riprova.', 'Verifica non disponibile. Riprova più tardi.', 'Troppe richieste. Attendi.'],
  ru: ['Код с картинки', 'Введите код', 'Обновить картинку', 'Необязательно', 'Загрузка', 'Повторить', 'Код истёк. Обновите картинку.', 'Неверный код. Повторите ввод.', 'Проверка недоступна. Попробуйте позже.', 'Слишком много запросов. Подождите.'],
  ar: ['رمز التحقق', 'أدخل الرمز', 'تحديث الصورة', 'اختياري', 'جار التحميل', 'إعادة المحاولة', 'انتهت صلاحية الرمز. حدّث الصورة.', 'الرمز غير صالح. حاول مجددًا.', 'التحقق غير متاح. حاول لاحقًا.', 'طلبات كثيرة. يرجى الانتظار.'],
  tr: ['Görsel doğrulama', 'Kodu girin', 'Yeni görsel', 'İsteğe bağlı', 'Yükleniyor', 'Tekrar dene', 'Kodun süresi doldu. Yenileyin.', 'Geçersiz kod. Tekrar deneyin.', 'Doğrulama kullanılamıyor. Daha sonra deneyin.', 'Çok fazla istek. Lütfen bekleyin.'],
  id: ['Kode gambar', 'Masukkan kode', 'Ganti gambar', 'Opsional', 'Memuat', 'Coba lagi', 'Kode kedaluwarsa. Muat ulang.', 'Kode tidak valid. Coba lagi.', 'Verifikasi tidak tersedia. Coba nanti.', 'Terlalu banyak permintaan. Tunggu.'],
  my: ['ပုံအတည်ပြုကုဒ်', 'ကုဒ်ထည့်ပါ', 'ပုံအသစ်ယူရန်', 'မဖြစ်မနေမဟုတ်', 'ဖွင့်နေသည်', 'ပြန်ကြိုးစားရန်', 'ကုဒ်သက်တမ်းကုန်ပါပြီ။ ပုံအသစ်ယူပါ။', 'ကုဒ်မမှန်ပါ။ ပြန်ကြိုးစားပါ။', 'အတည်ပြုခြင်း မရနိုင်ပါ။ နောက်မှ ပြန်ကြိုးစားပါ။', 'တောင်းဆိုမှုများလွန်းပါသည်။ ခဏစောင့်ပါ။'],
  hi: ['चित्र सत्यापन', 'कोड दर्ज करें', 'नया चित्र लें', 'वैकल्पिक', 'लोड हो रहा है', 'फिर प्रयास करें', 'कोड की अवधि समाप्त। नया चित्र लें।', 'अमान्य कोड। फिर प्रयास करें।', 'सत्यापन उपलब्ध नहीं। बाद में प्रयास करें।', 'बहुत अधिक अनुरोध। कृपया प्रतीक्षा करें।'],
  cs: ['Obrázkový kód', 'Zadejte kód', 'Obnovit obrázek', 'Volitelné', 'Načítání', 'Zkusit znovu', 'Kód vypršel. Obnovte obrázek.', 'Neplatný kód. Zkuste znovu.', 'Ověření není dostupné. Zkuste později.', 'Příliš mnoho požadavků. Počkejte.'],
  pl: ['Kod z obrazka', 'Wpisz kod', 'Odśwież obrazek', 'Opcjonalne', 'Ładowanie', 'Ponów', 'Kod wygasł. Odśwież obrazek.', 'Nieprawidłowy kod. Spróbuj ponownie.', 'Weryfikacja niedostępna. Spróbuj później.', 'Zbyt wiele żądań. Poczekaj.'],
  th: ['รหัสยืนยันรูปภาพ', 'กรอกรหัส', 'เปลี่ยนรูปภาพ', 'ไม่บังคับ', 'กำลังโหลด', 'ลองอีกครั้ง', 'รหัสหมดอายุ โปรดเปลี่ยนรูปภาพ', 'รหัสไม่ถูกต้อง โปรดลองอีกครั้ง', 'ไม่สามารถยืนยันได้ โปรดลองภายหลัง', 'คำขอมากเกินไป โปรดรอสักครู่'],
  vi: ['Mã hình ảnh', 'Nhập mã', 'Đổi hình ảnh', 'Không bắt buộc', 'Đang tải', 'Thử lại', 'Mã hết hạn. Hãy đổi hình.', 'Mã không hợp lệ. Hãy thử lại.', 'Không thể xác minh. Hãy thử sau.', 'Quá nhiều yêu cầu. Vui lòng đợi.'],
}
export function captchaText(locale: string, key: typeof keys[number]): string {
  return (translations[locale.startsWith('zh') ? 'zh-TW' : locale] || translations.en)![keys.indexOf(key)]!
}
