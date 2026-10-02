export type LoginReason = 'failed' | 'credentials' | 'rate' | 'service' | 'timeout' | 'email' | 'disabled' | 'network' | 'missing' | 'success'

export const accountLoginMessages: Record<string, readonly [string, string, string]> = {
  "zh-TW": ["帳號登入", "請輸入帳號", "請輸入帳號和密碼"],
  "en": ["Account sign-in", "Enter your account", "Enter account and password"],
  "fr": ["Connexion au compte", "Saisissez votre identifiant", "Saisissez votre identifiant et votre mot de passe"],
  "de": ["Kontoanmeldung", "Konto eingeben", "Bitte Konto und Passwort eingeben"],
  "ru": ["Вход в аккаунт", "Введите логин", "Введите логин и пароль"],
  "es": ["Inicio de sesión", "Ingresa tu cuenta", "Ingresa tu cuenta y contraseña"],
  "pt": ["Entrar na conta", "Digite sua conta", "Digite sua conta e senha"],
  "it": ["Accesso account", "Inserisci il tuo account", "Inserisci account e password"],
  "ar": ["تسجيل الدخول للحساب", "أدخل حسابك", "أدخل الحساب وكلمة المرور"],
  "tr": ["Hesap girişi", "Hesabınızı girin", "Hesap ve parola girin"],
  "id": ["Masuk akun", "Masukkan akun Anda", "Masukkan akun dan kata sandi"],
  "my": ["အကောင့်ဝင်ရန်", "အကောင့်ထည့်ပါ", "အကောင့်နှင့် စကားဝှက် ထည့်ပါ"],
  "hi": ["खाते में लॉगिन", "अपना खाता दर्ज करें", "खाता और पासवर्ड दर्ज करें"],
  "cs": ["Přihlášení k účtu", "Zadejte účet", "Zadejte účet a heslo"],
  "pl": ["Logowanie do konta", "Wpisz konto", "Wpisz konto i hasło"],
  "ja": ["アカウントでログイン", "アカウントを入力", "アカウントとパスワードを入力してください"],
  "ko": ["계정 로그인", "계정을 입력하세요", "계정과 비밀번호를 입력하세요"],
  "th": ["เข้าสู่ระบบบัญชี", "กรอกบัญชีของคุณ", "กรอกบัญชีและรหัสผ่าน"],
  "vi": ["Đăng nhập tài khoản", "Nhập tài khoản", "Nhập tài khoản và mật khẩu"],
}

export function loginAccountText(locale: string, part: 'title' | 'placeholder' | 'missing'): string {
  return (accountLoginMessages[locale] || accountLoginMessages.en)![{ title: 0, placeholder: 1, missing: 2 }[part]]!
}

// Keep login errors separate from expired-session handling; never display server text.
export function classifyLoginError(error: any): LoginReason {
  const status = error?.response?.status
  const data = error?.response?.data
  const message = String(data?.message || data?.error || '').toLowerCase()
  if (status === 429) return 'rate'
  if (String(data?.code || '').startsWith('TENANT_')) return 'service'
  if (status >= 500 || status === 403 && !/account disabled|账户已被禁用|賬戶已被禁用/.test(message)) return 'service'
  if (/account disabled|账户已被禁用|賬戶已被禁用/.test(message)) return 'disabled'
  if (status === 401 || /password wrong|user not found|密码错误|用户不存在|密碼錯誤/.test(message)) return 'credentials'
  if (error?.code === 'ECONNABORTED' || error?.code === 'ETIMEDOUT') return 'timeout'
  if (!error?.response && error?.request) return 'network'
  return 'failed'
}

