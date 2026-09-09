# Multi-Account Vault & Launch Auto-Switching Design Specification

**Status:** Approved  
**Date:** 2026-09-09  
**Platform:** Android (Windscribe Android App)  
**Target Codebase:** `com.windscribe.vpn` (Kotlin, Jetpack Compose, Room, Hilt, C++ / CdLib)

---

## ۱. هدف و چشم‌انداز (Goals & Problem Statement)

در نسخه رسمی اپلیکیشن وایندسکرایب اندروید، معماری سیستم احراز هویت تک‌کاربره است:
1. ورود کاربر مستلزم یک `session_auth_hash` واحد در کش محلی است.
2. خروج از حساب (`logout`) مستقیماً متد `api.deleteSession()` را روی سرور فراخوانی می‌کند که موجب ابطال سشن روی سرور و پاکسازی کل داده‌های محلی می‌شود.
3. شناسه‌های سخت‌افزاری دستگاه نظیر `AppSetId` (که به مک‌ادرس تبدیل می‌شود)، `cuid` و نام دستگاه به طور یکسان برای هر نشستی ارسال می‌شود که در صورت استفاده از چند اکانت رایگان، موجب شناسایی دستگاه توسط سیستم‌های ضدتقلب (Sybil/Abuse Detection) سرور و مسدودسازی یا اعمال محدودیت بر حساب‌ها می‌گردد.

**هدف این پروژه:**
توسعه یک فورک پیشرفته با پشتیبانی از چند حساب کاربری هم‌زمان (Multi-Account)، ایزوله‌سازی کامل اثرانگشت و شناسه‌های سخت‌افزاری (Virtual Device Profiling)، و قابلیت سوییچ خودکار بر اساس حجم باقی‌مانده (Auto-Switch زیر ۲ گیگابایت در زمان لانچ) بدون ابطال سشن‌ها و بدون تریگر شدن سیستم‌های امنیتی سرور وایندسکرایب.

---

## ۲. معماری کلان سیستم (High-Level Architecture)

```
+-------------------------------------------------------------+
|                      Jetpack Compose UI                     |
|  - AccountScreen: Accounts Vault, Add Account, Switch Tap   |
|  - AppStartActivity: Launch Pipeline & Low Data Notification|
+-------------------------------------------------------------+
                               |
                               v
+-------------------------------------------------------------+
|                     AutoSwitchEvaluator                     |
|  - Evaluates dataLeft < 2GB on launch                       |
|  - Queries candidate accounts (dataLeft >= 2GB)             |
|  - Fallback to max available data if all accounts exhausted |
+-------------------------------------------------------------+
                               |
                               v
+-------------------------------------------------------------+
|                   AccountVaultRepository                    |
|  - Coordinates active session switching                     |
|  - Manages AccountEntity CRUD & atomic state updates        |
|  - Injects virtual device credentials to CdLib/Network      |
|  - Avoids calling api.deleteSession() on switch             |
+-------------------------------------------------------------+
             |                                    |
             v                                    v
+------------------------+          +------------------------+
|   Room Database        |          |     CdLib & Network    |
|   (account_vault table)|          | (Virtual CUID/MAC/Name)|
+------------------------+          +------------------------+
```

---

## ۳. لایه داده و ایزوله‌سازی هویت (Data Layer & Virtual Identity)

### ۳.۱. مدل داده `AccountEntity` (Room Table: `account_vault`)
```kotlin
@Entity(tableName = "account_vault")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val username: String,
    val sessionAuthHash: String,
    val rawSessionJson: String,
    val dataLeft: Long,
    val trafficMax: Long,
    val trafficUsed: Long,
    val isPro: Boolean,
    val isActive: Boolean,
    // Virtual Device Profile
    val virtualCuid: String,
    val virtualMac: String,
    val virtualHostName: String,
    val sessionStatus: String = "VALID", // VALID, EXPIRED, SUSPENDED
    val lastSyncTimestamp: Long = System.currentTimeMillis()
)
```

### ۳.۲. ایزوله‌سازی ضدتشخیص (Virtual Device Profiling)
برای پیشگیری از تحلیل رفتاری و ارتباط‌دهی اکانت‌ها توسط سرور وایندسکرایب:
1. **Virtual CUID:** هنگام اولین لاگین موفق یک اکانت، یک شناسه کلاینت یکتا به فرمت UUID تصادفی تولید و ذخیره می‌شود:
   `virtualCuid = UUID.randomUUID().toString()`
2. **Virtual MAC Address:** یک مقدار هگزادسیمال ۱۲ رقمی معتبر فرمت‌شده به صورت مک‌ادرس:
   `XX:XX:XX:XX:XX:XX` که به صورت مجزا برای هر حساب ساخته می‌شود.
