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

    // 1. Product submission: Salesman submits on phone -> Admin receives PENDING submission
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

        // Simulate Admin device downloading from cloud and applying locally
        assertNull("Admin device should initially not have this submission", adminDb.productSubmissionDao().getSubmissionById("sub_prod_101"))

        val remoteSub = ProductSubmission(
            id = cloudMap["id"] as String,
            name = cloudMap["name"] as String,
            productCode = cloudMap["productCode"] as String,
            category = cloudMap["category"] as String,
            purchasePrice = cloudMap["purchasePrice"] as Double,
            sellingPrice = cloudMap["sellingPrice"] as Double,
            initialStock = cloudMap["initialStock"] as Double,
            unit = cloudMap["unit"] as String,
            description = cloudMap["description"] as String,
            salesmanId = cloudMap["salesmanId"] as String,
            salesmanName = cloudMap["salesmanName"] as String,
            salesmanPhone = cloudMap["salesmanPhone"] as String,
            status = ProductSubmissionStatus.valueOf(cloudMap["status"] as String),
            createdAt = cloudMap["createdAt"] as Long,
            updatedAt = cloudMap["updatedAt"] as Long
        )
        adminDb.productSubmissionDao().insertSubmission(remoteSub)

        // Verify Admin now sees the submission in PENDING status
        val adminReceived = adminDb.productSubmissionDao().getSubmissionById("sub_prod_101")
        assertNotNull("Admin database must receive the salesman product submission", adminReceived)
        assertEquals(ProductSubmissionStatus.PENDING, adminReceived?.status)
        assertEquals("প্রিমিয়াম সুতি পাঞ্জাবি", adminReceived?.name)
        assertEquals("করিম আহমেদ", adminReceived?.salesmanName)
    }

    // 2. Admin approves product submission -> Salesman receives APPROVED status
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
        val approvedSub = submission.copy(
            status = ProductSubmissionStatus.APPROVED,
            approvedProductId = "prod_wallet_approved_102",
            reviewedByAdminId = adminId,
            reviewedByAdminName = "ম্যানেজার",
            reviewedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis() + 1000
        )
        adminDb.productSubmissionDao().updateSubmission(approvedSub)

        // Admin uploads approved submission
        val cloudApprovedMap = CloudSyncManager.mapProductSubmissionToCloud(approvedSub, businessId)
        assertEquals("APPROVED", cloudApprovedMap["status"])
        assertEquals("prod_wallet_approved_102", cloudApprovedMap["approvedProductId"])

        // Salesman device syncs remote approved submission
        val salesmanExisting = salesmanDb.productSubmissionDao().getSubmissionById("sub_prod_102")
        assertNotNull(salesmanExisting)
        assertEquals(ProductSubmissionStatus.PENDING, salesmanExisting?.status)

        // Apply updated data on Salesman device (replicating CloudSyncManager logic)
        val updatedSalesmanSub = salesmanExisting!!.copy(
            status = ProductSubmissionStatus.valueOf(cloudApprovedMap["status"] as String),
            approvedProductId = cloudApprovedMap["approvedProductId"] as? String,
            reviewedByAdminId = cloudApprovedMap["reviewedByAdminId"] as? String,
            reviewedByAdminName = cloudApprovedMap["reviewedByAdminName"] as? String,
            reviewedAt = cloudApprovedMap["reviewedAt"] as? Long,
            updatedAt = cloudApprovedMap["updatedAt"] as Long
        )
        salesmanDb.productSubmissionDao().updateSubmission(updatedSalesmanSub)

        val salesmanFinal = salesmanDb.productSubmissionDao().getSubmissionById("sub_prod_102")
        assertEquals("Salesman device must reflect APPROVED status", ProductSubmissionStatus.APPROVED, salesmanFinal?.status)
        assertEquals("prod_wallet_approved_102", salesmanFinal?.approvedProductId)
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

        // Salesman device updates
        val salesmanSub = salesmanDb.productSubmissionDao().getSubmissionById("sub_prod_103")!!
        val updatedSalesmanSub = salesmanSub.copy(
            status = ProductSubmissionStatus.valueOf(cloudMap["status"] as String),
            adminCorrectionNote = cloudMap["adminCorrectionNote"] as String,
            updatedAt = cloudMap["updatedAt"] as Long
        )
        salesmanDb.productSubmissionDao().updateSubmission(updatedSalesmanSub)

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

        // Admin receives and inserts
        assertNull(adminDb.expenseDao().getExpenseById("exp_travel_201"))
        val adminExp = Expense(
            id = cloudMap["id"] as String,
            salesmanId = cloudMap["salesmanId"] as String,
            salesmanName = cloudMap["salesmanName"] as String,
            category = ExpenseCategory.valueOf(cloudMap["category"] as String),
            source = ExpenseSource.valueOf(cloudMap["source"] as String),
            amount = cloudMap["amount"] as Double,
            description = cloudMap["description"] as String,
            status = ExpenseStatus.valueOf(cloudMap["status"] as String),
            adminNote = cloudMap["adminNote"] as String,
            isDeleted = cloudMap["isDeleted"] as Boolean,
            createdAt = cloudMap["createdAt"] as Long
        )
        adminDb.expenseDao().insertExpense(adminExp)

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

        // Salesman device has existing expense with PENDING
        val salesmanExisting = salesmanDb.expenseDao().getExpenseById("exp_food_202")
        assertEquals(ExpenseStatus.PENDING, salesmanExisting?.status)

        // Salesman applies remote update (matching updated CloudSyncManager syncExpenses logic)
        val newStatus = ExpenseStatus.valueOf(cloudApprovedMap["status"] as String)
        val newAdminNote = cloudApprovedMap["adminNote"] as String
        val newReviewedAt = cloudApprovedMap["reviewedAt"] as? Long

        if (salesmanExisting!!.status != newStatus || salesmanExisting.adminNote != newAdminNote || salesmanExisting.reviewedAt != newReviewedAt) {
            val updatedSalesmanExpense = salesmanExisting.copy(
                status = newStatus,
                adminNote = newAdminNote,
                reviewedAt = newReviewedAt ?: salesmanExisting.reviewedAt
            )
            salesmanDb.expenseDao().updateExpense(updatedSalesmanExpense)
        }

        // Verify Salesman device is now APPROVED with notes
        val salesmanFinal = salesmanDb.expenseDao().getExpenseById("exp_food_202")
        assertEquals(ExpenseStatus.APPROVED, salesmanFinal?.status)
        assertEquals("অনুমোদিত ও ভাউচার পাস", salesmanFinal?.adminNote)
        assertEquals(reviewTime, salesmanFinal?.reviewedAt)
    }

    // 6. Duplicate prevention: sync replay does not duplicate records
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
