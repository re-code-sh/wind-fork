# Multi-Account Vault & Launch Auto-Switching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a robust multi-account management system with isolated virtual device profiles and a launch auto-switch mechanism when bandwidth falls below 2GB in the Windscribe Android app.

**Architecture:** 
- **Room Data Layer**: Store multiple authenticated accounts in `account_vault` table with encrypted/isolated session hashes and unique virtual device identities (CUID, MAC, HostName).
- **Safe Repository Switch**: `AccountVaultRepository` coordinates switching active session tokens in SharedPreferences/DataStore and notifying `UserRepository` and `CdLib` without calling `api.deleteSession()`.
- **Launch Auto-Switch Pipeline**: `AutoSwitchEvaluator` checks `dataLeft < 2GB` on application launch and transparently swaps to the account with the highest remaining traffic.
- **Jetpack Compose UI**: Account screen displays accounts vault, quick-switch actions, and an in-app "Add Account" flow.

**Tech Stack:** Kotlin, Android Jetpack Compose, Room DB, Hilt, Coroutines & StateFlow, Windscribe Native Engine (CdLib).

**Spec:** `docs/superpowers/specs/2026-09-09-multi-account-vault-and-auto-switch-design.md`

## Global Constraints

- Never invoke `apiManager.deleteSession()` during account switching.
- Auto-switch threshold: exactly 2GB ($2{,}147{,}483{,}648$ bytes).
- Each account must retain a deterministic/persistent virtual device profile (CUID, MAC, HostName) across all sessions.
- Preserve backward compatibility with existing single-account preference keys (`session_auth_hash`, `user_session`).

---

### Task 1: Data Layer - `AccountEntity`, `AccountDao`, and Room Database Integration

**Files:**
- Create: `base/src/main/java/com/windscribe/vpn/localdatabase/tables/AccountEntity.kt`
- Create: `base/src/main/java/com/windscribe/vpn/localdatabase/AccountDao.kt`
- Modify: `base/src/main/java/com/windscribe/vpn/localdatabase/WindscribeDatabase.kt`
- Modify: `base/src/main/java/com/windscribe/vpn/localdatabase/LocalDbInterface.kt`
- Modify: `base/src/main/java/com/windscribe/vpn/localdatabase/LocalDatabaseImpl.kt`
- Test: `base/src/test/java/com/windscribe/vpn/localdatabase/AccountDaoTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class AccountEntity(
      val id: Long = 0,
      val username: String,
      val sessionAuthHash: String,
      val rawSessionJson: String,
      val dataLeft: Long,
      val trafficMax: Long,
      val trafficUsed: Long,
      val isPro: Boolean,
      val isActive: Boolean,
      val virtualCuid: String,
      val virtualMac: String,
      val virtualHostName: String,
      val sessionStatus: String = "VALID",
      val lastSyncTimestamp: Long = System.currentTimeMillis()
  )
  interface AccountDao {
      suspend fun insertOrUpdate(account: AccountEntity): Long
      suspend fun getActiveAccount(): AccountEntity?
      fun getAllAccounts(): Flow<List<AccountEntity>>
      suspend fun getAllAccountsSync(): List<AccountEntity>
      suspend fun getAccountById(id: Long): AccountEntity?
      suspend fun getAccountByUsername(username: String): AccountEntity?
      suspend fun setActiveAccount(id: Long)
      suspend fun deleteAccountById(id: Long)
      suspend fun updateTraffic(id: Long, dataUsed: Long, dataMax: Long, dataLeft: Long)
  }
  ```

- [ ] **Step 1: Write the failing unit test for `AccountDao`**
  Create `AccountDaoTest.kt` testing CRUD, active account toggling, and traffic querying.

- [ ] **Step 2: Create `AccountEntity.kt`**
  Implement Room entity with tableName `account_vault`.

- [ ] **Step 3: Create `AccountDao.kt`**
  Implement queries with `@Transaction` for `setActiveAccount(id: Long)`.

- [ ] **Step 4: Register entity in `WindscribeDatabase.kt`**
  Add `AccountEntity::class` to `entities`, increment database version or configure migration, and expose `abstract fun accountDao(): AccountDao`.

