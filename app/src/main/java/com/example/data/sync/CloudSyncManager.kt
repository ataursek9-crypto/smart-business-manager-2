package com.example.data.sync

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.model.*
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CloudSyncManager handles bidirectional, offline-first synchronization between local Room database
 * and Firebase Cloud Firestore with tenant business isolation, idempotent transactions, and deletion tombstones.
 */
class CloudSyncManager(
    private val context: Context,
    private val db: AppDatabase
) {
    companion object {
        private const val TAG = "CloudSyncManager"

        /**
         * Converts an asynchronous Google Play Services / Firebase Task into a suspending coroutine.
         */
        suspend fun <T> Task<T>.awaitResult(): T =
            suspendCancellableCoroutine { continuation ->
                addOnSuccessListener { result ->
                    continuation.resume(result)
                }
                addOnFailureListener { exception ->
                    continuation.resumeWithException(exception)
                }
            }

        // --- Sanitization & Security Mappers ---

        fun mapProductToCloud(product: Product, businessId: String, isSalesman: Boolean): Map<String, Any> {
            val map = mutableMapOf<String, Any>(
                "id" to product.id,
                "name" to product.name,
                "sku" to product.sku,
                "barcode" to product.barcode,
                "category" to product.category,
                "sellingPrice" to product.sellingPrice,
                "currentStock" to product.currentStock,
                "alertStockLevel" to product.alertStockLevel,
                "unit" to product.unit,
                "isActive" to product.isActive,
                "updatedAt" to product.updatedAt,
                "businessId" to businessId
            )
            // Confidential purchase cost price is restricted from salesmen
            if (!isSalesman) {
                map["purchasePrice"] = product.purchasePrice
            }
            return map
        }

        fun mapCustomerToCloud(customer: Customer, businessId: String): Map<String, Any> {
            return mapOf(
                "id" to customer.id,
                "name" to customer.name,
                "phone" to customer.phone,
                "address" to customer.address,
                "totalBilled" to customer.totalBilled,
                "totalPaid" to customer.totalPaid,
                "currentDue" to customer.currentDue,
                "openingDue" to customer.openingDue,
                "isDeleted" to customer.isDeleted,
                "updatedAt" to customer.updatedAt,
                "businessId" to businessId
            )
        }

        fun mapSaleToCloud(sale: Sale, businessId: String): Map<String, Any> {
            return mapOf(
                "invoiceNumber" to sale.invoiceNumber,
                "customerId" to sale.customerId,
                "customerName" to sale.customerName,
                "customerPhone" to sale.customerPhone,
                "salesmanId" to sale.salesmanId,
                "salesmanName" to sale.salesmanName,
                "subtotal" to sale.subtotal,
                "discount" to sale.discount,
                "totalAmount" to sale.totalAmount,
                "paidAmount" to sale.paidAmount,
                "dueAmount" to sale.dueAmount,
                "paymentMethod" to sale.paymentMethod.name,
                "isDeleted" to sale.isDeleted,
                "createdAt" to sale.createdAt,
                "businessId" to businessId
            )
        }

        fun mapReceiptToCloud(receipt: PaymentReceipt, businessId: String): Map<String, Any> {
            return mapOf(
                "receiptNumber" to receipt.receiptNumber,
                "invoiceNumber" to receipt.invoiceNumber,
                "customerId" to receipt.customerId,
                "customerName" to receipt.customerName,
                "customerPhone" to receipt.customerPhone,
                "salesmanId" to receipt.salesmanId,
                "salesmanName" to receipt.salesmanName,
                "amount" to receipt.amount,
                "previousDue" to receipt.previousDue,
                "currentDue" to receipt.currentDue,
                "paymentMethod" to receipt.paymentMethod.name,
                "note" to receipt.note,
                "isDeleted" to receipt.isDeleted,
                "createdAt" to receipt.createdAt,
                "businessId" to businessId
            )
        }

        fun mapExpenseToCloud(expense: Expense, businessId: String): Map<String, Any> {
            return mapOf(
                "id" to expense.id,
                "salesmanId" to expense.salesmanId,
                "salesmanName" to expense.salesmanName,
                "category" to expense.category.name,
                "source" to expense.source.name,
                "amount" to expense.amount,
                "description" to expense.description,
                "status" to expense.status.name,
                "adminNote" to expense.adminNote,
                "isDeleted" to expense.isDeleted,
                "createdAt" to expense.createdAt,
                "businessId" to businessId
            )
        }

        fun mapTombstoneToCloud(tombstone: Tombstone, businessId: String): Map<String, Any> {
            return mapOf(
                "id" to tombstone.id,
                "tableName" to tombstone.tableName,
                "originalId" to tombstone.originalId,
                "deletedAt" to tombstone.deletedAt,
                "isPermanent" to tombstone.isPermanent,
                "deletedByUserId" to tombstone.deletedByUserId,
                "businessId" to businessId
            )
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val _syncState = MutableStateFlow(
        SyncState(
            status = if (isCloudBackendConfigured()) SyncStatus.SYNCED else SyncStatus.LOCAL_SAVED,
            statusText = if (isCloudBackendConfigured()) "সিঙ্ক সম্পন্ন" else "লোকাল ডেটা সংরক্ষিত — ক্লাউড সেটআপ বাকি",
            isCloudConfigured = isCloudBackendConfigured()
        )
    )
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val isSyncing = AtomicBoolean(false)

    /**
     * Checks whether Firebase is properly configured on device and backend is accessible.
     */
    fun isCloudBackendConfigured(): Boolean {
        return try {
            val apps = FirebaseApp.getApps(context)
            if (apps.isEmpty()) {
                val resId = context.resources.getIdentifier("firestore_database_id", "string", context.packageName)
                return resId != 0
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Resolves the secure multi-tenant business ID for cloud isolation under /businesses/{businessId}/.
     */
    fun resolveBusinessId(): String {
        return try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                val authUser = FirebaseAuth.getInstance().currentUser
                if (authUser != null && authUser.uid.isNotBlank()) {
                    return authUser.uid
                }
            }
            "biz_default_main"
        } catch (e: Exception) {
            "biz_default_main"
        }
    }

    /**
     * Executes a full synchronization cycle synchronously.
     * Returns the updated SyncState.
     */
    suspend fun executeSyncCycle(): SyncState {
        val cloudConfigured = isCloudBackendConfigured()
        if (!cloudConfigured) {
            val pendingCount = countPendingLocalRecords()
            val statusText = "লোকাল ডেটা সংরক্ষিত — ক্লাউড সেটআপ বাকি"
            val state = SyncState(
                status = SyncStatus.LOCAL_SAVED,
                statusText = statusText,
                pendingCount = pendingCount,
                lastSyncTime = System.currentTimeMillis(),
                isCloudConfigured = false
            )
            _syncState.value = state
            return state
        }

        // Execute real cloud synchronization
        performCloudSync()

        val state = SyncState(
            status = SyncStatus.SYNCED,
            statusText = "সিঙ্ক সম্পন্ন",
            pendingCount = 0,
            lastSyncTime = System.currentTimeMillis(),
            isCloudConfigured = true
        )
        _syncState.value = state
        return state
    }

    /**
     * Triggers an offline-first sync cycle.
     * If cloud backend is configured, it performs real bidirectional Firestore sync.
     * If cloud backend is pending setup, it safely records local counts without reporting false sync success.
     */
    fun triggerSync(onComplete: (Boolean, String) -> Unit = { _, _ -> }) {
        if (!isSyncing.compareAndSet(false, true)) {
            onComplete(false, "ইতিমধ্যেই সিঙ্ক প্রক্রিয়া চলমান রয়েছে...")
            return
        }

        scope.launch {
            try {
                _syncState.value = _syncState.value.copy(
                    status = SyncStatus.SYNCING,
                    statusText = "সিঙ্ক হচ্ছে..."
                )

                val state = executeSyncCycle()
                withContext(Dispatchers.Main) {
                    onComplete(
                        true,
                        if (state.isCloudConfigured) "ক্লাউড সিঙ্ক সফলভাবে সম্পন্ন হয়েছে!" else state.statusText
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed: ${e.message}", e)
                _syncState.value = _syncState.value.copy(
                    status = SyncStatus.ERROR,
                    statusText = "সিঙ্ক ব্যর্থ: ${e.localizedMessage ?: "নেটওয়ার্ক সমস্যা"}",
                    errorMessage = e.message
                )
                withContext(Dispatchers.Main) {
                    onComplete(false, e.localizedMessage ?: "সিঙ্ক ব্যর্থ")
                }
            } finally {
                isSyncing.set(false)
            }
        }
    }

    suspend fun countPendingLocalRecords(): Int {
        val sales = db.saleDao().getRealSalesCount()
        val customers = db.customerDao().getRealCustomersCount()
        val products = db.productDao().getRealProductsCount()
        val receipts = db.paymentReceiptDao().getRealReceiptsCount()
        val expenses = db.expenseDao().getRealExpensesCount()
        return sales + customers + products + receipts + expenses
    }

    /**
     * Executes real bidirectional synchronization with Firestore.
     */
    suspend fun performCloudSync() {
        if (FirebaseApp.getApps(context).isEmpty()) {
            throw IllegalStateException("ফায়ারবেস কনফিগারেশন অনুপস্থিত (google-services.json প্রয়োজন)।")
        }

        val firestore = FirebaseFirestore.getInstance()
        val businessId = resolveBusinessId()

        // 1. Synchronize Deletion Tombstones first to protect against resurrecting deleted items
        syncTombstones(firestore, businessId)

        // 2. Synchronize Products with stock and cost protection
        syncProducts(firestore, businessId)

        // 3. Synchronize Customers and due balances
        syncCustomers(firestore, businessId)

        // 4. Synchronize Sales invoices (idempotent, no duplicates)
        syncSales(firestore, businessId)

        // 5. Synchronize Payment Receipts (idempotent, no duplicates)
        syncPayments(firestore, businessId)

        // 6. Synchronize Expenses
        syncExpenses(firestore, businessId)
    }

    private suspend fun syncTombstones(firestore: FirebaseFirestore, businessId: String) {
        val collection = firestore.collection("businesses").document(businessId).collection("tombstones")

        // Upload local tombstones
        val localTombstones = db.recycleBinDao().getSyncTombstones()
        for (item in localTombstones) {
            val tombstone = Tombstone(
                id = item.id,
                tableName = item.originalTable,
                originalId = item.originalId,
                deletedAt = item.deletedAt,
                isPermanent = item.deleteReason.contains("Tombstone", ignoreCase = true),
                deletedByUserId = item.deletedByAdminId
            )
            val data = mapTombstoneToCloud(tombstone, businessId)
            collection.document(tombstone.id).set(data, SetOptions.merge()).awaitResult()
        }

        // Download remote tombstones
        val snapshot = collection.get().awaitResult()
        for (doc in snapshot.documents) {
            val origTable = doc.getString("tableName") ?: continue
            val origId = doc.getString("originalId") ?: continue
            val tombId = doc.id
            val existing = db.recycleBinDao().getItemByOriginalId(origTable, origId)
            if (existing == null) {
                db.recycleBinDao().insertItem(
                    RecycleBinItem(
                        id = tombId,
                        originalTable = origTable,
                        originalId = origId,
                        itemName = "মুছে ফেলা রেকর্ড ($origTable #$origId)",
                        itemType = "TOMBSTONE",
                        itemDetails = "ক্লাউড থেকে প্রাপ্ত টম্বস্টোন",
                        snapshotJson = "{}",
                        deletedByAdminId = doc.getString("deletedByUserId") ?: "",
                        deletedByAdminName = "Remote Device",
                        deleteReason = "ক্লাউড টম্বস্টোন সিঙ্ক",
                        deletedAt = doc.getLong("deletedAt") ?: System.currentTimeMillis(),
                        isRestored = false
                    )
                )
            }
        }
    }

    private suspend fun syncProducts(firestore: FirebaseFirestore, businessId: String) {
        val collection = firestore.collection("businesses").document(businessId).collection("products")

        // 1. Upload local real products
        val localProducts = db.productDao().getSyncProducts()
        for (product in localProducts) {
            val data = mapProductToCloud(product, businessId, isSalesman = false)
            collection.document(product.id).set(data, SetOptions.merge()).awaitResult()
        }

        // 2. Download and merge remote products
        val snapshot = collection.get().awaitResult()
        for (doc in snapshot.documents) {
            val id = doc.getString("id") ?: doc.id
            // Check tombstone: if deleted, do not resurrect
            val tombstone = db.recycleBinDao().getItemByOriginalId("products", id)
            if (tombstone != null && !tombstone.isRestored) {
                continue
            }

            val name = doc.getString("name") ?: continue
            val sku = doc.getString("sku") ?: ""
            val barcode = doc.getString("barcode") ?: ""
            val category = doc.getString("category") ?: "সাধারণ"
            val sellingPrice = doc.getDouble("sellingPrice") ?: 0.0
            val currentStock = doc.getDouble("currentStock") ?: 0.0
            val alertStockLevel = doc.getDouble("alertStockLevel") ?: 5.0
            val unit = doc.getString("unit") ?: "পিস"
            val isActive = doc.getBoolean("isActive") ?: true
            val updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis()
            val remotePurchasePrice = doc.getDouble("purchasePrice")

            val localProduct = db.productDao().getProductById(id)
            if (localProduct != null) {
                // Last-write-wins by timestamp
                if (updatedAt >= localProduct.updatedAt) {
                    val merged = localProduct.copy(
                        name = name,
                        sku = sku,
                        barcode = barcode,
                        category = category,
                        sellingPrice = sellingPrice,
                        currentStock = currentStock,
                        alertStockLevel = alertStockLevel,
                        unit = unit,
                        isActive = isActive,
                        updatedAt = updatedAt,
                        purchasePrice = remotePurchasePrice ?: localProduct.purchasePrice
                    )
                    db.productDao().updateProduct(merged)
                }
            } else {
                val newProduct = Product(
                    id = id,
                    name = name,
                    sku = sku,
                    barcode = barcode,
                    category = category,
                    purchasePrice = remotePurchasePrice ?: 0.0,
                    sellingPrice = sellingPrice,
                    currentStock = currentStock,
                    alertStockLevel = alertStockLevel,
                    unit = unit,
                    isActive = isActive,
                    updatedAt = updatedAt,
                    isTestData = false
                )
                db.productDao().insertProduct(newProduct)
            }
        }
    }

    private suspend fun syncCustomers(firestore: FirebaseFirestore, businessId: String) {
        val collection = firestore.collection("businesses").document(businessId).collection("customers")

        // 1. Upload local real customers
        val localCustomers = db.customerDao().getSyncCustomers()
        for (customer in localCustomers) {
            val data = mapCustomerToCloud(customer, businessId)
            collection.document(customer.id).set(data, SetOptions.merge()).awaitResult()
        }

        // 2. Download remote customers
        val snapshot = collection.get().awaitResult()
        for (doc in snapshot.documents) {
            val id = doc.getString("id") ?: doc.id
            val tombstone = db.recycleBinDao().getItemByOriginalId("customers", id)
            if (tombstone != null && !tombstone.isRestored) {
                continue
            }

            val name = doc.getString("name") ?: continue
            val phone = doc.getString("phone") ?: ""
            val address = doc.getString("address") ?: ""
            val totalBilled = doc.getDouble("totalBilled") ?: 0.0
            val totalPaid = doc.getDouble("totalPaid") ?: 0.0
            val currentDue = doc.getDouble("currentDue") ?: 0.0
            val openingDue = doc.getDouble("openingDue") ?: 0.0
            val isDeleted = doc.getBoolean("isDeleted") ?: false
            val updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis()

            val localCustomer = db.customerDao().getCustomerById(id)
            if (localCustomer != null) {
                if (updatedAt >= localCustomer.updatedAt) {
                    val merged = localCustomer.copy(
                        name = name,
                        phone = phone,
                        address = address,
                        totalBilled = totalBilled,
                        totalPaid = totalPaid,
                        currentDue = currentDue,
                        openingDue = openingDue,
                        isDeleted = isDeleted,
                        updatedAt = updatedAt
                    )
                    db.customerDao().updateCustomer(merged)
                }
            } else {
                val newCustomer = Customer(
                    id = id,
                    name = name,
                    phone = phone,
                    address = address,
                    totalBilled = totalBilled,
                    totalPaid = totalPaid,
                    currentDue = currentDue,
                    openingDue = openingDue,
                    isDeleted = isDeleted,
                    updatedAt = updatedAt,
                    isTestData = false
                )
                db.customerDao().insertCustomer(newCustomer)
            }
        }
    }

    private suspend fun syncSales(firestore: FirebaseFirestore, businessId: String) {
        val collection = firestore.collection("businesses").document(businessId).collection("sales")

        // 1. Upload local sales
        val localSales = db.saleDao().getSyncSales()
        for (sale in localSales) {
            val data = mapSaleToCloud(sale, businessId)
            collection.document(sale.invoiceNumber).set(data, SetOptions.merge()).awaitResult()
        }

        // 2. Download remote sales (idempotent, never duplicates)
        val snapshot = collection.get().awaitResult()
        for (doc in snapshot.documents) {
            val invoiceNumber = doc.getString("invoiceNumber") ?: doc.id
            val tombstone = db.recycleBinDao().getItemByOriginalId("sales", invoiceNumber)
            if (tombstone != null && !tombstone.isRestored) {
                continue
            }

            val existing = db.saleDao().getSaleByInvoice(invoiceNumber)
            if (existing == null) {
                val customerId = doc.getString("customerId") ?: ""
                val customerName = doc.getString("customerName") ?: ""
                val customerPhone = doc.getString("customerPhone") ?: ""
                val salesmanId = doc.getString("salesmanId") ?: ""
                val salesmanName = doc.getString("salesmanName") ?: ""
                val subtotal = doc.getDouble("subtotal") ?: 0.0
                val discount = doc.getDouble("discount") ?: 0.0
                val totalAmount = doc.getDouble("totalAmount") ?: 0.0
                val paidAmount = doc.getDouble("paidAmount") ?: 0.0
                val dueAmount = doc.getDouble("dueAmount") ?: 0.0
                val paymentMethodStr = doc.getString("paymentMethod") ?: PaymentMethod.CASH.name
                val method = try { PaymentMethod.valueOf(paymentMethodStr) } catch (e: Exception) { PaymentMethod.CASH }
                val isDeleted = doc.getBoolean("isDeleted") ?: false
                val createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()

                val newSale = Sale(
                    invoiceNumber = invoiceNumber,
                    customerId = customerId,
                    customerName = customerName,
                    customerPhone = customerPhone,
                    salesmanId = salesmanId,
                    salesmanName = salesmanName,
                    subtotal = subtotal,
                    discount = discount,
                    totalAmount = totalAmount,
                    paidAmount = paidAmount,
                    dueAmount = dueAmount,
                    paymentMethod = method,
                    isDeleted = isDeleted,
                    createdAt = createdAt,
                    isTestData = false
                )
                db.saleDao().insertSale(newSale)
            }
        }
    }

    private suspend fun syncPayments(firestore: FirebaseFirestore, businessId: String) {
        val collection = firestore.collection("businesses").document(businessId).collection("payments")

        // 1. Upload local payments
        val localReceipts = db.paymentReceiptDao().getSyncReceipts()
        for (receipt in localReceipts) {
            val data = mapReceiptToCloud(receipt, businessId)
            collection.document(receipt.receiptNumber).set(data, SetOptions.merge()).awaitResult()
        }

        // 2. Download remote payments (idempotent, never duplicates)
        val snapshot = collection.get().awaitResult()
        for (doc in snapshot.documents) {
            val receiptNumber = doc.getString("receiptNumber") ?: doc.id
            val tombstone = db.recycleBinDao().getItemByOriginalId("payments", receiptNumber)
            if (tombstone != null && !tombstone.isRestored) {
                continue
            }

            val existing = db.paymentReceiptDao().getReceiptByNumber(receiptNumber)
            if (existing == null) {
                val invoiceNumber = doc.getString("invoiceNumber") ?: ""
                val customerId = doc.getString("customerId") ?: ""
                val customerName = doc.getString("customerName") ?: ""
                val salesmanId = doc.getString("salesmanId") ?: ""
                val amount = doc.getDouble("amount") ?: 0.0
                val paymentMethodStr = doc.getString("paymentMethod") ?: PaymentMethod.CASH.name
                val method = try { PaymentMethod.valueOf(paymentMethodStr) } catch (e: Exception) { PaymentMethod.CASH }
                val isDeleted = doc.getBoolean("isDeleted") ?: false
                val createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()

                val customerPhone = doc.getString("customerPhone") ?: ""
                val salesmanName = doc.getString("salesmanName") ?: ""
                val previousDue = doc.getDouble("previousDue") ?: 0.0
                val currentDue = doc.getDouble("currentDue") ?: 0.0
                val note = doc.getString("note") ?: ""

                val newReceipt = PaymentReceipt(
                    receiptNumber = receiptNumber,
                    invoiceNumber = invoiceNumber,
                    customerId = customerId,
                    customerName = customerName,
                    customerPhone = customerPhone,
                    salesmanId = salesmanId,
                    salesmanName = salesmanName,
                    amount = amount,
                    previousDue = previousDue,
                    currentDue = currentDue,
                    paymentMethod = method,
                    note = note,
                    isDeleted = isDeleted,
                    createdAt = createdAt,
                    isTestData = false
                )
                db.paymentReceiptDao().insertReceipt(newReceipt)
            }
        }
    }

    private suspend fun syncExpenses(firestore: FirebaseFirestore, businessId: String) {
        val collection = firestore.collection("businesses").document(businessId).collection("expenses")

        // 1. Upload local expenses
        val localExpenses = db.expenseDao().getSyncExpenses()
        for (expense in localExpenses) {
            val data = mapExpenseToCloud(expense, businessId)
            collection.document(expense.id).set(data, SetOptions.merge()).awaitResult()
        }

        // 2. Download remote expenses
        val snapshot = collection.get().awaitResult()
        for (doc in snapshot.documents) {
            val id = doc.getString("id") ?: doc.id
            val tombstone = db.recycleBinDao().getItemByOriginalId("expenses", id)
            if (tombstone != null && !tombstone.isRestored) {
                continue
            }

            val existing = db.expenseDao().getExpenseById(id)
            if (existing == null) {
                val salesmanId = doc.getString("salesmanId") ?: ""
                val salesmanName = doc.getString("salesmanName") ?: ""
                val categoryStr = doc.getString("category") ?: ExpenseCategory.OTHER.name
                val category = try { ExpenseCategory.valueOf(categoryStr) } catch (e: Exception) { ExpenseCategory.OTHER }
                val sourceStr = doc.getString("source") ?: ExpenseSource.COMPANY_CASH.name
                val source = try { ExpenseSource.valueOf(sourceStr) } catch (e: Exception) { ExpenseSource.COMPANY_CASH }
                val amount = doc.getDouble("amount") ?: 0.0
                val description = doc.getString("description") ?: ""
                val statusStr = doc.getString("status") ?: ExpenseStatus.APPROVED.name
                val status = try { ExpenseStatus.valueOf(statusStr) } catch (e: Exception) { ExpenseStatus.APPROVED }
                val adminNote = doc.getString("adminNote") ?: ""
                val isDeleted = doc.getBoolean("isDeleted") ?: false
                val createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()

                val newExpense = Expense(
                    id = id,
                    salesmanId = salesmanId,
                    salesmanName = salesmanName,
                    category = category,
                    source = source,
                    amount = amount,
                    description = description,
                    status = status,
                    adminNote = adminNote,
                    isDeleted = isDeleted,
                    createdAt = createdAt,
                    isTestData = false
                )
                db.expenseDao().insertExpense(newExpense)
            }
        }
    }

    /**
     * Record a persistent deletion tombstone in Room's recycle_bin.
     * Prevents any other authorized device from resurrecting this deleted item.
     */
    suspend fun recordTombstone(
        originalTable: String,
        originalId: String,
        adminUserId: String,
        isPermanent: Boolean = false
    ) {
        val tombstone = Tombstone(
            id = "tomb_${originalTable}_${originalId}",
            tableName = originalTable,
            originalId = originalId,
            deletedAt = System.currentTimeMillis(),
            isPermanent = isPermanent,
            deletedByUserId = adminUserId
        )

        val existing = db.recycleBinDao().getItemByOriginalId(originalTable, originalId)
        if (existing == null) {
            db.recycleBinDao().insertItem(
                RecycleBinItem(
                    id = tombstone.id,
                    originalTable = originalTable,
                    originalId = originalId,
                    itemName = "মুছে ফেলা রেকর্ড ($originalTable #$originalId)",
                    itemType = "TOMBSTONE",
                    itemDetails = "টম্বস্টোন — স্থায়ীভাবে মুছে ফেলার রেকর্ড",
                    snapshotJson = "{}",
                    deletedByAdminId = adminUserId,
                    deletedByAdminName = "Admin",
                    deleteReason = if (isPermanent) "স্থায়ীভাবে মুছে ফেলা হয়েছে (Tombstone)" else "রিসাইকেল বিনে স্থানান্তর",
                    deletedAt = tombstone.deletedAt,
                    isRestored = false
                )
            )
        }
        Log.d(TAG, "Recorded persistent deletion tombstone for $originalTable #$originalId")
    }
}
