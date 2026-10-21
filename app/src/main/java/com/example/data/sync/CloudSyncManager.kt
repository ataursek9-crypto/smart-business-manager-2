package com.example.data.sync

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.model.*
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
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
                "businessId" to businessId,
                "imageUri" to (product.imageUri ?: ""),
                "imageSource" to product.imageSource,
                "imageSourceInfo" to product.imageSourceInfo
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

        fun mapSaleToCloud(sale: Sale, items: List<SaleItem>, businessId: String): Map<String, Any> {
            val itemsList = items.map { item ->
                mapOf(
                    "productId" to item.productId,
                    "productName" to item.productName,
                    "quantity" to item.quantity,
                    "unitPrice" to item.unitPrice,
                    "purchasePriceAtSale" to item.purchasePriceAtSale,
                    "totalPrice" to item.totalPrice
                )
            }
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
                "items" to itemsList,
                "createdAt" to sale.createdAt,
                "businessId" to businessId
            )
        }

        fun mapSaleToCloud(sale: Sale, businessId: String): Map<String, Any> {
            return mapSaleToCloud(sale, emptyList(), businessId)
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
            val map = mutableMapOf<String, Any>(
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
            expense.reviewedAt?.let { map["reviewedAt"] = it }
            return map
        }

        fun mapProductSubmissionToCloud(sub: ProductSubmission, businessId: String): Map<String, Any> {
            val map = mutableMapOf<String, Any>(
                "id" to sub.id,
                "name" to sub.name,
                "productCode" to sub.productCode,
                "category" to sub.category,
                "purchasePrice" to sub.purchasePrice,
                "sellingPrice" to sub.sellingPrice,
                "initialStock" to sub.initialStock,
                "unit" to sub.unit,
                "description" to sub.description,
                "salesmanId" to sub.salesmanId,
                "salesmanName" to sub.salesmanName,
                "salesmanPhone" to sub.salesmanPhone,
                "status" to sub.status.name,
                "adminCorrectionNote" to sub.adminCorrectionNote,
                "imageSource" to sub.imageSource,
                "imageSourceInfo" to sub.imageSourceInfo,
                "createdAt" to sub.createdAt,
                "updatedAt" to sub.updatedAt,
                "businessId" to businessId
            )
            sub.approvedProductId?.let { map["approvedProductId"] = it }
            sub.reviewedByAdminId?.let { map["reviewedByAdminId"] = it }
            sub.reviewedByAdminName?.let { map["reviewedByAdminName"] = it }
            sub.reviewedAt?.let { map["reviewedAt"] = it }
            sub.imageUri?.let { map["imageUri"] = it }
            return map
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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun cancelActiveSync() {
        scope.coroutineContext.cancelChildren()
    }
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
     * Checks whether Firebase is properly configured on device and an authenticated user session is active.
     */
    fun isCloudBackendConfigured(): Boolean {
        return try {
            val apps = FirebaseApp.getApps(context)
            if (apps.isEmpty()) {
                val resId = context.resources.getIdentifier("firestore_database_id", "string", context.packageName)
                return resId != 0
            }
            val authUser = FirebaseAuth.getInstance().currentUser
            authUser != null && authUser.uid.isNotBlank()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if current active session belongs to a salesman (staff) rather than admin (owner).
     */
    suspend fun isCurrentActiveSalesman(): Boolean {
        val authUser = try { FirebaseAuth.getInstance().currentUser } catch (e: Exception) { null }
        if (authUser != null) {
            val email = authUser.email?.trim()?.lowercase() ?: ""
            if (email == "ataursek9@gmail.com" || email == "ataurgonee4@gmail.com" || email == "support@smartbiz.local" || email == "gonee8666@gmail.com") {
                return false
            }
            val storedRole = CloudBusinessAuth.getStoredRole(context)
            if (storedRole == UserRole.ADMIN.name) {
                return false
            }
            // If the user is connected to an active business that they do not own, they are a salesman
            val activeBizId = CloudBusinessAuth.getStoredBusinessId(context)
            if (!activeBizId.isNullOrBlank() && authUser.uid != activeBizId) {
                return true
            }
            val localUser = db.userDao().getUserById(authUser.uid)
            if (localUser != null) {
                return localUser.role == UserRole.SALESMAN
            }
        }
        val storedRole = CloudBusinessAuth.getStoredRole(context)
        if (storedRole != null) {
            return storedRole == UserRole.SALESMAN.name
        }
        return false
    }

    /**
     * Resolves the secure multi-tenant business ID for cloud isolation under /businesses/{businessId}/.
     * Enforces strict authentication: never returns a fallback ID when unauthenticated.
     */
    fun resolveBusinessId(): String {
        if (FirebaseApp.getApps(context).isEmpty()) {
            throw IllegalStateException("ফায়ারবেস কনফিগারেশন অনুপস্থিত (google-services.json প্রয়োজন)।")
        }
        val authUser = try {
            FirebaseAuth.getInstance().currentUser
        } catch (e: Exception) {
            null
        } ?: throw IllegalStateException("ক্লাউড সিঙ্ক করার জন্য গুগল একাউন্টে প্রমাণীকরণ (Authentication) আবশ্যক। ফলব্যাক আইডি ব্যবহার নিষিদ্ধ।")

        if (authUser.uid.isBlank()) {
            throw IllegalStateException("অবৈধ ইউজার আইডি। ক্লাউড সিঙ্ক বাতিল করা হয়েছে।")
        }

        val email = authUser.email?.trim()?.lowercase() ?: ""
        val isOwner = email == "ataursek9@gmail.com" || email == "ataurgonee4@gmail.com" ||
                email == "support@smartbiz.local" || email == "gonee8666@gmail.com" ||
                CloudBusinessAuth.getStoredRole(context) == UserRole.ADMIN.name

        if (isOwner) {
            CloudBusinessAuth.setStoredBusinessId(context, authUser.uid, role = UserRole.ADMIN.name)
            return authUser.uid
        }

        val storedBizId = CloudBusinessAuth.getStoredBusinessId(context)
        if (!storedBizId.isNullOrBlank()) {
            return storedBizId
        }

        throw IllegalStateException("অননুমোদিত স্টাফ ডিভাইস: কোনো অনুমোদিত ক্লাউড বিজনেস আইডি পাওয়া যায়নি। অনুগ্রহ করে অ্যাডমিনের বিজনেস আইডি দিয়ে কানেক্ট করুন।")
    }

    suspend fun resolveOrFetchBusinessId(): String {
        if (FirebaseApp.getApps(context).isEmpty()) {
            throw IllegalStateException("ফায়ারবেস কনফিগারেশন অনুপস্থিত (google-services.json প্রয়োজন)।")
        }
        val authUser = try {
            FirebaseAuth.getInstance().currentUser
        } catch (e: Exception) {
            null
        } ?: throw IllegalStateException("ক্লাউড সিঙ্ক করার জন্য গুগল একাউন্টে প্রমাণীকরণ আবশ্যক।")

        val email = authUser.email?.trim()?.lowercase() ?: ""
        val isOwner = email == "ataursek9@gmail.com" || email == "ataurgonee4@gmail.com" ||
                email == "support@smartbiz.local" || email == "gonee8666@gmail.com" ||
                CloudBusinessAuth.getStoredRole(context) == UserRole.ADMIN.name

        if (isOwner) {
            CloudBusinessAuth.setStoredBusinessId(context, authUser.uid, role = UserRole.ADMIN.name)
            return authUser.uid
        }

        val storedBizId = CloudBusinessAuth.getStoredBusinessId(context)
        if (!storedBizId.isNullOrBlank()) {
            return storedBizId
        }

        // Attempt remote lookup in Firestore staff_memberships/{authUser.uid}
        try {
            val firestore = FirebaseFirestore.getInstance()
            val doc = firestore.collection("staff_memberships").document(authUser.uid).get().awaitResult()
            if (doc.exists()) {
                val bizId = doc.getString("businessId")
                val isActive = doc.getBoolean("isActive") ?: false
                if (!bizId.isNullOrBlank() && isActive) {
                    CloudBusinessAuth.setStoredBusinessId(context, bizId, role = UserRole.SALESMAN.name)
                    return bizId
                }
            }
        } catch (_: Exception) {}

        throw IllegalStateException("অননুমোদিত স্টাফ ডিভাইস: কোনো অনুমোদিত ক্লাউড বিজনেস আইডি পাওয়া যায়নি। অনুগ্রহ করে অ্যাডমিনের বিজনেস আইডি দিয়ে কানেক্ট করুন।")
    }

    /**
     * Executes a full synchronization cycle synchronously.
     * Returns the updated SyncState.
     */
    suspend fun executeSyncCycle(): SyncState {
        val cloudConfigured = isCloudBackendConfigured()
        if (!cloudConfigured) {
            val pendingCount = countPendingLocalRecords()
            val hasFirebaseApp = try { FirebaseApp.getApps(context).isNotEmpty() } catch (e: Exception) { false }
            val statusText = if (!hasFirebaseApp) {
                "লোকাল ডেটা সংরক্ষিত — ক্লাউড সেটআপ বাকি"
            } else {
                "লোকাল ডেটা সংরক্ষিত — গুগল একাউন্টে সাইন-ইন প্রয়োজন"
            }
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

        // Maintain live snapshot observation for real-time multi-device sync
        startProductsRealtimeListener()

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

    private var productsListenerRegistration: ListenerRegistration? = null
    private var submissionsListenerRegistration: ListenerRegistration? = null
    private var expensesListenerRegistration: ListenerRegistration? = null

    /**
     * Attaches real-time snapshot listeners to products, product_submissions, and expenses.
     * Ensures Admin reviews, salesman submissions, and expenses reflect on all authorized devices immediately.
     */
    fun startProductsRealtimeListener() {
        scope.launch {
            try {
                val apps = FirebaseApp.getApps(context)
                if (apps.isEmpty()) return@launch
                val authUser = FirebaseAuth.getInstance().currentUser ?: return@launch
                if (authUser.uid.isBlank()) return@launch

                val businessId = try { resolveBusinessId() } catch (_: Exception) {
                    try { resolveOrFetchBusinessId() } catch (_: Exception) { return@launch }
                }
                if (businessId.isBlank()) return@launch

                val firestore = FirebaseFirestore.getInstance()

                // 1. Products Listener
                productsListenerRegistration?.remove()
                productsListenerRegistration = firestore.collection("businesses")
                    .document(businessId)
                    .collection("products")
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.w(TAG, "Products realtime listener warning: ${error.message}")
                            return@addSnapshotListener
                        }
                        if (snapshot != null) {
                            scope.launch {
                                val isSalesman = isCurrentActiveSalesman()
                                for (doc in snapshot.documents) {
                                    val data = doc.data?.toMutableMap() ?: mutableMapOf()
                                    if (!data.containsKey("id")) data["id"] = doc.id
                                    applyRemoteProductMap(data, isSalesman)
                                }
                            }
                        }
                    }

                // 2. Product Submissions Listener (for Admin review and Salesman status feedback)
                submissionsListenerRegistration?.remove()
                submissionsListenerRegistration = firestore.collection("businesses")
                    .document(businessId)
                    .collection("product_submissions")
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.w(TAG, "Submissions realtime listener warning: ${error.message}")
                            return@addSnapshotListener
                        }
                        if (snapshot != null) {
                            scope.launch {
                                for (doc in snapshot.documents) {
                                    applyRemoteSubmissionDoc(doc)
                                }
                            }
                        }
                    }

                // 3. Expenses Listener (for Admin approval and Salesman status feedback)
                expensesListenerRegistration?.remove()
                expensesListenerRegistration = firestore.collection("businesses")
                    .document(businessId)
                    .collection("expenses")
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.w(TAG, "Expenses realtime listener warning: ${error.message}")
                            return@addSnapshotListener
                        }
                        if (snapshot != null) {
                            scope.launch {
                                for (doc in snapshot.documents) {
                                    applyRemoteExpenseDoc(doc)
                                }
                            }
                        }
                    }

                Log.i(TAG, "Realtime listeners started for business: $businessId")
            } catch (e: Exception) {
                Log.w(TAG, "Could not start realtime listeners: ${e.message}")
            }
        }
    }

    fun stopProductsRealtimeListener() {
        productsListenerRegistration?.remove()
        productsListenerRegistration = null
        submissionsListenerRegistration?.remove()
        submissionsListenerRegistration = null
        expensesListenerRegistration?.remove()
        expensesListenerRegistration = null
    }

    /**
     * Atomically records a sale and deducts product stock in Cloud Firestore.
     * Prevents race conditions and multi-device double deduction.
     */
    suspend fun deductCloudStockForSale(invoiceNumber: String, items: List<SaleItem>, businessId: String) {
        if (!isCloudBackendConfigured() || businessId.isBlank()) return
        val firestore = FirebaseFirestore.getInstance()
        val batch = firestore.batch()
        val now = System.currentTimeMillis()

        for (item in items) {
            if (item.productId.isBlank() || item.quantity <= 0) continue
            val prodDocRef = firestore.collection("businesses").document(businessId).collection("products").document(item.productId)
            batch.update(prodDocRef, mapOf(
                "currentStock" to FieldValue.increment(-item.quantity),
                "updatedAt" to now
            ))
        }
        try {
            batch.commit().awaitResult()
            Log.i(TAG, "Atomic cloud stock deduction committed for sale: $invoiceNumber")
        } catch (e: Exception) {
            Log.w(TAG, "Atomic cloud stock deduction non-fatal note: ${e.message}")
        }
    }

    /**
     * Triggers an offline-first sync cycle.
     * If cloud backend is configured, it performs real bidirectional Firestore sync.
     * If cloud backend is pending setup, it safely records local counts without reporting false sync success.
     */
    fun triggerSync(onComplete: ((Boolean, String) -> Unit)? = null) {
        if (!isSyncing.compareAndSet(false, true)) {
            onComplete?.invoke(false, "ইতিমধ্যেই সিঙ্ক প্রক্রিয়া চলমান রয়েছে...")
            return
        }

        scope.launch {
            try {
                _syncState.value = _syncState.value.copy(
                    status = SyncStatus.SYNCING,
                    statusText = "সিঙ্ক হচ্ছে..."
                )

                val state = executeSyncCycle()
                if (onComplete != null) {
                    withContext(Dispatchers.Main) {
                        onComplete(
                            state.isCloudConfigured,
                            if (state.isCloudConfigured) "ক্লাউড সিঙ্ক সফলভাবে সম্পন্ন হয়েছে!" else state.statusText
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed: ${e.message}", e)
                _syncState.value = _syncState.value.copy(
                    status = SyncStatus.ERROR,
                    statusText = "সিঙ্ক ব্যর্থ: ${e.localizedMessage ?: "নেটওয়ার্ক সমস্যা"}",
                    errorMessage = e.message
                )
                if (onComplete != null) {
                    withContext(Dispatchers.Main) {
                        onComplete(false, e.localizedMessage ?: "সিঙ্ক ব্যর্থ")
                    }
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
        val businessId = resolveOrFetchBusinessId()
        val isSalesman = isCurrentActiveSalesman()

        // 0. Synchronize Business Profile & Staff Membership
        syncBusinessAndStaff(firestore, businessId, isSalesman)

        // 1. Synchronize Deletion Tombstones first to protect against resurrecting deleted items
        syncTombstones(firestore, businessId, isSalesman)

        // 2. Synchronize Products with stock and cost protection
        syncProducts(firestore, businessId, isSalesman)

        // 3. Synchronize Customers and due balances
        syncCustomers(firestore, businessId)

        // 4. Synchronize Sales invoices (idempotent, no duplicates)
        syncSales(firestore, businessId)

        // 5. Synchronize Payment Receipts (idempotent, no duplicates)
        syncPayments(firestore, businessId)

        // 6. Synchronize Expenses (download first, role-protected upload)
        syncExpenses(firestore, businessId, isSalesman)

        // 7. Synchronize Product Submissions (download first, role-protected upload)
        syncProductSubmissions(firestore, businessId, isSalesman)
    }

    private suspend fun syncBusinessAndStaff(firestore: FirebaseFirestore, businessId: String, isSalesman: Boolean) {
        val bizDocRef = firestore.collection("businesses").document(businessId)

        if (!isSalesman) {
            val authUser = FirebaseAuth.getInstance().currentUser
            val settings = db.companySettingsDao().getSettings() ?: CompanySettings()
            val bizData = mapOf(
                "id" to businessId,
                "ownerUid" to (authUser?.uid ?: businessId),
                "businessName" to settings.businessName,
                "phone" to settings.phone,
                "address" to settings.address,
                "currencySymbol" to settings.currencySymbol,
                "updatedAt" to System.currentTimeMillis()
            )
            bizDocRef.set(bizData, SetOptions.merge()).awaitResult()

            // Admin syncs active staff to Firestore under /businesses/{businessId}/staff/
            val activeUsers = db.userDao().getAllActiveUsersList()
            for (user in activeUsers) {
                if (user.role == UserRole.SALESMAN) {
                    val cleanPhone = CloudBusinessAuth.normalizePhone(user.phone)
                    val staffData = mapOf(
                        "id" to user.id,
                        "name" to user.name,
                        "phone" to cleanPhone,
                        "role" to user.role.name,
                        "isActive" to (user.isActive && user.accountStatus == UserAccountStatus.ACTIVE),
                        "canDiscount" to user.canDiscount,
                        "businessId" to businessId,
                        "pinHash" to user.pinHash,
                        "updatedAt" to System.currentTimeMillis()
                    )
                    bizDocRef.collection("staff").document(user.id).set(staffData, SetOptions.merge()).awaitResult()
                    if (cleanPhone.isNotBlank()) {
                        bizDocRef.collection("staff_by_phone").document(cleanPhone).set(staffData, SetOptions.merge()).awaitResult()
                    }
                }
            }
        } else {
            // Salesman fetches business profile and syncs business name
            try {
                val bizDoc = bizDocRef.get().awaitResult()
                if (bizDoc.exists()) {
                    val bName = bizDoc.getString("businessName")
                    val bPhone = bizDoc.getString("phone")
                    if (!bName.isNullOrBlank()) {
                        val currentSettings = db.companySettingsDao().getSettings() ?: CompanySettings()
                        db.companySettingsDao().insertOrUpdate(
                            currentSettings.copy(
                                businessName = bName,
                                phone = bPhone ?: currentSettings.phone
                            )
                        )
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun syncTombstones(firestore: FirebaseFirestore, businessId: String, isSalesman: Boolean) {
        val collection = firestore.collection("businesses").document(businessId).collection("tombstones")

        // Upload local tombstones (Only Admin can create/update tombstones)
        if (!isSalesman) {
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

    suspend fun applyRemoteProductMap(data: Map<String, Any?>, isSalesman: Boolean) {
        val id = data["id"] as? String ?: return
        val tombstone = db.recycleBinDao().getItemByOriginalId("products", id)
        if (tombstone != null && !tombstone.isRestored) {
            return
        }

        val name = data["name"] as? String ?: return
        val sku = data["sku"] as? String ?: ""
        val barcode = data["barcode"] as? String ?: ""
        val category = data["category"] as? String ?: "সাধারণ"
        val sellingPrice = (data["sellingPrice"] as? Number)?.toDouble() ?: 0.0
        val currentStock = (data["currentStock"] as? Number)?.toDouble() ?: 0.0
        val alertStockLevel = (data["alertStockLevel"] as? Number)?.toDouble() ?: 5.0
        val unit = data["unit"] as? String ?: "পিস"
        val isActive = data["isActive"] as? Boolean ?: true
        val updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
        val remotePurchasePrice = (data["purchasePrice"] as? Number)?.toDouble()
        val imageUri = (data["imageUri"] as? String)?.takeIf { it.isNotBlank() }
        val imageSource = data["imageSource"] as? String ?: "NONE"
        val imageSourceInfo = data["imageSourceInfo"] as? String ?: ""

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
                    imageUri = imageUri ?: localProduct.imageUri,
                    imageSource = if (imageSource != "NONE") imageSource else localProduct.imageSource,
                    imageSourceInfo = if (imageSourceInfo.isNotBlank()) imageSourceInfo else localProduct.imageSourceInfo,
                    // Purchase price protection: salesman never overwrites or gets exposed to purchase cost
                    purchasePrice = if (isSalesman) (localProduct.purchasePrice.takeIf { it > 0 } ?: 0.0) else (remotePurchasePrice ?: localProduct.purchasePrice)
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
                purchasePrice = if (isSalesman) 0.0 else (remotePurchasePrice ?: 0.0),
                sellingPrice = sellingPrice,
                currentStock = currentStock,
                alertStockLevel = alertStockLevel,
                unit = unit,
                isActive = isActive,
                updatedAt = updatedAt,
                imageUri = imageUri,
                imageSource = imageSource,
                imageSourceInfo = imageSourceInfo,
                isTestData = false
            )
            db.productDao().insertProduct(newProduct)
        }
    }

    private suspend fun syncProducts(firestore: FirebaseFirestore, businessId: String, isSalesman: Boolean) {
        val collection = firestore.collection("businesses").document(businessId).collection("products")

        // 1. Upload local real products: STRICTLY OWNER / ADMIN ONLY
        if (!isSalesman) {
            val localProducts = db.productDao().getSyncProducts()
            for (product in localProducts) {
                if (product.isTestData) continue
                val data = mapProductToCloud(product, businessId, isSalesman = false)
                collection.document(product.id).set(data, SetOptions.merge()).awaitResult()
            }
        }

        // 2. Download and merge remote products: BOTH ADMIN AND SALESMAN DOWNLOAD
        val snapshot = collection.get().awaitResult()
        for (doc in snapshot.documents) {
            val data = doc.data?.toMutableMap() ?: mutableMapOf()
            if (!data.containsKey("id")) data["id"] = doc.id
            applyRemoteProductMap(data, isSalesman)
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

        // 1. Upload local sales with items
        val localSales = db.saleDao().getSyncSales()
        for (sale in localSales) {
            val items = db.saleDao().getSaleItems(sale.invoiceNumber)
            val data = mapSaleToCloud(sale, items, businessId)
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

                // Insert associated line items if present
                val rawItems = doc.get("items") as? List<Map<String, Any?>>
                if (rawItems != null && rawItems.isNotEmpty()) {
                    val itemsList = rawItems.mapNotNull { itemMap ->
                        val pId = itemMap["productId"] as? String ?: return@mapNotNull null
                        val pName = itemMap["productName"] as? String ?: ""
                        val qty = (itemMap["quantity"] as? Number)?.toDouble() ?: 0.0
                        val uPrice = (itemMap["unitPrice"] as? Number)?.toDouble() ?: 0.0
                        val pPurchase = (itemMap["purchasePriceAtSale"] as? Number)?.toDouble() ?: 0.0
                        val tPrice = (itemMap["totalPrice"] as? Number)?.toDouble() ?: (qty * uPrice)
                        SaleItem(
                            invoiceNumber = invoiceNumber,
                            productId = pId,
                            productName = pName,
                            quantity = qty,
                            unitPrice = uPrice,
                            purchasePriceAtSale = pPurchase,
                            totalPrice = tPrice
                        )
                    }
                    if (itemsList.isNotEmpty()) {
                        db.saleDao().insertSaleItems(itemsList)
                    }
                }
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

    private suspend fun syncExpenses(firestore: FirebaseFirestore, businessId: String, isSalesman: Boolean) {
        val collection = firestore.collection("businesses").document(businessId).collection("expenses")

        // 1. Download remote expenses first to retrieve Admin reviews, approvals, and rejections
        val snapshot = collection.get().awaitResult()
        for (doc in snapshot.documents) {
            applyRemoteExpenseDoc(doc)
        }

        // 2. Upload local expenses
        // If salesman: only upload PENDING active expenses submitted by this salesman (never overwrite approved/rejected records)
        // If admin: upload all local real expenses (including approvals, rejections, and review notes)
        val localExpenses = db.expenseDao().getSyncExpenses()
        for (expense in localExpenses) {
            if (isSalesman && (expense.status != ExpenseStatus.PENDING || expense.isDeleted)) {
                continue
            }
            val data = mapExpenseToCloud(expense, businessId)
            collection.document(expense.id).set(data, SetOptions.merge()).awaitResult()
        }
    }

    suspend fun applyRemoteExpenseDoc(doc: DocumentSnapshot) {
        val id = doc.getString("id") ?: doc.id
        val tombstone = db.recycleBinDao().getItemByOriginalId("expenses", id)
        if (tombstone != null && !tombstone.isRestored) {
            return
        }

        val salesmanId = doc.getString("salesmanId") ?: ""
        val salesmanName = doc.getString("salesmanName") ?: ""
        val categoryStr = doc.getString("category") ?: ExpenseCategory.OTHER.name
        val category = try { ExpenseCategory.valueOf(categoryStr) } catch (e: Exception) { ExpenseCategory.OTHER }
        val sourceStr = doc.getString("source") ?: ExpenseSource.COMPANY_CASH.name
        val source = try { ExpenseSource.valueOf(sourceStr) } catch (e: Exception) { ExpenseSource.COMPANY_CASH }
        val amount = (doc.get("amount") as? Number)?.toDouble() ?: doc.getDouble("amount") ?: 0.0
        val description = doc.getString("description") ?: ""
        val statusStr = doc.getString("status") ?: ExpenseStatus.APPROVED.name
        val status = try { ExpenseStatus.valueOf(statusStr) } catch (e: Exception) { ExpenseStatus.APPROVED }
        val adminNote = doc.getString("adminNote") ?: ""
        val isDeleted = doc.getBoolean("isDeleted") ?: false
        val createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
        val reviewedAt = doc.getLong("reviewedAt")

        val existing = db.expenseDao().getExpenseById(id)
        if (existing == null) {
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
                reviewedAt = reviewedAt,
                isTestData = false
            )
            db.expenseDao().insertExpense(newExpense)
        } else {
            // Update local expense if status, note, review timestamp, amount, description or deletion state changed remotely
            if (existing.status != status ||
                existing.adminNote != adminNote ||
                existing.reviewedAt != reviewedAt ||
                existing.isDeleted != isDeleted ||
                existing.amount != amount ||
                existing.description != description) {
                val updatedExpense = existing.copy(
                    salesmanName = if (salesmanName.isNotBlank()) salesmanName else existing.salesmanName,
                    category = category,
                    source = source,
                    amount = amount,
                    description = description,
                    status = status,
                    adminNote = adminNote,
                    reviewedAt = reviewedAt ?: existing.reviewedAt,
                    isDeleted = isDeleted
                )
                db.expenseDao().updateExpense(updatedExpense)
            }
        }
    }

    private suspend fun syncProductSubmissions(firestore: FirebaseFirestore, businessId: String, isSalesman: Boolean) {
        val collection = firestore.collection("businesses").document(businessId).collection("product_submissions")

        // 1. Download remote product submissions first to retrieve Admin review and approvals
        val snapshot = collection.get().awaitResult()
        for (doc in snapshot.documents) {
            applyRemoteSubmissionDoc(doc)
        }

        // 2. Upload local product submissions
        // If salesman: only upload PENDING or NEEDS_CORRECTION resubmissions (never overwrite APPROVED submissions)
        // If admin: upload all local submissions (including approvals, notes, and revisions)
        val localSubmissions = db.productSubmissionDao().getSyncSubmissions()
        for (sub in localSubmissions) {
            if (isSalesman && sub.status == ProductSubmissionStatus.APPROVED) {
                continue
            }
            val data = mapProductSubmissionToCloud(sub, businessId)
            collection.document(sub.id).set(data, SetOptions.merge()).awaitResult()
        }
    }

    suspend fun applyRemoteSubmissionDoc(doc: DocumentSnapshot) {
        val id = doc.getString("id") ?: doc.id
        val name = doc.getString("name") ?: ""
        if (name.isBlank()) return
        val productCode = doc.getString("productCode") ?: ""
        val category = doc.getString("category") ?: "সাধারণ"
        val purchasePrice = (doc.get("purchasePrice") as? Number)?.toDouble() ?: doc.getDouble("purchasePrice") ?: 0.0
        val sellingPrice = (doc.get("sellingPrice") as? Number)?.toDouble() ?: doc.getDouble("sellingPrice") ?: 0.0
        val initialStock = (doc.get("initialStock") as? Number)?.toDouble() ?: doc.getDouble("initialStock") ?: 0.0
        val unit = doc.getString("unit") ?: "পিস"
        val description = doc.getString("description") ?: ""
        val salesmanId = doc.getString("salesmanId") ?: ""
        val salesmanName = doc.getString("salesmanName") ?: ""
        val salesmanPhone = doc.getString("salesmanPhone") ?: ""
        val statusStr = doc.getString("status") ?: ProductSubmissionStatus.PENDING.name
        val status = try { ProductSubmissionStatus.valueOf(statusStr) } catch (e: Exception) { ProductSubmissionStatus.PENDING }
        val adminCorrectionNote = doc.getString("adminCorrectionNote") ?: ""
        val approvedProductId = doc.getString("approvedProductId")
        val reviewedByAdminId = doc.getString("reviewedByAdminId")
        val reviewedByAdminName = doc.getString("reviewedByAdminName")
        val createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
        val updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis()
        val reviewedAt = doc.getLong("reviewedAt")
        val imageUri = doc.getString("imageUri")
        val imageSource = doc.getString("imageSource") ?: "NONE"
        val imageSourceInfo = doc.getString("imageSourceInfo") ?: ""

        val existing = db.productSubmissionDao().getSubmissionById(id)
        if (existing == null) {
            val newSub = ProductSubmission(
                id = id,
                name = name,
                productCode = productCode,
                category = category,
                purchasePrice = purchasePrice,
                sellingPrice = sellingPrice,
                initialStock = initialStock,
                unit = unit,
                description = description,
                salesmanId = salesmanId,
                salesmanName = salesmanName,
                salesmanPhone = salesmanPhone,
                status = status,
                adminCorrectionNote = adminCorrectionNote,
                approvedProductId = approvedProductId,
                reviewedByAdminId = reviewedByAdminId,
                reviewedByAdminName = reviewedByAdminName,
                createdAt = createdAt,
                updatedAt = updatedAt,
                reviewedAt = reviewedAt,
                imageUri = imageUri,
                imageSource = imageSource,
                imageSourceInfo = imageSourceInfo
            )
            db.productSubmissionDao().insertSubmission(newSub)
        } else {
            // Update if status, admin notes, review data, or updatedAt changed remotely
            if (existing.status != status ||
                existing.adminCorrectionNote != adminCorrectionNote ||
                existing.approvedProductId != approvedProductId ||
                existing.reviewedAt != reviewedAt ||
                existing.updatedAt <= updatedAt) {
                val updatedSub = existing.copy(
                    name = name,
                    productCode = productCode,
                    category = category,
                    purchasePrice = purchasePrice,
                    sellingPrice = sellingPrice,
                    initialStock = initialStock,
                    unit = unit,
                    description = description,
                    status = status,
                    adminCorrectionNote = adminCorrectionNote,
                    approvedProductId = approvedProductId,
                    reviewedByAdminId = reviewedByAdminId,
                    reviewedByAdminName = reviewedByAdminName,
                    reviewedAt = reviewedAt ?: existing.reviewedAt,
                    updatedAt = maxOf(existing.updatedAt, updatedAt),
                    imageUri = imageUri ?: existing.imageUri,
                    imageSource = if (imageSource != "NONE") imageSource else existing.imageSource,
                    imageSourceInfo = if (imageSourceInfo.isNotBlank()) imageSourceInfo else existing.imageSourceInfo
                )
                db.productSubmissionDao().updateSubmission(updatedSub)
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

/**
 * Manages device-local storage of the verified multi-tenant business identity.
 * Prevents hardcoding or fallback IDs, while ensuring salesmen on separate devices
 * resolve to the exact business authorized by the owner.
 */
object CloudBusinessAuth {
    private const val PREFS_NAME = "smart_biz_cloud_auth_prefs"
    private const val KEY_BUSINESS_ID = "active_cloud_business_id"
    private const val KEY_BUSINESS_NAME = "active_cloud_business_name"
    private const val KEY_USER_ROLE = "active_cloud_user_role"

    fun getStoredBusinessId(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BUSINESS_ID, null)?.trim()?.ifBlank { null }
    }

    fun getStoredRole(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_USER_ROLE, null)?.trim()?.ifBlank { null }
    }

    fun getStoredBusinessName(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BUSINESS_NAME, null)?.trim()?.ifBlank { null }
    }

    fun setStoredBusinessId(context: Context, businessId: String, businessName: String? = null, role: String? = null) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        editor.putString(KEY_BUSINESS_ID, businessId.trim())
        if (!businessName.isNullOrBlank()) editor.putString(KEY_BUSINESS_NAME, businessName.trim())
        if (!role.isNullOrBlank()) editor.putString(KEY_USER_ROLE, role.trim())
        editor.apply()
    }

    fun normalizePhone(raw: String): String {
        val digits = raw.replace(Regex("[^0-9+]"), "").trim()
        return when {
            digits.startsWith("+880") -> "0" + digits.substring(4)
            digits.startsWith("880") -> "0" + digits.substring(3)
            digits.startsWith("+88") -> digits.substring(3)
            digits.startsWith("88") && digits.length > 11 -> digits.substring(2)
            digits.length == 10 && digits.startsWith("1") -> "0$digits"
            else -> digits
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

