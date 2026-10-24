package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.User
import com.example.data.model.UserAccountStatus
import com.example.data.model.UserRole
import com.example.data.repository.BusinessRepository
import com.example.viewmodel.BusinessViewModel
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
class GoogleAuthRoleMappingTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var repo: BusinessRepository
    private lateinit var viewModel: BusinessViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
        com.example.data.sync.CloudBusinessAuth.clear(app)
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = BusinessRepository(db)
        viewModel = BusinessViewModel(app)
    }

    @After
    fun tearDown() {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        viewModel.syncManager.cancelActiveSync()
        com.example.data.sync.CloudBusinessAuth.clear(app)
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `test unknown Google account is strictly rejected and never granted admin role`() = runTest(testDispatcher) {
        var callbackSuccess: Boolean? = null
        var callbackMsg: String? = null
        var callbackRole: UserRole? = null

        viewModel.handleGoogleUserCredentials(
            uid = "unknown_uid_123",
            email = "stranger@unknown-domain.com",
            displayName = "অচেনা ব্যক্তি",
            phoneNumber = null
        ) { success, msg, role ->
            callbackSuccess = success
            callbackMsg = msg
            callbackRole = role
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        testDispatcher.scheduler.advanceUntilIdle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertFalse("Unknown Google user must not be granted access", callbackSuccess == true)
        assertNull("Role must be null for unauthorized account", callbackRole)
        assertNull("Current active user must remain null", viewModel.currentUser.value)
        assertTrue("Error message must inform user that account is not authorized", callbackMsg!!.contains("অনুমোদিত"))
    }

    @Test
    fun `test primary business owner Google email is recognized and assigned ADMIN role`() = runTest(testDispatcher) {
        var callbackSuccess: Boolean? = null
        var callbackRole: UserRole? = null

        viewModel.handleGoogleUserCredentials(
            uid = "owner_google_uid_001",
            email = "ataurgonee4@gmail.com",
            displayName = "আতাউর রহমান",
            phoneNumber = "01700000000"
        ) { success, _, role ->
            callbackSuccess = success
            callbackRole = role
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        testDispatcher.scheduler.advanceUntilIdle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertTrue("Primary business owner must succeed", callbackSuccess == true)
        assertEquals("Primary owner must be assigned ADMIN role", UserRole.ADMIN, callbackRole)
        assertEquals(UserRole.ADMIN, viewModel.currentUser.value?.role)
        assertEquals("owner_google_uid_001", viewModel.currentUser.value?.id)
    }

    @Test
    fun `test linking Google account with valid local salesman PIN assigns SALESMAN role`() = runTest(testDispatcher) {
        val salesmanPinHash = repo.hashPin("5555")
        val localSalesman = User(
            id = "salesman_local_01",
            name = "করিম সেলস",
            phone = "01811223344",
            role = UserRole.SALESMAN,
            pinHash = salesmanPinHash,
            isActive = true,
            accountStatus = UserAccountStatus.ACTIVE
        )
        viewModel.repository.saveUserDirect(localSalesman)

        var linkSuccess: Boolean? = null
        var linkRole: UserRole? = null

        viewModel.linkGoogleCredentialsWithPin(
            uid = "google_salesman_uid_777",
            displayName = "করিম শেখ",
            phone = "01811223344",
            pin = "5555"
        ) { success, _, role ->
            linkSuccess = success
            linkRole = role
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        testDispatcher.scheduler.advanceUntilIdle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertTrue("Linking with correct PIN must succeed", linkSuccess == true)
        assertEquals("Assigned role must be SALESMAN", UserRole.SALESMAN, linkRole)
        assertEquals("google_salesman_uid_777", viewModel.currentUser.value?.id)
        assertEquals(UserRole.SALESMAN, viewModel.currentUser.value?.role)
    }

    @Test
    fun `test linking Google account with wrong PIN fails and rejects login`() = runTest(testDispatcher) {
        val salesmanPinHash = repo.hashPin("5555")
        val localSalesman = User(
            id = "salesman_local_02",
            name = "করিম সেলস",
            phone = "01899887766",
            role = UserRole.SALESMAN,
            pinHash = salesmanPinHash,
            isActive = true,
            accountStatus = UserAccountStatus.ACTIVE
        )
        viewModel.repository.saveUserDirect(localSalesman)

        var linkSuccess: Boolean? = null

        viewModel.linkGoogleCredentialsWithPin(
            uid = "google_salesman_uid_888",
            displayName = "হ্যাকার",
            phone = "01899887766",
            pin = "wrong_pin"
        ) { success, _, _ ->
            linkSuccess = success
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        testDispatcher.scheduler.advanceUntilIdle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertFalse("Linking with incorrect PIN must fail", linkSuccess == true)
    }

    @Test
    fun `test phone normalization handles international prefix spaces and local formats`() {
        assertEquals("01811223344", com.example.data.sync.CloudBusinessAuth.normalizePhone("+8801811223344"))
        assertEquals("01811223344", com.example.data.sync.CloudBusinessAuth.normalizePhone("8801811223344"))
        assertEquals("01811223344", com.example.data.sync.CloudBusinessAuth.normalizePhone("01811-223344"))
        assertEquals("01811223344", com.example.data.sync.CloudBusinessAuth.normalizePhone(" 01811 223344 "))
        assertEquals("01700000000", com.example.data.sync.CloudBusinessAuth.normalizePhone("+8801700000000"))
    }

    @Test
    fun `test connectSalesmanToBusiness rejects blank inputs without network call`() {
        var callbackSuccess: Boolean? = null
        var callbackMsg: String? = null

        viewModel.connectSalesmanToBusiness(
            businessId = "",
            phone = "01811223344",
            pin = "1234"
        ) { success, msg ->
            callbackSuccess = success
            callbackMsg = msg
        }
        assertFalse("Blank business ID must be rejected", callbackSuccess == true)
        assertTrue("Error must mention business code", callbackMsg!!.contains("বিজনেস কোড"))
    }
}
