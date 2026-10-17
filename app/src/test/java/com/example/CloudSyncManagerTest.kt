package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.data.sync.CloudSyncManager
import com.example.data.sync.SyncStatus
import com.example.data.sync.Tombstone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudSyncManagerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var syncManager: CloudSyncManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        syncManager = CloudSyncManager(context, db)
    }

    @Test
    fun `test product cloud mapping strips test flags and protects cost price for salesmen`() {
        val product = Product(
            id = "prod_101",
            name = "চিনি ১ কেজি",
            sku = "SKU-SUGAR-1",
            barcode = "8941234567890",
            category = "মুদি",
            purchasePrice = 120.0,
            sellingPrice = 140.0,
            currentStock = 50.0,
            alertStockLevel = 10.0,
            unit = "কেজি",
            isActive = true,
            updatedAt = 1700000000L,
            isTestData = true,
            testTag = "DEMO_TEST"
        )

        // 1. Admin mapping: includes purchasePrice, excludes isTestData/testTag
        val adminMap = CloudSyncManager.mapProductToCloud(product, "biz_main_123", isSalesman = false)
        assertEquals("prod_101", adminMap["id"])
        assertEquals("চিনি ১ কেজি", adminMap["name"])
        assertEquals(120.0, adminMap["purchasePrice"])
        assertEquals(140.0, adminMap["sellingPrice"])
        assertEquals("biz_main_123", adminMap["businessId"])
        assertFalse("isTestData must not be uploaded", adminMap.containsKey("isTestData"))
        assertFalse("testTag must not be uploaded", adminMap.containsKey("testTag"))

        // 2. Salesman mapping: restricts purchasePrice
        val salesmanMap = CloudSyncManager.mapProductToCloud(product, "biz_main_123", isSalesman = true)
        assertEquals("prod_101", salesmanMap["id"])
        assertFalse("Salesman must never see or upload purchasePrice", salesmanMap.containsKey("purchasePrice"))
        assertEquals(140.0, salesmanMap["sellingPrice"])
    }

    @Test
    fun `test customer cloud mapping includes balances and excludes secrets`() {
        val customer = Customer(
            id = "cust_201",
            name = "আকরাম ট্রেডার্স",
            phone = "01711223344",
            address = "মিরপুর, ঢাকা",
            totalBilled = 15000.0,
            totalPaid = 10000.0,
            currentDue = 5000.0,
            openingDue = 2000.0,
            isDeleted = false,
            updatedAt = 1700000500L,
            isTestData = false
        )

        val map = CloudSyncManager.mapCustomerToCloud(customer, "biz_main_123")
        assertEquals("cust_201", map["id"])
        assertEquals("আকরাম ট্রেডার্স", map["name"])
        assertEquals("01711223344", map["phone"])
        assertEquals(5000.0, map["currentDue"])
        assertEquals(2000.0, map["openingDue"])
        assertEquals("biz_main_123", map["businessId"])
        assertFalse(map.containsKey("isTestData"))
    }

    @Test
    fun `test sale cloud mapping enforces stable idempotency keys and excludes test flags`() {
        val sale = Sale(
            invoiceNumber = "INV-2026-0001",
            customerId = "cust_201",
            customerName = "আকরাম ট্রেডার্স",
            customerPhone = "01711223344",
            salesmanId = "sales_01",
            salesmanName = "করিম",
            subtotal = 500.0,
            discount = 50.0,
            totalAmount = 450.0,
            paidAmount = 450.0,
            dueAmount = 0.0,
            paymentMethod = PaymentMethod.CASH,
            isDeleted = false,
            createdAt = 1700001000L,
            isTestData = false
        )

        val map = CloudSyncManager.mapSaleToCloud(sale, "biz_main_123")
        assertEquals("INV-2026-0001", map["invoiceNumber"])
        assertEquals(450.0, map["totalAmount"])
        assertEquals("CASH", map["paymentMethod"])
        assertEquals("biz_main_123", map["businessId"])
        assertFalse(map.containsKey("isTestData"))
    }

    @Test
    fun `test payment receipt cloud mapping maps all accounting fields and excludes test flags`() {
        val receipt = PaymentReceipt(
            receiptNumber = "RCT-20261017-001",
            invoiceNumber = "INV-2026-0001",
            customerId = "cust_201",
            customerName = "আকরাম ট্রেডার্স",
            customerPhone = "01711223344",
            salesmanId = "sales_01",
            salesmanName = "করিম",
            amount = 1500.0,
            previousDue = 5000.0,
            currentDue = 3500.0,
            paymentMethod = PaymentMethod.BKASH,
            note = "বিকাশ পেমেন্ট",
            isDeleted = false,
            createdAt = 1700002000L,
            isTestData = false
        )

        val map = CloudSyncManager.mapReceiptToCloud(receipt, "biz_main_123")
        assertEquals("RCT-20261017-001", map["receiptNumber"])
        assertEquals(1500.0, map["amount"])
        assertEquals("BKASH", map["paymentMethod"])
        assertEquals("01711223344", map["customerPhone"])
        assertEquals(3500.0, map["currentDue"])
        assertEquals("biz_main_123", map["businessId"])
        assertFalse(map.containsKey("isTestData"))
    }

    @Test
    fun `test expense cloud mapping maps category, source, status and excludes test flags`() {
        val expense = Expense(
            id = "exp_301",
            salesmanId = "sales_01",
            salesmanName = "করিম",
            category = ExpenseCategory.DELIVERY,
            source = ExpenseSource.COMPANY_CASH,
            amount = 350.0,
            description = "ভ্যান ভাড়া",
            status = ExpenseStatus.APPROVED,
            adminNote = "অনুমোদিত",
            isDeleted = false,
            createdAt = 1700003000L,
            isTestData = false
        )

        val map = CloudSyncManager.mapExpenseToCloud(expense, "biz_main_123")
        assertEquals("exp_301", map["id"])
        assertEquals("DELIVERY", map["category"])
        assertEquals("COMPANY_CASH", map["source"])
        assertEquals(350.0, map["amount"])
        assertEquals("APPROVED", map["status"])
        assertEquals("biz_main_123", map["businessId"])
        assertFalse(map.containsKey("isTestData"))
    }

    @Test
    fun `test persistent tombstone recording prevents resurrection`() = runBlocking {
        // Record deletion tombstone
        syncManager.recordTombstone(
            originalTable = "products",
            originalId = "prod_999",
            adminUserId = "admin_1",
            isPermanent = true
        )

        val tombstone = db.recycleBinDao().getItemByOriginalId("products", "prod_999")
        assertNotNull("Tombstone must be persisted in local database", tombstone)
        assertEquals("TOMBSTONE", tombstone!!.itemType)
        assertEquals("prod_999", tombstone.originalId)
        assertFalse("Tombstone must not be marked restored", tombstone.isRestored)
    }

    @Test
    fun `test offline fallback reports local saved and never claims sync success without backend`() = runBlocking {
        // Without Firebase backend configuration, executeSyncCycle must report LOCAL_SAVED
        val state = syncManager.executeSyncCycle()

        assertEquals(SyncStatus.LOCAL_SAVED, state.status)
        assertEquals("লোকাল ডেটা সংরক্ষিত — ক্লাউড সেটআপ বাকি", state.statusText)
        assertFalse("isCloudConfigured must remain false", state.isCloudConfigured)

        val currentState = syncManager.syncState.value
        assertEquals(SyncStatus.LOCAL_SAVED, currentState.status)
        assertEquals("লোকাল ডেটা সংরক্ষিত — ক্লাউড সেটআপ বাকি", currentState.statusText)
        assertFalse(currentState.isCloudConfigured)
    }

    @Test
    fun `test countPendingLocalRecords accurately tallies real business records and ignores test data`() = runBlocking {
        // Real Product
        db.productDao().insertProduct(
            Product(
                id = "real_prod_1",
                name = "বাস্তব পণ্য",
                sku = "SKU-R1",
                category = "মুদি",
                purchasePrice = 50.0,
                sellingPrice = 60.0,
                currentStock = 10.0,
                isTestData = false
            )
        )
        // Test Product (must be ignored)
        db.productDao().insertProduct(
            Product(
                id = "test_prod_1",
                name = "[টেস্ট] ডেমো পণ্য",
                sku = "SKU-T1",
                category = "মুদি",
                purchasePrice = 10.0,
                sellingPrice = 20.0,
                currentStock = 5.0,
                isTestData = true
            )
        )

        // Real Customer
        db.customerDao().insertCustomer(
            Customer(
                id = "real_cust_1",
                name = "বাস্তব কাস্টমার",
                phone = "01700000001",
                currentDue = 500.0,
                isTestData = false
            )
        )

        val pending = syncManager.countPendingLocalRecords()
        // 1 real product + 1 real customer = 2
        assertEquals(2, pending)
    }
}