3. **Virtual Host Name:** نام دستگاه مجازی استاندارد (مانند `Galaxy-A54` یا `Pixel-7`) تولید و منتسب می‌گردد.
4. **تزریق در زمان سوییچ:** متدهای `CdLib.getHostName()`, `CdLib.getMacAddress()` و پارامتر `cuid` ورودی تابع نیتیو `CdLib.startCd` این مقادیر مجازی را از اکانت فعال جاری دریافت می‌کنند.

---

## ۴. لایه ریپازیتوری و سوییچ امن (Repository & Safe Switch Flow)

### ۴.۱. اینترفیس `AccountVaultRepository`
```kotlin
interface AccountVaultRepository {
    val activeAccount: StateFlow<AccountEntity?>
    val allAccounts: Flow<List<AccountEntity>>
    
    suspend fun addOrUpdateAccount(
        loginResponse: UserLoginResponse,
        sessionResponse: UserSessionResponse
    ): Long
    
    suspend fun switchToAccount(accountId: Long): Boolean
    suspend fun deleteAccount(accountId: Long, terminateOnServer: Boolean)
    suspend fun updateTraffic(accountId: Long, dataUsed: Long, dataMax: Long)
    suspend fun evaluateLaunchAutoSwitch(): AutoSwitchResult
}
```

### ۴.۲. الگوریتم سوییچ بدون ابطال (`switchToAccount`)
1. **بررسی اتصال VPN:** در صورت فعال بودن تونل، با فراخوانی `vpnController.disconnectAsync()` اتصال موقتاً قطع می‌شود تا تداخل کلید رخ ندهد.
2. **تغییر پرچم اکانت در دیتابیس:** در یک تراکنش Room:
   * مقدار `isActive = false` برای تمام رکوردها.
   * مقدار `isActive = true` برای رکورد با شناسه `accountId`.
3. **به‌روزرسانی کش پرفرنس‌ها:**
   * `preferencesHelper.sessionHash = targetAccount.sessionAuthHash`
   * `preferencesHelper.getSession = targetAccount.rawSessionJson`
   * `preferencesHelper.userName = targetAccount.username`
   * `preferencesHelper.userStatus = if (targetAccount.isPro) 1 else 0`
4. **تزریق مقادیر هویتی مجازی به لایه نیتیو:** شناسه `cuid` و مک‌ادرس مجازی اکانت جدید برای نشست بعدی به `CdLib` پاس داده می‌شود.
5. **بازخوانی وضعیت و اطلاع‌رسانی به UI:** فراخوانی متد `userRepository.reload()` که `StateFlow<User?>` را به مقادیر اکانت جدید به‌روزرسانی می‌کند.
6. **عدم صدا زدن `apiManager.deleteSession()`:** این فراخوانی به صورت قطعی در فرآیند سوییچ حذف و بای‌پس می‌شود.

---

## ۵. موتور اتوسوییچ در زمان لانچ (Launch Auto-Switch Pipeline)

### ۵.۱. شرط آستانه ترافیک (۲ گیگابایت)
$$\text{THRESHOLD\_BYTES} = 2 \times 1024 \times 1024 \times 1024 = 2{,}147{,}483{,}648 \text{ Bytes}$$

### ۵.۲. منطق ارزیابی در `AutoSwitchEvaluator`
در متد `onCreate` کلاس `AppStartActivity` یا `AppStartViewModelImpl.init`:
```kotlin
suspend fun evaluateLaunchAutoSwitch(): AutoSwitchResult {
    val currentActive = accountDao.getActiveAccount() ?: return AutoSwitchResult.NoAction
    
    if (currentActive.dataLeft >= THRESHOLD_BYTES) {
        return AutoSwitchResult.NoAction
    }
    
    // جستجوی بهترین اکانت با حجم بالای ۲ گیگابایت
    val eligibleAccounts = accountDao.getAccountsWithDataAbove(THRESHOLD_BYTES)
    val bestAccount = if (eligibleAccounts.isNotEmpty()) {
        eligibleAccounts.maxByOrNull { it.dataLeft }!!
    } else {
        // سناریوی مرزی: هیچ حسابی بالای ۲ گیگابایت ندارد -> انتخاب بالاترین حجم موجود
        accountDao.getAllAccountsSync()
            .filter { it.id != currentActive.id && it.sessionStatus == "VALID" }
            .maxByOrNull { it.dataLeft }
    }
    
    return if (bestAccount != null && bestAccount.id != currentActive.id) {
        switchToAccount(bestAccount.id)
        val isEmergency = bestAccount.dataLeft < THRESHOLD_BYTES
        AutoSwitchResult.Switched(
            previousUsername = currentActive.username,
            newUsername = bestAccount.username,
            newDataLeft = bestAccount.dataLeft,
            isEmergency = isEmergency
        )
    } else {
        AutoSwitchResult.NoEligibleAccount(currentActive.dataLeft)
    }
}
```

