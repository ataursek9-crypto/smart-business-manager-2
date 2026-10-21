package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.data.sync.CloudBusinessAuth
import com.example.data.sync.CloudSyncManager
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
class TwoDeviceSubmissionSyncTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var app: Application
    private lateinit var adminDb: AppDatabase
    private lateinit var salesmanDb: AppDatabase
    private lateinit var adminSyncManager: CloudSyncManager
    private lateinit var salesmanSyncManager: CloudSyncManager

    private val businessId = "biz_test_sync_999"
    private val adminId = "admin_owner_1"
    private val salesmanId = "salesman_user_2"

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
        CloudBusinessAuth.clear(app)

        // Device 1: Admin phone local Room database
        adminDb = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        adminSyncManager = CloudSyncManager(app, adminDb)

        // Device 2: Salesman separate phone local Room database
        salesmanDb = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        salesmanSyncManager = CloudSyncManager(app, salesmanDb)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        adminDb.close()
        salesmanDb.close()
        CloudBusinessAuth.clear(app)
    }

    // 1. Product submission: Salesman submits on phone -> Admin receives PENDING submission via applyRemoteSubmissionMap
    @Test
    fun testSalesmanProductSubmissionUploadAndAdminReceive() = runTest(testDispatcher) {
        val submission = ProductSubmission(
            id = "sub_prod_101",
            name = "প্রিমিয়াম সুতি পাঞ্জাবি",
            productCode = "SKU-PANJABI-01",
            category = "পোশাক",
            purchasePrice = 800.0,
            sellingPrice = 1360.0,
            initialStock = 20.0,
            unit = "পিস",
            description = "উচ্চমানের কটন সুতি পাঞ্জাবি",
            salesmanId = salesmanId,
            salesmanName = "করিম আহমেদ",
            salesmanPhone = "01711000000",
            status = ProductSubmissionStatus.PENDING,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        // Salesman saves locally
        salesmanDb.productSubmissionDao().insertSubmission(submission)
        val salesmanLocal = salesmanDb.productSubmissionDao().getSubmissionById("sub_prod_101")
        assertNotNull("Salesman local database must store the submission", salesmanLocal)
        assertEquals(ProductSubmissionStatus.PENDING, salesmanLocal?.status)

        // Verify sync selection on Salesman device
        val syncList = salesmanDb.productSubmissionDao().getSyncSubmissions()
        assertTrue("getSyncSubmissions must contain the new submission", syncList.any { it.id == "sub_prod_101" })

        // Verify Cloud mapping
        val cloudMap = CloudSyncManager.mapProductSubmissionToCloud(submission, businessId)
        assertEquals("sub_prod_101", cloudMap["id"])
        assertEquals("প্রিমিয়াম সুতি পাঞ্জাবি", cloudMap["name"])
        assertEquals("PENDING", cloudMap["status"])
        assertEquals(businessId, cloudMap["businessId"])
        assertEquals(salesmanId, cloudMap["salesmanId"])

        // Admin device should initially not have this submission
        assertNull("Admin device should initially not have this submission", adminDb.productSubmissionDao().getSubmissionById("sub_prod_101"))

        // Admin device receives and applies remote submission using real CloudSyncManager logic
        adminSyncManager.applyRemoteSubmissionMap(cloudMap)

        // Verify Admin now sees the submission in PENDING status
        val adminReceived = adminDb.productSubmissionDao().getSubmissionById("sub_prod_101")
        assertNotNull("Admin database must receive the salesman product submission", adminReceived)
        assertEquals(ProductSubmissionStatus.PENDING, adminReceived?.status)
        assertEquals("প্রিমিয়াম সুতি পাঞ্জাবি", adminReceived?.name)
        assertEquals("করিম আহমেদ", adminReceived?.salesmanName)
    }

    // 2. Admin approves product submission -> Salesman receives APPROVED status AND approved Product in inventory
    @Test
    fun testAdminProductApprovalReflectsOnSalesmanDevice() = runTest(testDispatcher) {
        val submission = ProductSubmission(
            id = "sub_prod_102",
            name = "লেদার মানিব্যাগ",
            productCode = "SKU-WALLET-01",
            category = "এক্সেসরিজ",
            purchasePrice = 300.0,
            sellingPrice = 510.0,
            initialStock = 15.0,
            unit = "পিস",
            description = "জেনুইন লেদার ওয়ালেট",
            salesmanId = salesmanId,
            salesmanName = "করিম আহমেদ",
            salesmanPhone = "01711000000",
            status = ProductSubmissionStatus.PENDING
        )
        salesmanDb.productSubmissionDao().insertSubmission(submission)
        adminDb.productSubmissionDao().insertSubmission(submission)

        // Admin approves the submission
        val approvedProductId = "prod_wallet_approved_102"
        val reviewTime = System.currentTimeMillis()
        val approvedSub = submission.copy(
            status = ProductSubmissionStatus.APPROVED,
            approvedProductId = approvedProductId,
            reviewedByAdminId = adminId,
            reviewedByAdminName = "ম্যানেজার",
            reviewedAt = reviewTime,
            updatedAt = reviewTime + 500
        )
        adminDb.productSubmissionDao().updateSubmission(approvedSub)

        // Admin also adds the approved product to inventory
        val approvedProduct = Product(
            id = approvedProductId,
            name = submission.name,
            sku = submission.productCode,
            category = submission.category,
            purchasePrice = submission.purchasePrice,
            sellingPrice = submission.sellingPrice,
            currentStock = submission.initialStock,
            unit = submission.unit,
            isActive = true,
            updatedAt = reviewTime + 500
        )
        adminDb.productDao().insertProduct(approvedProduct)

        // Admin uploads approved submission and approved product to Cloud
        val cloudApprovedSubMap = CloudSyncManager.mapProductSubmissionToCloud(approvedSub, businessId)
        val cloudApprovedProdMap = CloudSyncManager.mapProductToCloud(approvedProduct, businessId, isSalesman = false)
        assertEquals("APPROVED", cloudApprovedSubMap["status"])
        assertEquals(approvedProductId, cloudApprovedSubMap["approvedProductId"])

        // Salesman device syncs remote approved submission via applyRemoteSubmissionMap
        salesmanSyncManager.applyRemoteSubmissionMap(cloudApprovedSubMap)

        // Salesman device syncs remote product via applyRemoteProductMap
        salesmanSyncManager.applyRemoteProductMap(cloudApprovedProdMap, isSalesman = true)

        // Verify Salesman submission record is now APPROVED
        val salesmanFinalSub = salesmanDb.productSubmissionDao().getSubmissionById("sub_prod_102")
        assertEquals("Salesman device must reflect APPROVED status", ProductSubmissionStatus.APPROVED, salesmanFinalSub?.status)
        assertEquals(approvedProductId, salesmanFinalSub?.approvedProductId)

        // Verify Salesman product inventory has the approved product with protected cost price
        val salesmanProduct = salesmanDb.productDao().getProductById(approvedProductId)
        assertNotNull("Salesman must have the approved product in local inventory", salesmanProduct)
        assertEquals("লেদার মানিব্যাগ", salesmanProduct?.name)
        assertEquals(510.0, salesmanProduct?.sellingPrice ?: 0.0, 0.001)
        assertEquals(15.0, salesmanProduct?.currentStock ?: 0.0, 0.001)
        assertEquals("Confidential purchase price must be 0 for salesman", 0.0, salesmanProduct?.purchasePrice ?: 0.0, 0.001)
    }

    // 3. Admin returns submission for correction -> Salesman sees NEEDS_CORRECTION and note
    @Test
    fun testAdminReturnForCorrectionWorkflow() = runTest(testDispatcher) {
        val submission = ProductSubmission(
            id = "sub_prod_103",
            name = "টি-শার্ট",
            productCode = "SKU-TSHIRT-01",
            category = "পোশাক",
            purchasePrice = 250.0,
            sellingPrice = 425.0,
            initialStock = 10.0,
            salesmanId = salesmanId,
            salesmanName = "করিম আহমেদ",
            salesmanPhone = "01711000000",
            status = ProductSubmissionStatus.PENDING
        )
        salesmanDb.productSubmissionDao().insertSubmission(submission)
        adminDb.productSubmissionDao().insertSubmission(submission)

        // Admin marks as NEEDS_CORRECTION with note
        val correctionSub = submission.copy(
            status = ProductSubmissionStatus.NEEDS_CORRECTION,
            adminCorrectionNote = "দয়া করে সঠিক সাইজ ও ফেব্রিক বিবরণ দিন",
            updatedAt = System.currentTimeMillis() + 500
        )
        adminDb.productSubmissionDao().updateSubmission(correctionSub)

        val cloudMap = CloudSyncManager.mapProductSubmissionToCloud(correctionSub, businessId)
        assertEquals("NEEDS_CORRECTION", cloudMap["status"])
        assertEquals("দয়া করে সঠিক সাইজ ও ফেব্রিক বিবরণ দিন", cloudMap["adminCorrectionNote"])

        // Salesman device updates via real CloudSyncManager
        salesmanSyncManager.applyRemoteSubmissionMap(cloudMap)

        val resultOnSalesman = salesmanDb.productSubmissionDao().getSubmissionById("sub_prod_103")
        assertEquals(ProductSubmissionStatus.NEEDS_CORRECTION, resultOnSalesman?.status)
        assertEquals("দয়া করে সঠিক সাইজ ও ফেব্রিক বিবরণ দিন", resultOnSalesman?.adminCorrectionNote)
    }

    // 4. Expense submission: Salesman submits -> Admin receives PENDING expense
    @Test
    fun testSalesmanExpenseSubmissionAndAdminReceive() = runTest(testDispatcher) {
        val expense = Expense(
            id = "exp_travel_201",
            salesmanId = salesmanId,
            salesmanName = "করিম আহমেদ",
            category = ExpenseCategory.TRAVEL,
            source = ExpenseSource.COMPANY_CASH,
            amount = 350.0,
            description = "গ্রাহক ভিজিটে সিএনজি ভাড়া",
            status = ExpenseStatus.PENDING,
            createdAt = System.currentTimeMillis()
        )

        // Salesman saves locally
        salesmanDb.expenseDao().insertExpense(expense)
        val salesmanLocalExp = salesmanDb.expenseDao().getExpenseById("exp_travel_201")
        assertNotNull(salesmanLocalExp)
        assertEquals(ExpenseStatus.PENDING, salesmanLocalExp?.status)

        // Verify sync list
        val syncExpenses = salesmanDb.expenseDao().getSyncExpenses()
        assertTrue("getSyncExpenses must contain the new expense", syncExpenses.any { it.id == "exp_travel_201" })

        // Verify Cloud mapping
        val cloudMap = CloudSyncManager.mapExpenseToCloud(expense, businessId)
        assertEquals("exp_travel_201", cloudMap["id"])
        assertEquals(350.0, cloudMap["amount"])
        assertEquals("PENDING", cloudMap["status"])
        assertEquals(businessId, cloudMap["businessId"])

        // Admin receives and applies remote expense using real CloudSyncManager
        assertNull(adminDb.expenseDao().getExpenseById("exp_travel_201"))
        adminSyncManager.applyRemoteExpenseMap(cloudMap)

        val adminReceived = adminDb.expenseDao().getExpenseById("exp_travel_201")
        assertNotNull("Admin must receive the salesman expense", adminReceived)
        assertEquals(ExpenseStatus.PENDING, adminReceived?.status)
        assertEquals(350.0, adminReceived?.amount ?: 0.0, 0.001)
        assertEquals("গ্রাহক ভিজিটে সিএনজি ভাড়া", adminReceived?.description)
    }

    // 5. Admin approves expense -> Salesman device updates from PENDING to APPROVED with adminNote & reviewedAt
    @Test
    fun testAdminExpenseApprovalReflectsOnSalesmanDevice() = runTest(testDispatcher) {
        val expense = Expense(
            id = "exp_food_202",
            salesmanId = salesmanId,
            salesmanName = "করিম আহমেদ",
            category = ExpenseCategory.FOOD,
            source = ExpenseSource.COMPANY_CASH,
            amount = 180.0,
            description = "দুপুরের খাবার",
            status = ExpenseStatus.PENDING,
            createdAt = System.currentTimeMillis()
        )
        salesmanDb.expenseDao().insertExpense(expense)
        adminDb.expenseDao().insertExpense(expense)

        // Admin approves with note
        val reviewTime = System.currentTimeMillis()
        val approvedExpense = expense.copy(
            status = ExpenseStatus.APPROVED,
            adminNote = "অনুমোদিত ও ভাউচার পাস",
            reviewedAt = reviewTime
        )
        adminDb.expenseDao().updateExpense(approvedExpense)

        // Cloud mapping from Admin
        val cloudApprovedMap = CloudSyncManager.mapExpenseToCloud(approvedExpense, businessId)
        assertEquals("APPROVED", cloudApprovedMap["status"])
        assertEquals("অনুমোদিত ও ভাউচার পাস", cloudApprovedMap["adminNote"])
        assertEquals(reviewTime, cloudApprovedMap["reviewedAt"])

        // Salesman applies remote update using real CloudSyncManager
        salesmanSyncManager.applyRemoteExpenseMap(cloudApprovedMap)

        // Verify Salesman device is now APPROVED with notes
        val salesmanFinal = salesmanDb.expenseDao().getExpenseById("exp_food_202")
        assertEquals(ExpenseStatus.APPROVED, salesmanFinal?.status)
        assertEquals("অনুমোদিত ও ভাউচার পাস", salesmanFinal?.adminNote)
        assertEquals(reviewTime, salesmanFinal?.reviewedAt)
    }

    // 6. Admin rejects expense -> Salesman device updates from PENDING to REJECTED with adminNote
    @Test
    fun testAdminExpenseRejectionReflectsOnSalesmanDevice() = runTest(testDispatcher) {
        val expense = Expense(
            id = "exp_misc_203",
            salesmanId = salesmanId,
            salesmanName = "করিম আহমেদ",
            category = ExpenseCategory.OTHER,
            source = ExpenseSource.OWN_POCKET,
            amount = 500.0,
            description = "অপ্রয়োজনীয় ব্যক্তিগত খরচ",
            status = ExpenseStatus.PENDING,
            createdAt = System.currentTimeMillis()
        )
        salesmanDb.expenseDao().insertExpense(expense)
        adminDb.expenseDao().insertExpense(expense)

        val rejectTime = System.currentTimeMillis()
        val rejectedExpense = expense.copy(
            status = ExpenseStatus.REJECTED,
            adminNote = "কোম্পানি পলিসি বহির্ভূত খরচ, অনুমোদনযোগ্য নয়",
            reviewedAt = rejectTime
        )
        adminDb.expenseDao().updateExpense(rejectedExpense)

        val cloudMap = CloudSyncManager.mapExpenseToCloud(rejectedExpense, businessId)
        assertEquals("REJECTED", cloudMap["status"])

        // Salesman device syncs remote rejection
        salesmanSyncManager.applyRemoteExpenseMap(cloudMap)

        val salesmanResult = salesmanDb.expenseDao().getExpenseById("exp_misc_203")
        assertEquals(ExpenseStatus.REJECTED, salesmanResult?.status)
        assertEquals("কোম্পানি পলিসি বহির্ভূত খরচ, অনুমোদনযোগ্য নয়", salesmanResult?.adminNote)
        assertEquals(rejectTime, salesmanResult?.reviewedAt)
    }

    // 7. Duplicate prevention: sync replay does not duplicate records
    @Test
    fun testDuplicateSubmissionAndExpensePrevention() = runTest(testDispatcher) {
        val submission = ProductSubmission(
            id = "sub_dedup_01",
            name = "ইউনিক আইটেম",
            category = "সাধারণ",
            purchasePrice = 100.0,
            sellingPrice = 170.0,
            initialStock = 5.0,
            salesmanId = salesmanId,
            salesmanName = "সেলসম্যান",
            salesmanPhone = "01700000000"
        )
        salesmanDb.productSubmissionDao().insertSubmission(submission)
        salesmanDb.productSubmissionDao().insertSubmission(submission) // re-insert

        val allSubs = salesmanDb.productSubmissionDao().getSyncSubmissions()
        assertEquals("Re-inserting same ID must not duplicate", 1, allSubs.count { it.id == "sub_dedup_01" })

        val expense = Expense(
            id = "exp_dedup_01",
            salesmanId = salesmanId,
            salesmanName = "সেলসম্যান",
            category = ExpenseCategory.OTHER,
            source = ExpenseSource.COMPANY_CASH,
            amount = 50.0,
            description = "ফটোকপি"
        )
        salesmanDb.expenseDao().insertExpense(expense)
        salesmanDb.expenseDao().insertExpense(expense) // re-insert

        val allExps = salesmanDb.expenseDao().getSyncExpenses()
        assertEquals("Re-inserting same expense ID must not duplicate", 1, allExps.count { it.id == "exp_dedup_01" })
    }
}
