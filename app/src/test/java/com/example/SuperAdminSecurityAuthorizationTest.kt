package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.BusinessAccountStatus
import com.example.data.model.PlatformBusiness
import com.example.data.model.SubscriptionStatus
import com.example.data.model.User
import com.example.data.model.UserAccountStatus
import com.example.data.model.UserRole
import com.example.data.repository.BusinessRepository
import com.example.data.repository.SuperAdminRepository
import com.example.data.sync.CloudBusinessAuth
import com.example.viewmodel.BusinessViewModel
import com.example.viewmodel.SuperAdminViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SuperAdminSecurityAuthorizationTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var repo: BusinessRepository
    private lateinit var superAdminRepo: SuperAdminRepository
    private lateinit var viewModel: BusinessViewModel
    private lateinit var superAdminViewModel: SuperAdminViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
        CloudBusinessAuth.clear(app)
        val directExecutor = java.util.concurrent.Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        AppDatabase.setInstanceForTesting(db)
        superAdminRepo = SuperAdminRepository.getInstance(app)
        superAdminRepo.resetStateForTesting()
        repo = BusinessRepository(db)
        viewModel = BusinessViewModel(app)
        superAdminViewModel = SuperAdminViewModel(app, superAdminRepo)
    }

    @After
    fun tearDown() {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        viewModel.syncManager.cancelActiveSync()
        CloudBusinessAuth.clear(app)
        superAdminRepo.resetStateForTesting()
        AppDatabase.setInstanceForTesting(null)
        Dispatchers.resetMain()
        db.close()
    }

    /**
     * Scenario 1: অনুমোদিত Platform Super Admin লগইন করলে Super Admin বাটন দেখা যায় এবং প্যানেল খোলে।
     */
    @Test
    fun `scenario 1 - authorized Super Admin Google login enables Super Admin button and grants portal access`() = runTest(testDispatcher) {
        val superAdminEmail = "ataulgonee786@gmail.com"
        assertTrue("Email must be in authorized super admin list", SuperAdminRepository.isSuperAdminEmail(superAdminEmail))

        var loginSuccess = false
        var assignedRole: UserRole? = null

        viewModel.handleGoogleUserCredentials(
            uid = "super_admin_ataul_uid",
            email = superAdminEmail,
            displayName = "আতাউল গনী",
            phoneNumber = "01700000000"
        ) { success, _, role ->
            loginSuccess = success
            assignedRole = role
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("Super admin login must succeed", loginSuccess)
        assertEquals("Super admin is assigned ADMIN role", UserRole.ADMIN, assignedRole)
        assertTrue("Super Admin button must be shown (isSuperAdminUser == true)", viewModel.isSuperAdminUser())
        assertEquals("getSuperAdminEmail must return authorized email", superAdminEmail, viewModel.getSuperAdminEmail())
        assertTrue("SuperAdminRepository validates super admin email", SuperAdminRepository.isSuperAdminEmail(viewModel.getSuperAdminEmail()))
    }

    /**
     * Scenario 2: নতুন Google দিয়ে দোকান রেজিস্ট্রেশন করলে Super Admin বাটন দেখা যায় না।
     */
    @Test
    fun `scenario 2 - newly registered shop owner NEVER sees Super Admin button`() = runTest(testDispatcher) {
        var registerSuccess = false
        var newShopId: String? = null

        viewModel.registerNewShop(
            shopName = "মিরপুর বস্ত্র বিতান",
            ownerName = "মোহাম্মদ রফিক",
            phone = "01712345678",
            address = "মিরপুর ১, ঢাকা",
            district = "ঢাকা",
            businessType = "বস্ত্র ও গার্মেন্টস",
            pin = "7890",
            isPhoneVerified = true
        ) { success, _, shopId ->
            registerSuccess = success
            newShopId = shopId
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("Registration must succeed", registerSuccess)
        assertNotNull("ShopId must be generated", newShopId)

        val currentUser = viewModel.currentUser.value
        assertNotNull("Current user must be set", currentUser)
        assertEquals(UserRole.ADMIN, currentUser!!.role)
        assertEquals(newShopId, currentUser.shopId)

        // CRITICAL CHECK: Super Admin button must NEVER be visible for new shop owner
        assertFalse(
            "New shopkeeper must NEVER have Super Admin button enabled!",
            viewModel.isSuperAdminUser()
        )
        assertEquals("getSuperAdminEmail must return empty string for new shop owner", "", viewModel.getSuperAdminEmail())
    }

    /**
     * Scenario 3: সাধারণ দোকানের Admin লগইন করলে Super Admin বাটন দেখা যায় না।
     */
    @Test
    fun `scenario 3 - regular shop store admin login does NOT see Super Admin button`() = runTest(testDispatcher) {
        val storeAdminPinHash = repo.hashPin("1234")
        val storeAdminUser = User(
            id = "store_admin_001",
            name = "দোকান অ্যাডমিন",
            phone = "01711223344",
            role = UserRole.ADMIN,
            shopId = "biz_store_9999",
            pinHash = storeAdminPinHash,
            isActive = true,
            accountStatus = UserAccountStatus.ACTIVE
        )
        repo.saveUserDirect(storeAdminUser)

        var loginSuccess = false
        viewModel.loginAdmin("01711223344", "1234") { success, _ ->
            loginSuccess = success
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("Store admin login must succeed", loginSuccess)
        assertEquals(UserRole.ADMIN, viewModel.currentUser.value?.role)
        assertEquals("biz_store_9999", viewModel.currentUser.value?.shopId)

        // CRITICAL: Regular shop admin must NOT see Super Admin button
        assertFalse(
            "Regular store admin must NOT see Platform Super Admin button!",
            viewModel.isSuperAdminUser()
        )
        assertEquals("", viewModel.getSuperAdminEmail())
    }

    /**
     * Scenario 4: Salesman লগইন করলে Super Admin বাটন দেখা যায় না।
     */
    @Test
    fun `scenario 4 - salesman login does NOT see Super Admin button`() = runTest(testDispatcher) {
        val salesmanPinHash = repo.hashPin("4321")
        val salesmanUser = User(
            id = "salesman_001",
            name = "সেলসম্যান জামান",
            phone = "01855667788",
            role = UserRole.SALESMAN,
            shopId = "biz_store_9999",
            pinHash = salesmanPinHash,
            isActive = true,
            accountStatus = UserAccountStatus.ACTIVE
        )
        repo.saveUserDirect(salesmanUser)

        var loginSuccess = false
        viewModel.login("01855667788", "4321") { success, _ ->
            loginSuccess = success
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("Salesman login must succeed", loginSuccess)
        assertEquals(UserRole.SALESMAN, viewModel.currentUser.value?.role)

        // CRITICAL: Salesman must NEVER see Super Admin button
        assertFalse(
            "Salesman must NEVER see Super Admin button!",
            viewModel.isSuperAdminUser()
        )
        assertEquals("", viewModel.getSuperAdminEmail())
    }

    /**
     * Scenario 5: সাধারণ ব্যবহারকারী সরাসরি Super Admin screen বা protected function খোলার চেষ্টা করলে access denied হয়।
     */
    @Test
    fun `scenario 5 - unauthorized user calling protected Super Admin repository functions is denied`() = runTest(testDispatcher) {
        val regularUserEmail = "regular.owner@gmail.com"
        assertFalse(SuperAdminRepository.isSuperAdminEmail(regularUserEmail))

        val businesses = superAdminRepo.businesses.value
        assertTrue("Platform businesses must exist", businesses.isNotEmpty())
        val target = businesses.first()

        // 1. Try to suspend business with unauthorized email
        val suspendResult = superAdminRepo.updateBusinessStatus(
            businessId = target.id,
            newStatus = BusinessAccountStatus.SUSPENDED,
            reason = "অবৈধ স্থগিতের চেষ্টা",
            actorUid = "unauth_user",
            actorName = "অসাধু ব্যবহারকারী",
            actorEmail = regularUserEmail
        )
        assertTrue("Attempt to update business status by unauthorized email must FAIL", suspendResult.isFailure)
        assertTrue("Exception must be SecurityException", suspendResult.exceptionOrNull() is SecurityException)

        // 2. Try to change business plan with unauthorized email
        val planResult = superAdminRepo.updateBusinessPlan(
            businessId = target.id,
            planId = "plan_enterprise",
            durationDays = 30,
            actorUid = "unauth_user",
            actorName = "অসাধু ব্যবহারকারী",
            actorEmail = regularUserEmail
        )
        assertTrue("Attempt to change plan by unauthorized email must FAIL", planResult.isFailure)
        assertTrue("Exception must be SecurityException", planResult.exceptionOrNull() is SecurityException)

        // 3. Try to review payment with unauthorized email
        val reviewResult = superAdminRepo.reviewPayment(
            paymentId = "pay_test_01",
            isApproved = true,
            rejectionReason = "",
            actorUid = "unauth_user",
            actorName = "অসাধু ব্যবহারকারী",
            actorEmail = regularUserEmail
        )
        assertTrue("Attempt to approve payment by unauthorized email must FAIL", reviewResult.isFailure)
        assertTrue("Exception must be SecurityException", reviewResult.exceptionOrNull() is SecurityException)

        // 4. Try via SuperAdminViewModel
        superAdminViewModel.suspendBusiness(
            business = target,
            reason = "অবৈধ চেষ্টা",
            actorUid = "unauth_user",
            actorName = "অসাধু ব্যবহারকারী",
            actorEmail = regularUserEmail
        )
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(
            "ViewModel UI state must reflect unauthorized error",
            superAdminViewModel.uiState.value.errorMessage?.contains("অননুমোদিত") == true
        )
    }

    /**
     * Scenario 6: Logout করে অন্য দোকানদারের অ্যাকাউন্টে লগইন করলে আগের Super Admin access থেকে যায় না।
     */
    @Test
    fun `scenario 6 - logging out of Super Admin and logging into shopkeeper account completely clears Super Admin privileges`() = runTest(testDispatcher) {
        // Step A: Login as Super Admin
        val superAdminEmail = "ataulgonee786@gmail.com"
        viewModel.handleGoogleUserCredentials(
            uid = "super_admin_uid_786",
            email = superAdminEmail,
            displayName = "সুপার এডমিন",
            phoneNumber = null
        ) { _, _, _ -> }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.isSuperAdminUser())

        // Step B: Logout
        viewModel.logout()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull("Current user must be null after logout", viewModel.currentUser.value)
        assertFalse("Super admin flag must be reset upon logout", viewModel.isSuperAdminUser())
        assertEquals("", viewModel.getSuperAdminEmail())

        // Step C: Login as another shopkeeper (Phone & PIN)
        val shopAdmin = User(
            id = "shopkeeper_55",
            name = "দোকানদার শফিক",
            phone = "01733445566",
            role = UserRole.ADMIN,
            shopId = "biz_shafiq_store",
            pinHash = repo.hashPin("9999"),
            isActive = true,
            accountStatus = UserAccountStatus.ACTIVE
        )
        repo.saveUserDirect(shopAdmin)

        viewModel.loginAdmin("01733445566", "9999") { _, _ -> }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("shopkeeper_55", viewModel.currentUser.value?.id)
        // CRITICAL CHECK: Previous Super Admin access MUST NOT leak into the new user!
        assertFalse(
            "Previous Super Admin access must NOT persist when logging into shopkeeper account!",
            viewModel.isSuperAdminUser()
        )
        assertEquals("", viewModel.getSuperAdminEmail())
    }

    /**
     * Scenario 7: অ্যাপ পুনরায় চালু ও session restore করার পরেও role যাচাই সঠিক থাকে।
     */
    @Test
    fun `scenario 7 - session restore properly validates role and refuses unauthorized Super Admin privilege`() = runTest(testDispatcher) {
        // Test A: Normal shop owner does NOT get Super Admin upon ViewModel recreation / app restart
        CloudBusinessAuth.clear(app)
        CloudBusinessAuth.setStoredBusinessId(app, "biz_test_shop_10", "টেস্ট শপ", UserRole.ADMIN.name)
        CloudBusinessAuth.setStoredRole(app, UserRole.ADMIN.name)
        CloudBusinessAuth.setStoredSuperAdminFlag(app, false)
        CloudBusinessAuth.setStoredAuthEmail(app, null)

        val freshViewModel = BusinessViewModel(app)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(
            "Restored normal shop session must NOT be granted Super Admin access",
            freshViewModel.isSuperAdminUser()
        )
        assertEquals("", freshViewModel.getSuperAdminEmail())

        // Test B: If a deactivated/revoked superadmin account tries to restore, access is refused
        val revokedSuperAdmin = User(
            id = "revoked_superadmin_uid",
            name = "সাবেক সুপার এডমিন",
            phone = "01700000000",
            role = UserRole.ADMIN,
            pinHash = repo.hashPin("1234"),
            isActive = false, // Deactivated!
            accountStatus = UserAccountStatus.SUSPENDED
        )
        repo.saveUserDirect(revokedSuperAdmin)

        CloudBusinessAuth.setStoredAuthEmail(app, "ataulgonee786@gmail.com")
        CloudBusinessAuth.setStoredSuperAdminFlag(app, true)

        val checkViewModel = BusinessViewModel(app)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        testDispatcher.scheduler.advanceUntilIdle()

        // Because account is inactive, Super Admin user check must return false
        assertFalse(
            "Inactive or suspended account must NEVER have Super Admin access",
            checkViewModel.isSuperAdminUser()
        )
    }
}