- [ ] **Step 5: Run tests and verify they pass**
  Run: `./gradlew :base:testDebugUnitTest --tests "com.windscribe.vpn.localdatabase.AccountDaoTest"`

---

### Task 2: Virtual Device Profile & Anti-Fingerprint Generator

**Files:**
- Create: `base/src/main/java/com/windscribe/vpn/backend/VirtualDeviceProfile.kt`
- Create: `base/src/main/java/com/windscribe/vpn/backend/VirtualDeviceManager.kt`
- Modify: `base/src/main/java/com/windscribe/vpn/backend/CdLib.kt`
- Test: `base/src/test/java/com/windscribe/vpn/backend/VirtualDeviceManagerTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class VirtualDeviceProfile(
      val cuid: String,
      val macAddress: String,
      val hostName: String
  )
  class VirtualDeviceManager {
      fun generateNewProfile(username: String): VirtualDeviceProfile
      fun applyProfileToCd(profile: VirtualDeviceProfile, cdLib: CdLib)
  }
  ```

- [ ] **Step 1: Write test for `VirtualDeviceManager`**
  Verify generated CUID is valid UUID, MAC has 6 hex pairs, and profiles generated for different accounts are strictly distinct.

- [ ] **Step 2: Implement `VirtualDeviceProfile.kt` and `VirtualDeviceManager.kt`**
  Implement generator logic formatting random MAC and hostnames.

- [ ] **Step 3: Update `CdLib.kt`**
  Allow overriding hostname, mac address, and cuid dynamically from the active virtual profile.

- [ ] **Step 4: Run tests and verify they pass**
  Run: `./gradlew :base:testDebugUnitTest --tests "com.windscribe.vpn.backend.VirtualDeviceManagerTest"`

---

### Task 3: `AccountVaultRepository` & Safe Session Switching

**Files:**
- Create: `base/src/main/java/com/windscribe/vpn/repository/AccountVaultRepository.kt`
- Create: `base/src/main/java/com/windscribe/vpn/repository/AccountVaultRepositoryImpl.kt`
- Modify: `base/src/main/java/com/windscribe/vpn/repository/UserRepository.kt`
- Modify: `base/src/main/java/com/windscribe/vpn/di/ApplicationModule.kt`
- Test: `base/src/test/java/com/windscribe/vpn/repository/AccountVaultRepositoryTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  interface AccountVaultRepository {
      val activeAccount: StateFlow<AccountEntity?>
      val allAccounts: Flow<List<AccountEntity>>
      suspend fun addOrUpdateAccount(loginResponse: UserLoginResponse, sessionResponse: UserSessionResponse): Long
      suspend fun switchToAccount(accountId: Long): Boolean
      suspend fun removeAccount(accountId: Long)
      suspend fun refreshCurrentAccountTraffic(): Result<UserSessionResponse>
  }
  ```

- [ ] **Step 1: Write failing unit test for `AccountVaultRepository`**
  Test adding multiple accounts, verifying `switchToAccount` updates preferences without calling `deleteSession()`, and verifying `activeAccount` state emissions.

- [ ] **Step 2: Implement `AccountVaultRepositoryImpl.kt`**
  Implement logic to update `preferenceHelper.sessionHash`, `preferenceHelper.getSession`, inject virtual profile to `CdLib`, and trigger `userRepository.reload()`.

- [ ] **Step 3: Wire in Hilt `ApplicationModule.kt`**
  Provide singleton instance of `AccountVaultRepository`.

- [ ] **Step 4: Run tests and verify they pass**
  Run: `./gradlew :base:testDebugUnitTest --tests "com.windscribe.vpn.repository.AccountVaultRepositoryTest"`

---

### Task 4: Launch Auto-Switch Engine (`AutoSwitchEvaluator`)

