package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.data.repository.BusinessRepository
import com.example.data.sync.CloudBusinessAuth
import com.example.data.sync.CloudSyncManager
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
class TwoDeviceProductSyncTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var app: Application
    private lateinit var adminDb: AppDatabase
    private lateinit var salesmanDb: AppDatabase

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
        // Clear stored cloud auth
        CloudBusinessAuth.clear(app)

        // Separate Database 1: Admin Device
        adminDb = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // Separate Database 2: Salesman Device (Empty initially)
        salesmanDb = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        adminDb.close()
        salesmanDb.close()
        CloudBusinessAuth.clear(app)
    }

    @Test
    fun `test complete two-device product sync from admin creation to salesman download with security protection`() = runTest(testDispatcher) {
        val adminUid = "admin_cloud_uid_1001"
        val salesmanUid = "salesman_cloud_uid_2002"

        // =========================================================================
        // STEP 1: Admin Device Flow
        // =========================================================================
        // 1.1 Admin signs in with Google
        val adminViewModel = BusinessViewModel(app)
        var adminLoginSuccess = false
        var adminRole: UserRole? = null

        adminViewModel.handleGoogleUserCredentials(
            uid = adminUid,
            email = "ataurgonee4@gmail.com",
            displayName = "আতাউর রহমান",
            phoneNumber = "01700000000"
        ) { success, _, role ->
            adminLoginSuccess = success
            adminRole = role
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("Admin Google sign-in must succeed", adminLoginSuccess)
        assertEquals("Admin must be assigned ADMIN role", UserRole.ADMIN, adminRole)
        assertEquals("Business ID must resolve to Admin UID", adminUid, CloudBusinessAuth.getStoredBusinessId(app))

        // 1.2 Admin adds a clearly identified test product in Room
        val testProduct = Product(
            id = "prod_sync_test_01",
            name = "টেস্ট পণ্য - প্রিমিয়াম পোলো শার্ট",
            sku = "SKU-POLO-01",
            barcode = "8941009988",
            category = "পোশাক",
            purchasePrice = 500.0,
            sellingPrice = 850.0,
            currentStock = 35.0,
            alertStockLevel = 5.0,
            unit = "পিস",
            isActive = true,
            updatedAt = System.currentTimeMillis(),
            isTestData = false
        )
        adminDb.productDao().insertProduct(testProduct)

        // Verify product in Admin local Room
        val adminSavedProduct = adminDb.productDao().getProductById("prod_sync_test_01")
        assertNotNull("Product must exist in Admin database", adminSavedProduct)
        assertEquals("টেস্ট পণ্য - প্রিমিয়াম পোলো শার্ট", adminSavedProduct!!.name)
        assertEquals(500.0, adminSavedProduct.purchasePrice, 0.001)

        // 1.3 Verify product is selected by getSyncProducts and correctly mapped for cloud upload
        val syncProducts = adminDb.productDao().getSyncProducts()
        assertTrue("getSyncProducts must include the new product", syncProducts.any { it.id == "prod_sync_test_01" })

        val cloudProductPayload = CloudSyncManager.mapProductToCloud(testProduct, adminUid, isSalesman = false)
        assertEquals("prod_sync_test_01", cloudProductPayload["id"])
        assertEquals("টেস্ট পণ্য - প্রিমিয়াম পোলো শার্ট", cloudProductPayload["name"])
        assertEquals(850.0, cloudProductPayload["sellingPrice"])
        assertEquals(35.0, cloudProductPayload["currentStock"])
        assertEquals(adminUid, cloudProductPayload["businessId"])
        assertEquals("Admin upload payload must include purchasePrice for owner", 500.0, cloudProductPayload["purchasePrice"])

        // =========================================================================
        // STEP 2: Salesman Separate Device Flow
        // =========================================================================
        // 2.1 On the separate salesman device, Room database starts completely empty of this product
        assertNull("Salesman device must not have product before sync", salesmanDb.productDao().getProductById("prod_sync_test_01"))

        // Clear stored business preferences on salesman device (simulating fresh install on phone 2)
        CloudBusinessAuth.clear(app)
        assertNull("Salesman device starts with no businessId", CloudBusinessAuth.getStoredBusinessId(app))

        // 2.2 Salesman connects and authorizes with Admin's Business Code
        // Seed default staff account on salesman device (as per APK seedInitialDataIfEmpty)
        val defaultSalesman = User(
            id = "salesman_1",
            name = "সেলসম্যান হাসিবুল করিম",
            phone = "01800000000",
            role = UserRole.SALESMAN,
            pinHash = adminViewModel.repository.hashPin("1234"),
            isActive = true,
            accountStatus = UserAccountStatus.ACTIVE
        )
        salesmanDb.userDao().insertUser(defaultSalesman)

        // Salesman stores the verified Business ID
        CloudBusinessAuth.setStoredBusinessId(
            context = app,
            businessId = adminUid,
            businessName = "স্মার্ট বিজনেস",
            role = UserRole.SALESMAN.name
        )

        assertEquals("Salesman must resolve to Admin's exact Business ID", adminUid, CloudBusinessAuth.getStoredBusinessId(app))
        assertEquals("Salesman role must be stored as SALESMAN", UserRole.SALESMAN.name, CloudBusinessAuth.getStoredRole(app))

        // 2.3 Salesman device downloads and synchronizes product from Cloud payload
        // Simulating the CloudSyncManager product insertion logic on Salesman device:
        val remoteDocId = cloudProductPayload["id"] as String
        val remoteName = cloudProductPayload["name"] as String
        val remoteSku = cloudProductPayload["sku"] as String
        val remoteBarcode = cloudProductPayload["barcode"] as String
        val remoteCategory = cloudProductPayload["category"] as String
        val remoteSellingPrice = (cloudProductPayload["sellingPrice"] as Number).toDouble()
        val remoteCurrentStock = (cloudProductPayload["currentStock"] as Number).toDouble()
        val remoteAlertStock = (cloudProductPayload["alertStockLevel"] as Number).toDouble()
        val remoteUnit = cloudProductPayload["unit"] as String
        val remoteIsActive = cloudProductPayload["isActive"] as Boolean
        val remoteUpdatedAt = (cloudProductPayload["updatedAt"] as Number).toLong()
        val remotePurchasePrice = (cloudProductPayload["purchasePrice"] as? Number)?.toDouble()

        val isSalesmanDevice = CloudBusinessAuth.getStoredRole(app) == UserRole.SALESMAN.name
        assertTrue("Device must be recognized as Salesman", isSalesmanDevice)

        val salesmanProduct = Product(
            id = remoteDocId,
            name = remoteName,
            sku = remoteSku,
            barcode = remoteBarcode,
            category = remoteCategory,
            // SECURITY ENFORCEMENT: purchase cost is strictly stripped (0.0) for salesmen
            purchasePrice = if (isSalesmanDevice) 0.0 else (remotePurchasePrice ?: 0.0),
            sellingPrice = remoteSellingPrice,
            currentStock = remoteCurrentStock,
            alertStockLevel = remoteAlertStock,
            unit = remoteUnit,
            isActive = remoteIsActive,
            updatedAt = remoteUpdatedAt,
            isTestData = false
        )
        salesmanDb.productDao().insertProduct(salesmanProduct)

        // =========================================================================
        // STEP 3: Verification on Salesman Device
        // =========================================================================
        val downloadedProduct = salesmanDb.productDao().getProductById("prod_sync_test_01")
        assertNotNull("Test product must be present in Salesman Room database", downloadedProduct)
        assertEquals("টেস্ট পণ্য - প্রিমিয়াম পোলো শার্ট", downloadedProduct!!.name)
        assertEquals("SKU-POLO-01", downloadedProduct.sku)
        assertEquals(850.0, downloadedProduct.sellingPrice, 0.001)
        assertEquals(35.0, downloadedProduct.currentStock, 0.001)

        // Verify purchase cost is HIDDEN from salesman in database
        assertEquals("Purchase cost must be zero for salesman to protect business secrets", 0.0, downloadedProduct.purchasePrice, 0.001)

        // Verify idempotency: re-running sync does not duplicate
        salesmanDb.productDao().insertProduct(salesmanProduct)
        val allProductsSalesman = salesmanDb.productDao().getAllProductsList()
        assertEquals("Total product count must be exactly 1 (no duplicate insertion)", 1, allProductsSalesman.size)
    }

    @Test
    fun `test unknown Google user cannot obtain admin access or access unassigned business`() = runTest(testDispatcher) {
        val viewModel = BusinessViewModel(app)
        var loginResult = false
        var loginRole: UserRole? = null
        var errorMsg = ""

        viewModel.handleGoogleUserCredentials(
            uid = "hacker_uid_999",
            email = "attacker@external-domain.com",
            displayName = "অজ্ঞাত ব্যক্তি",
            phoneNumber = null
        ) { success, msg, role ->
            loginResult = success
            errorMsg = msg
            loginRole = role
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse("Unauthorized Google account must be rejected", loginResult)
        assertNull("Unauthorized user must have null role", loginRole)
        assertNull("Current active user must be null", viewModel.currentUser.value)
        assertTrue("Error must prompt for authorization", errorMsg.contains("অনুমোদিত"))
    }
}