### ۵.۳. بازخورد به کاربر (User Feedback)
هنگامی که سوییچ انجام شد، یک Toast یا Snackbar با مضمون زیر نمایش داده می‌شود:
* در حالت عادی: *«به حساب {newUsername} با {X.X} گیگابایت حجم باقیمانده منتقل شدید.»*
* در شرایط اضطراری (تمام اکانت‌ها زیر ۲ گیگ): *«به حساب {newUsername} منتقل شدید. تمامی حساب‌ها کمتر از ۲ گیگابایت حجم دارند.»*

---

## ۶. تغییرات رابط کاربری در Jetpack Compose

### ۶.۱. صفحه تنظیمات حساب کاربری (`AccountScreen`)
1. **کارت حساب جاری (Active Account Card):**
   * بج سبز «حساب فعال» (Active).
   * نمودار میله‌ای حجم مصرفی و دیتای باقیمانده.
2. **فهرست حساب‌های ذخیره‌شده (Accounts Vault List):**
   * کارت‌های فشرده برای هر اکانت با نمایش یوزرنیم، حجم آزاد، و برچسب وضعیت.
   * دکمه سوییچ تک‌کلیکه (Switch).
   * دکمه سه‌نقطه جهت حذف دستی اکانت از مخزن.
3. **دکمه افزودن حساب جدید (Add Account):**
   * فرم ورود استاندارد اپلیکیشن بدون لاگ‌اوت کردن حساب قبلی باز می‌شود. پس از تأیید موفق کپچا و توکن سشن، اکانت جدید ذخیره و فعال می‌شود.
4. **سوییچ اتوسوییچ:**
   * تاگل «سوییچ خودکار زیر ۲ گیگابایت در هنگام باز شدن برنامه» جهت کنترل توسط کاربر.

---

## ۷. تاب‌آوری، مدیریت خطاها و امنیت (Resilience & Anti-Abuse)

1. **انقضای سشن یک اکانت (HTTP 401 / Token Expired):**
   * اگر اکانتی منقضی شد، کل اپ از لاگین خارج نمی‌شود؛ وضعیت آن اکانت در جدول به `EXPIRED` تغییر کرده و فوراً به بهترین اکانت بعدی سوییچ می‌شود.
2. **محافظت در برابر حملات نرخ تبادل (Rate Limit Protection):**
   * جلوگیری از کوئری‌های هم‌زمان شبکه در حین سوییچ.
   * متد استعلام حجم، حداقل ۱۰ دقیقه کش محلی دارد مگر آنکه کاربر دستی دکمه ریفرش را لمس کند.
3. **تراکنش‌های پایگاه‌داده (Atomic DB Operations):**
   * استفاده از `@Transaction` در Room تا از بروز حالت‌های ناهمگام (مثلاً نداشتن هیچ اکانت فعال) جلوگیری شود.

---

## ۸. پلن تست و اعتبارسنجی (Verification Plan)

### ۸.۱. تست‌های واحد خودکار (Automated Unit Tests)
* `AccountVaultRepositoryTest`: بررسی ثبت اکانت، تولید شناسه‌های مجازی منحصربه‌فرد، و عدم تکرار CUID/MAC بین اکانت‌ها.
* `AutoSwitchEvaluatorTest`:
  * اکانت بالای ۲ گیگابایت -> بدون سوییچ.
  * اکانت زیر ۲ گیگابایت و وجود اکانت‌های واجد شرایط -> سوییچ به اکانت دارای بیشترین حجم.
  * تمام اکانت‌ها زیر ۲ گیگابایت -> سوییچ به بالاترین حجم موجود و فلگ هشدار اضطراری.

### ۸.۲. تست یکپارچگی شبکه و نیتیو (Integration Tests)
* اعتبارسنجی فرآیند `switchToAccount` و تایید عدم فراخوانی متد `deleteSession()` در کلاینت شبکه.
* اطمینان از تزریق موفق مقادیر `virtualCuid` و `virtualMac` به آبجکت `CdLib`.

### ۸.۳. تست دستی در محیط شبیه‌ساز / دیوایس (Manual Verification)
* افزودن ۲ اکانت تستی از طریق UI.
* تغییر ساختگی حجم اکانت اول به ۱.۵ گیگابایت در دیتابیس محلی.
* بستن کامل برنامه و اجرای مجدد.
* مشاهده انتقال خودکار به اکانت دوم و باز شدن مستقیم صفحه Home با دیتای اکانت جدید.