**Files:**
- Create: `base/src/main/java/com/windscribe/vpn/autoswitch/AutoSwitchEvaluator.kt`
- Modify: `mobile/src/main/java/com/windscribe/mobile/ui/auth/AppStartViewModel.kt`
- Modify: `mobile/src/main/java/com/windscribe/mobile/ui/AppStartActivity.kt`
- Test: `base/src/test/java/com/windscribe/vpn/autoswitch/AutoSwitchEvaluatorTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  sealed class AutoSwitchResult {
      object NoAction : AutoSwitchResult()
      data class Switched(
          val previousUsername: String,
          val newUsername: String,
          val newDataLeft: Long,
          val isEmergency: Boolean
      ) : AutoSwitchResult()
      data class NoEligibleAccount(val currentDataLeft: Long) : AutoSwitchResult()
  }
  class AutoSwitchEvaluator(
      private val accountVaultRepository: AccountVaultRepository,
      private val accountDao: AccountDao
  ) {
      suspend fun evaluateOnLaunch(): AutoSwitchResult
  }
  ```

- [ ] **Step 1: Write unit tests for `AutoSwitchEvaluatorTest`**
  - Case 1: active account data >= 2GB -> NoAction
  - Case 2: active account data < 2GB & candidate accounts available -> Switched to highest data
  - Case 3: all accounts < 2GB -> Switched to highest available with `isEmergency = true`

- [ ] **Step 2: Implement `AutoSwitchEvaluator.kt`**
  Implement evaluation and auto-switch trigger using `THRESHOLD_BYTES = 2_147_483_648L`.

- [ ] **Step 3: Integrate into `AppStartActivity.kt` and `AppStartViewModel.kt`**
  Before navigating to `Screen.Home`, invoke `evaluateOnLaunch()`. If switched, post a notification message/toast to show in UI.

- [ ] **Step 4: Run tests and verify they pass**
  Run: `./gradlew :base:testDebugUnitTest --tests "com.windscribe.vpn.autoswitch.AutoSwitchEvaluatorTest"`

---

### Task 5: Jetpack Compose UI - Account Vault Management & "Add Account" Flow

**Files:**
- Modify: `mobile/src/main/java/com/windscribe/mobile/ui/preferences/account/AccountViewModel.kt`
- Modify: `mobile/src/main/java/com/windscribe/mobile/ui/preferences/account/AccountScreen.kt`
- Create: `mobile/src/main/java/com/windscribe/mobile/ui/preferences/account/AccountVaultSection.kt`
- Test: `mobile/src/test/java/com/windscribe/mobile/ui/preferences/account/AccountViewModelTest.kt`

- [ ] **Step 1: Update `AccountViewModel.kt`**
  Expose `accountsList: StateFlow<List<AccountEntity>>`, `activeAccount: StateFlow<AccountEntity?>`, `onSwitchAccount(id: Long)`, and `onRemoveAccount(id: Long)`.

- [ ] **Step 2: Build `AccountVaultSection.kt` Composable**
  Create UI components matching Windscribe design theme:
  - Active account card with green badge.
  - Inactive account list items with username, data progress, and "Switch" button.
  - "Add Another Account" button opening authentication dialog/activity.
  - Auto-switch toggle setting.

- [ ] **Step 3: Embed `AccountVaultSection` inside `AccountScreen.kt`**
  Assemble components in the account settings screen.

- [ ] **Step 4: Run tests and verify UI logic**
  Run: `./gradlew :mobile:testGoogleDebugUnitTest --tests "com.windscribe.mobile.ui.preferences.account.AccountViewModelTest"`

---

### Task 6: Full Verification & E2E Integration

**Files:**
- Modify: `mobile/src/main/java/com/windscribe/mobile/ui/AppStartActivity.kt`
- Test: Manual verification in debug build

- [ ] **Step 1: Build the debug APK**
  Run: `./gradlew assembleDebug` or `./gradlew :mobile:assembleGoogleDebug`

- [ ] **Step 2: Verify End-to-End Flow**
  - Log in with Account 1.
  - Add Account 2 via Account Settings.
  - Switch between accounts; verify IP/session updates cleanly without logout.
  - Simulate Account 1 remaining data < 2GB; restart app; verify automatic switch to Account 2 with snackbar notification.