// Order: generic failure, credentials, throttling, unavailable service, timeout, invalid email.
export const loginMessages: Record<string, readonly string[]> = {
  'zh-TW': ['登入失敗，請重試。', '帳號或密碼不正確，請檢查後重試。', '嘗試次數過多，請稍後再試。', '登入服務暫時不可用，請稍後再試或聯絡客服。', '連線逾時，請重試。', '請輸入有效的郵箱地址。'],
  en: ['Unable to sign in. Please try again.', 'Account or password is incorrect. Please check and retry.', 'Too many attempts. Please try again later.', 'Sign-in is temporarily unavailable. Try later or contact support.', 'Connection timed out. Please try again.', 'Please enter a valid email address.'],
  fr: ['Connexion impossible. Réessayez.', 'Identifiant ou mot de passe incorrect. Vérifiez et réessayez.', 'Trop de tentatives. Réessayez plus tard.', 'Connexion temporairement indisponible. Réessayez plus tard ou contactez l’assistance.', 'Délai de connexion dépassé. Réessayez.', 'Saisissez une adresse e-mail valide.'],
  de: ['Anmeldung fehlgeschlagen. Bitte erneut versuchen.', 'Konto oder Passwort falsch. Bitte prüfen und erneut versuchen.', 'Zu viele Versuche. Bitte später erneut versuchen.', 'Anmeldung vorübergehend nicht verfügbar. Später versuchen oder Support kontaktieren.', 'Zeitüberschreitung. Bitte erneut versuchen.', 'Bitte eine gültige E-Mail-Adresse eingeben.'],
  ru: ['Не удалось войти. Повторите попытку.', 'Неверный логин или пароль. Проверьте данные.', 'Слишком много попыток. Повторите позже.', 'Вход временно недоступен. Повторите позже или обратитесь в поддержку.', 'Время ожидания истекло. Повторите попытку.', 'Введите действительный адрес электронной почты.'],
  es: ['No se pudo iniciar sesión. Inténtalo de nuevo.', 'Cuenta o contraseña incorrectos. Revisa los datos.', 'Demasiados intentos. Inténtalo más tarde.', 'Inicio de sesión no disponible temporalmente. Inténtalo más tarde o contacta con soporte.', 'Se agotó el tiempo de conexión. Inténtalo de nuevo.', 'Introduce un correo electrónico válido.'],
  pt: ['Não foi possível entrar. Tente novamente.', 'Conta ou senha incorretas. Verifique os dados.', 'Muitas tentativas. Tente mais tarde.', 'Login temporariamente indisponível. Tente mais tarde ou contate o suporte.', 'Tempo de conexão esgotado. Tente novamente.', 'Digite um endereço de e-mail válido.'],
  it: ['Accesso non riuscito. Riprova.', 'Account o password errati. Controlla i dati.', 'Troppi tentativi. Riprova più tardi.', 'Accesso temporaneamente non disponibile. Riprova più tardi o contatta l’assistenza.', 'Connessione scaduta. Riprova.', 'Inserisci un indirizzo e-mail valido.'],
  ar: ['تعذر تسجيل الدخول. حاول مجددًا.', 'الحساب أو كلمة المرور غير صحيحة. تحقق وحاول مجددًا.', 'محاولات كثيرة جدًا. حاول لاحقًا.', 'تسجيل الدخول غير متاح مؤقتًا. حاول لاحقًا أو تواصل مع الدعم.', 'انتهت مهلة الاتصال. حاول مجددًا.', 'أدخل عنوان بريد إلكتروني صالحًا.'],
  tr: ['Giriş yapılamadı. Tekrar deneyin.', 'Hesap veya parola yanlış. Bilgileri kontrol edin.', 'Çok fazla deneme. Daha sonra tekrar deneyin.', 'Giriş geçici olarak kullanılamıyor. Daha sonra deneyin veya destekle iletişime geçin.', 'Bağlantı zaman aşımına uğradı. Tekrar deneyin.', 'Geçerli bir e-posta adresi girin.'],
  id: ['Tidak dapat masuk. Coba lagi.', 'Akun atau kata sandi salah. Periksa kembali.', 'Terlalu banyak percobaan. Coba lagi nanti.', 'Login sementara tidak tersedia. Coba nanti atau hubungi dukungan.', 'Waktu koneksi habis. Coba lagi.', 'Masukkan alamat email yang valid.'],
  my: ['ဝင်ရောက်မှု မအောင်မြင်ပါ။ ထပ်မံကြိုးစားပါ။', 'အကောင့် သို့မဟုတ် စကားဝှက် မမှန်ပါ။ ပြန်စစ်ပါ။', 'ကြိုးစားမှုများလွန်းပါသည်။ နောက်မှ ထပ်ကြိုးစားပါ။', 'ဝင်ရောက်မှု ယာယီမရနိုင်ပါ။ နောက်မှ ထပ်ကြိုးစားပါ သို့မဟုတ် အကူအညီတောင်းပါ။', 'ချိတ်ဆက်ချိန် ကုန်ဆုံးပါပြီ။ ထပ်ကြိုးစားပါ။', 'မှန်ကန်သော အီးမေးလ်လိပ်စာ ထည့်ပါ။'],
  hi: ['लॉगिन नहीं हो सका। फिर कोशिश करें।', 'खाता या पासवर्ड गलत है। जाँचकर फिर कोशिश करें।', 'बहुत अधिक प्रयास। बाद में कोशिश करें।', 'लॉगिन अभी उपलब्ध नहीं है। बाद में कोशिश करें या सहायता से संपर्क करें।', 'कनेक्शन का समय समाप्त हुआ। फिर कोशिश करें।', 'मान्य ईमेल पता दर्ज करें।'],
  cs: ['Přihlášení se nezdařilo. Zkuste to znovu.', 'Nesprávný účet nebo heslo. Zkontrolujte údaje.', 'Příliš mnoho pokusů. Zkuste to později.', 'Přihlášení je dočasně nedostupné. Zkuste to později nebo kontaktujte podporu.', 'Časový limit připojení vypršel. Zkuste to znovu.', 'Zadejte platnou e-mailovou adresu.'],
  pl: ['Logowanie nie powiodło się. Spróbuj ponownie.', 'Nieprawidłowe konto lub hasło. Sprawdź dane.', 'Zbyt wiele prób. Spróbuj później.', 'Logowanie jest chwilowo niedostępne. Spróbuj później lub skontaktuj się z pomocą.', 'Upłynął limit czasu połączenia. Spróbuj ponownie.', 'Wpisz prawidłowy adres e-mail.'],
  ja: ['ログインできませんでした。再試行してください。', 'アカウントまたはパスワードが正しくありません。確認してください。', '試行回数が多すぎます。しばらくしてから再試行してください。', '現在ログインできません。後でもう一度試すか、サポートにお問い合わせください。', '接続がタイムアウトしました。再試行してください。', '有効なメールアドレスを入力してください。'],
  ko: ['로그인하지 못했습니다. 다시 시도해 주세요.', '계정 또는 비밀번호가 올바르지 않습니다. 확인해 주세요.', '시도 횟수가 너무 많습니다. 나중에 다시 시도해 주세요.', '일시적으로 로그인할 수 없습니다. 나중에 다시 시도하거나 고객센터에 문의해 주세요.', '연결 시간이 초과되었습니다. 다시 시도해 주세요.', '유효한 이메일 주소를 입력해 주세요.'],
  th: ['เข้าสู่ระบบไม่สำเร็จ โปรดลองอีกครั้ง', 'บัญชีหรือรหัสผ่านไม่ถูกต้อง โปรดตรวจสอบ', 'ลองหลายครั้งเกินไป โปรดลองใหม่ภายหลัง', 'ไม่สามารถเข้าสู่ระบบได้ชั่วคราว โปรดลองภายหลังหรือติดต่อฝ่ายสนับสนุน', 'การเชื่อมต่อหมดเวลา โปรดลองอีกครั้ง', 'โปรดป้อนอีเมลที่ถูกต้อง'],
  vi: ['Không thể đăng nhập. Vui lòng thử lại.', 'Tài khoản hoặc mật khẩu không đúng. Vui lòng kiểm tra.', 'Quá nhiều lần thử. Vui lòng thử lại sau.', 'Tạm thời không thể đăng nhập. Hãy thử lại sau hoặc liên hệ hỗ trợ.', 'Kết nối hết thời gian chờ. Vui lòng thử lại.', 'Vui lòng nhập địa chỉ email hợp lệ.'],
}

export function loginText(locale: string, reason: LoginReason): string {
  const index = ['failed', 'credentials', 'rate', 'service', 'timeout', 'email'].indexOf(reason)
  return (loginMessages[locale] || loginMessages.en)![Math.max(0, index)]!
}
