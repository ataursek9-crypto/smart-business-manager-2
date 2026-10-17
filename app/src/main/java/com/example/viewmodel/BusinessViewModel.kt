package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.data.repository.BusinessRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.example.util.BengaliVoiceHelper
import com.example.data.repository.SmartReminderManager
import com.example.data.repository.StockVerificationRepository
import com.example.data.repository.AdminMismatchAction
import java.util.Calendar
import java.util.UUID

import com.example.data.storage.SavedAccount
import com.example.data.storage.SavedAccountManager
import kotlinx.coroutines.tasks.await
import com.example.data.sync.CloudBusinessAuth
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

data class DashboardSummary(
    val todaySales: Double = 0.0,
    val todayCollected: Double = 0.0,
    val totalDue: Double = 0.0,
    val totalApprovedExpense: Double = 0.0,
    val netProfit: Double = 0.0,
    val lowStockCount: Int = 0,
    val pendingExpenseCount: Int = 0,
    val totalStockValuation: Double = 0.0
)

// Customer Due Item with last transaction timestamp & totals
data class CustomerDueItem(
    val customer: Customer,
    val lastTransactionDate: Long = 0L,
    val totalTransactionsCount: Int = 0
)

// Sanitized stock view for Salesman (purchase price hidden)
data class SalesmanProductView(
    val id: String,
    val name: String,
    val sku: String,
    val category: String,
    val sellingPrice: Double,
    val currentStock: Double,
    val unit: String,
    val imageUri: String? = null,
    val imageSource: String = "NONE"
)

class BusinessViewModel(application: Application) : AndroidViewModel(application) {
    val productImageService = com.example.data.service.ProductImageService(application)
    val repository = BusinessRepository(AppDatabase.getDatabase(application), productImageService)
    val savedAccountManager = SavedAccountManager.getInstance(application)
    val savedAccounts: StateFlow<List<SavedAccount>> = savedAccountManager.savedAccounts
    val keepAccountsOnLogout: StateFlow<Boolean> = savedAccountManager.keepOnLogout

    val syncManager = com.example.data.sync.CloudSyncManager(application, repository.getDbInstance())
    val syncState: StateFlow<com.example.data.sync.SyncState> = syncManager.syncState

    private val _activeBusinessId = MutableStateFlow(
        CloudBusinessAuth.getStoredBusinessId(application) ?: ""
    )
    val activeBusinessId: StateFlow<String> = _activeBusinessId.asStateFlow()

    fun getActiveBusinessId(): String {
        val stored = CloudBusinessAuth.getStoredBusinessId(getApplication())
        if (!stored.isNullOrBlank()) return stored
        val current = _currentUser.value
        if (current != null && current.role == UserRole.ADMIN) {
            val fUid = try { FirebaseAuth.getInstance().currentUser?.uid } catch (_: Exception) { null }
            val id = fUid ?: current.id
            CloudBusinessAuth.setStoredBusinessId(getApplication(), id, role = UserRole.ADMIN.name)
            _activeBusinessId.value = id
            return id
        }
        return ""
    }

    fun refreshActiveBusinessId() {
        _activeBusinessId.value = getActiveBusinessId()
    }

    fun triggerSync(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        syncManager.triggerSync(onResult)
    }

    fun connectSalesmanToBusiness(businessId: String, onResult: (Boolean, String) -> Unit) {
        val cleanBizId = businessId.trim()
        if (cleanBizId.isBlank()) {
            onResult(false, "সঠিক ক্লাউড বিজনেস আইডি দিন!")
            return
        }
        viewModelScope.launch {
            try {
                val fUser = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null }
                if (fUser == null || fUser.uid.isBlank()) {
                    onResult(false, "ক্লাউড ব্যবসার সাথে যুক্ত হতে গুগল সাইন-ইন আবশ্যক!")
                    return@launch
                }

                val firestore = FirebaseFirestore.getInstance()
                val bizDoc = firestore.collection("businesses").document(cleanBizId).get().await()
                if (!bizDoc.exists()) {
                    onResult(false, "এই বিজনেস আইডি ($cleanBizId) খুঁজে পাওয়া যায়নি! সঠিক আইডি লিখুন।")
                    return@launch
                }

                val bizName = bizDoc.getString("businessName") ?: "স্মার্ট বিজনেস"
                val user = _currentUser.value

                // 1. Write/Link active staff profile in Firestore under this business
                val staffDocRef = firestore.collection("businesses").document(cleanBizId).collection("staff").document(fUser.uid)
                val staffData = mapOf(
                    "id" to fUser.uid,
                    "name" to (user?.name ?: fUser.displayName ?: "সেলস প্রতিনিধি"),
                    "phone" to (user?.phone ?: ""),
                    "email" to (fUser.email ?: ""),
                    "role" to UserRole.SALESMAN.name,
                    "isActive" to true,
                    "businessId" to cleanBizId,
                    "linkedAt" to System.currentTimeMillis()
                )
                staffDocRef.set(staffData, SetOptions.merge()).await()

                // 2. Also register in root staff_memberships for quick lookups
                firestore.collection("staff_memberships").document(fUser.uid).set(
                    mapOf(
                        "userId" to fUser.uid,
                        "businessId" to cleanBizId,
                        "role" to UserRole.SALESMAN.name,
                        "isActive" to true,
                        "email" to (fUser.email ?: ""),
                        "updatedAt" to System.currentTimeMillis()
                    ),
                    SetOptions.merge()
                ).await()

                // 3. Save in device-local preferences
                CloudBusinessAuth.setStoredBusinessId(
                    context = getApplication(),
                    businessId = cleanBizId,
                    businessName = bizName,
                    role = UserRole.SALESMAN.name
                )
                _activeBusinessId.value = cleanBizId

                // 4. Ensure local user in Room is registered as SALESMAN
                val localUser = user ?: repository.getUserById(fUser.uid) ?: User(
                    id = fUser.uid,
                    name = fUser.displayName ?: "সেলস প্রতিনিধি",
                    phone = fUser.phoneNumber ?: "01800000000",
                    role = UserRole.SALESMAN,
                    pinHash = repository.hashPin("1234"),
                    isActive = true,
                    accountStatus = UserAccountStatus.ACTIVE
                )
                val updatedLocalUser = localUser.copy(id = fUser.uid, role = UserRole.SALESMAN, isActive = true)
                repository.saveUserDirect(updatedLocalUser)
                _currentUser.value = updatedLocalUser

                // 5. Trigger sync immediately to download products and catalog into Room
                syncManager.triggerSync { success, msg ->
                    if (success) {
                        onResult(true, "ব্যবসার সাথে সফলভাবে যুক্ত হয়েছে! সমস্ত পণ্য ও তালিকা সিঙ্ক সম্পন্ন।")
                    } else {
                        onResult(true, "ব্যবসার সাথে যুক্ত হয়েছে। সিঙ্ক বার্তা: $msg")
                    }
                }
            } catch (e: Exception) {
                onResult(false, "কানেকশন ব্যর্থ হয়েছে: ${e.localizedMessage ?: e.message}")
            }
        }
    }

    // Product Image Mode: AUTO vs MANUAL
    val productImageMode: StateFlow<String> = repository.settingsFlow
        .map { it?.productImageMode ?: "AUTO" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "AUTO")

    fun setProductImageMode(mode: String) {
        viewModelScope.launch {
            repository.setProductImageMode(mode)
        }
    }

    suspend fun generateAiProductImage(title: String, category: String, sku: String, unit: String): com.example.data.service.ProductImageResult {
        return productImageService.generateWithAi(title, category, sku, unit)
    }

    suspend fun searchOnlineImages(query: String): List<com.example.data.service.OnlineImageCandidate> {
        val keyword = productImageService.mapCategoryToSearchKeyword(query, query)
        return productImageService.searchOnlineImages(keyword)
    }

    suspend fun downloadOnlineImage(candidate: com.example.data.service.OnlineImageCandidate): com.example.data.service.ProductImageResult {
        return productImageService.downloadAndSaveOnlineImage(candidate)
    }

    // Active User State
    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    // App Lock PIN Verification state
    private val _isUnlocked = MutableStateFlow(true)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    // Raw Products stream from database
    val rawProducts: StateFlow<List<Product>> = repository.products
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val products: StateFlow<List<Product>> = rawProducts

    // Sanitized product list for Salesman UI
    val salesmanProducts: StateFlow<List<SalesmanProductView>> = rawProducts.map { list ->
        list.map {
            SalesmanProductView(
                id = it.id,
                name = it.name,
                sku = it.sku,
                category = it.category,
                sellingPrice = it.sellingPrice,
                currentStock = it.currentStock,
                unit = it.unit,
                imageUri = it.imageUri,
                imageSource = it.imageSource
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val lowStockProducts: StateFlow<List<Product>> = repository.lowStockProducts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customers: StateFlow<List<Customer>> = repository.customers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSales: StateFlow<List<Sale>> = repository.allSales
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allReceipts: StateFlow<List<PaymentReceipt>> = repository.allReceipts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allExpenses: StateFlow<List<Expense>> = repository.allExpenses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val stockLogs: StateFlow<List<StockLog>> = repository.stockLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val auditLogs: StateFlow<List<AuditLog>> = repository.auditLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val companySettings: StateFlow<CompanySettings> = repository.settingsFlow
        .map { it ?: CompanySettings() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CompanySettings())

    val users: StateFlow<List<User>> = repository.users
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSubmissions: StateFlow<List<ProductSubmission>> = repository.allProductSubmissions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingSubmissionsCount: StateFlow<Int> = repository.pendingProductSubmissionsCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val companies: StateFlow<List<Company>> = repository.companies
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val companyPurchases: StateFlow<List<CompanyPurchase>> = repository.companyPurchases
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val companyPayments: StateFlow<List<CompanyPayment>> = repository.companyPayments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recycleBinItems: StateFlow<List<RecycleBinItem>> = repository.recycleBinItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recycleBinCount: StateFlow<Int> = repository.recycleBinCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val testProducts: StateFlow<List<Product>> = repository.testProducts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val testCompanies: StateFlow<List<Company>> = repository.testCompanies
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val testCustomers: StateFlow<List<Customer>> = repository.testCustomers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val testSales: StateFlow<List<Sale>> = repository.testSales
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val testReceipts: StateFlow<List<PaymentReceipt>> = repository.testReceipts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val testExpenses: StateFlow<List<Expense>> = repository.testExpenses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Test Due Customer Detection Flows
    private val _detectedTestDueCustomers = MutableStateFlow<List<DetectedTestDueCustomer>>(emptyList())
    val detectedTestDueCustomers: StateFlow<List<DetectedTestDueCustomer>> = _detectedTestDueCustomers.asStateFlow()

    private val _isDetectingTestDues = MutableStateFlow(false)
    val isDetectingTestDues: StateFlow<Boolean> = _isDetectingTestDues.asStateFlow()

    // Role-filtered submissions: Salesman only sees their submissions, Admin sees all
    val mySubmissions: StateFlow<List<ProductSubmission>> = combine(_currentUser, allSubmissions) { user, subs ->
        if (user == null) emptyList()
        else if (user.role == UserRole.ADMIN) subs
        else subs.filter { it.salesmanId == user.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Feedback message
    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    // Smart Reminder Service & Voice Helper
    val voiceHelper = BengaliVoiceHelper(application)
    val reminderManager = SmartReminderManager(application, repository.getDbInstance(), voiceHelper)
    val isBengaliTtsSupported: StateFlow<Boolean> = voiceHelper.isBengaliSupported
    val ttsStatusMessage: StateFlow<String> = voiceHelper.statusMessage

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activeReminders: StateFlow<List<ReminderItem>> = _currentUser.flatMapLatest { user ->
        if (user == null) {
            flowOf(emptyList())
        } else if (user.role == UserRole.ADMIN) {
            repository.getAdminRemindersFlow()
        } else {
            repository.getSalesmanRemindersFlow(user.id)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val pendingRemindersCount: StateFlow<Int> = _currentUser.flatMapLatest { user ->
        if (user == null) {
            flowOf(0)
        } else if (user.role == UserRole.ADMIN) {
            repository.getAdminPendingCountFlow()
        } else {
            repository.getSalesmanPendingCountFlow(user.id)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val userReminderPreference: StateFlow<ReminderPreference> = _currentUser.flatMapLatest { user ->
        if (user == null) {
            flowOf(ReminderPreference(userId = "default"))
        } else {
            repository.getReminderPreferenceFlow(user.id).map {
                it ?: ReminderPreference(userId = user.id)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReminderPreference(userId = "default"))

    // Admin-to-Salesman Alert Repository
    val alertRepository = com.example.data.repository.AlertRepository(repository.getDbInstance(), application, voiceHelper)

    val adminAlertDetails: StateFlow<List<AdminAlertDetail>> = alertRepository.adminAlertDetailsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val salesmanAlerts: StateFlow<List<SalesmanAlertView>> = _currentUser.flatMapLatest { user ->
        if (user == null || user.role != UserRole.SALESMAN) {
            flowOf(emptyList())
        } else {
            alertRepository.getSalesmanAlertsFlow(user.id)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val salesmanPendingAlertsCount: StateFlow<Int> = _currentUser.flatMapLatest { user ->
        if (user == null || user.role != UserRole.SALESMAN) {
            flowOf(0)
        } else {
            alertRepository.getSalesmanPendingCountFlow(user.id)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // --- Shop Stock Verification & Audit System ---
    val stockVerificationRepository = StockVerificationRepository(repository.getDbInstance(), alertRepository)

    val allStockVerifications: StateFlow<List<StockVerification>> = stockVerificationRepository.getAllVerificationsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingStockApprovals: StateFlow<List<StockVerification>> = stockVerificationRepository.getPendingApprovalsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingStockApprovalsCount: StateFlow<Int> = stockVerificationRepository.getPendingApprovalCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val stockVerificationSchedules: StateFlow<List<StockVerificationSchedule>> = stockVerificationRepository.getAllSchedulesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val productVerificationItems: StateFlow<List<ProductVerificationItem>> = combine(
        products,
        stockVerificationSchedules,
        allStockVerifications
    ) { prods, schedules, verifs ->
        val scheduleMap = schedules.associateBy { it.productId }
        val verifsByProduct = verifs.groupBy { it.productId }
        val now = System.currentTimeMillis()

        prods.map { prod ->
            val sched = scheduleMap[prod.id]
            val prodVerifs = verifsByProduct[prod.id].orEmpty()
            val lastVerif = prodVerifs.firstOrNull { it.status == VerificationStatus.MATCHED || it.status == VerificationStatus.APPROVED_ADJUSTED }
                ?: prodVerifs.firstOrNull()
            val pendingMismatch = prodVerifs.firstOrNull { it.status == VerificationStatus.MISMATCH_PENDING || it.status == VerificationStatus.RECOUNT_REQUESTED }

            val nextDueAt = sched?.nextDueAt ?: 0L
            val isOverdue = nextDueAt > 0L && now > nextDueAt
            val lastTime = lastVerif?.verifiedAt ?: sched?.lastVerifiedAt ?: 0L
            val daysSince = if (lastTime > 0) ((now - lastTime) / (24L * 60 * 60 * 1000L)).toInt() else -1

            ProductVerificationItem(
                product = prod,
                schedule = sched,
                lastVerification = lastVerif,
                pendingMismatch = pendingMismatch,
                isOverdue = isOverdue,
                daysSinceLastVerification = daysSince
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val stockControlSummaryStats: StateFlow<StockControlSummaryStats> = combine(
        productVerificationItems,
        allStockVerifications,
        pendingStockApprovals
    ) { items, verifs, pendingApprovals ->
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val verifiedToday = verifs.count { it.verifiedAt >= startOfToday }
        val lastVerifTime = verifs.maxOfOrNull { it.verifiedAt }
        val mismatches = verifs.filter { it.difference != 0.0 }
        val resolvedMismatches = mismatches.count { it.isResolved }
        val overdueCount = items.count { it.isOverdue }
        val pendingCount = items.count { it.lastVerification == null || it.isOverdue }

        StockControlSummaryStats(
            totalProducts = items.size,
            lastVerificationTime = lastVerifTime,
            verifiedTodayCount = verifiedToday,
            pendingVerificationCount = pendingCount,
            mismatchCount = mismatches.size,
            pendingAdminApprovalCount = pendingApprovals.size,
            resolvedMismatchesCount = resolvedMismatches,
            overdueCount = overdueCount
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StockControlSummaryStats())

    private val _isDefaultAdminPinActive = MutableStateFlow(false)
    val isDefaultAdminPinActive: StateFlow<Boolean> = _isDefaultAdminPinActive.asStateFlow()

    init {
        viewModelScope.launch {
            // Real business mode: Essential accounts and settings only, no fake demo records
            repository.seedInitialDataIfEmpty(includeSampleDemoData = false)
            checkDefaultAdminPinStatus()
            reminderManager.schedulePeriodicCheck()
            alertRepository.checkAndDispatchScheduledAlerts()
        }
        viewModelScope.launch {
            products.collectLatest { prodList ->
                if (prodList.isNotEmpty()) {
                    stockVerificationRepository.ensureSchedulesForProducts(prodList)
                }
            }
        }
        viewModelScope.launch {
            _currentUser.collectLatest { user ->
                if (user != null) {
                    reminderManager.scanAndSyncReminders(user)
                    alertRepository.checkAndDispatchScheduledAlerts()
                    if (user.role == UserRole.ADMIN) {
                        scanTestDueCustomers()
                        checkDefaultAdminPinStatus()
                    }
                }
            }
        }
        viewModelScope.launch {
            customers.collectLatest {
                scanTestDueCustomers()
            }
        }
    }

    // Role-filtered sales
    val mySales: StateFlow<List<Sale>> = combine(_currentUser, allSales) { user, sales ->
        if (user == null) emptyList()
        else if (user.role == UserRole.ADMIN) sales
        else sales.filter { it.salesmanId == user.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Role-filtered receipts
    val myReceipts: StateFlow<List<PaymentReceipt>> = combine(_currentUser, allReceipts) { user, receipts ->
        if (user == null) emptyList()
        else if (user.role == UserRole.ADMIN) receipts
        else receipts.filter { it.salesmanId == user.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Role-filtered expenses
    val myExpenses: StateFlow<List<Expense>> = combine(_currentUser, allExpenses) { user, expenses ->
        if (user == null) emptyList()
        else if (user.role == UserRole.ADMIN) expenses
        else expenses.filter { it.salesmanId == user.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // -------------------------------------------------------------
    // Customer Due Lists (Role-based & Accurately filtered > 0 due)
    // -------------------------------------------------------------

    // Helper map of customerId to last transaction timestamp (from sales & receipts)
    private val lastTransactionByCustomer: Flow<Map<String, Long>> = combine(allSales, allReceipts) { sales, receipts ->
        val map = mutableMapOf<String, Long>()
        for (sale in sales) {
            val curr = map[sale.customerId] ?: 0L
            if (sale.createdAt > curr) map[sale.customerId] = sale.createdAt
        }
        for (rct in receipts) {
            val curr = map[rct.customerId] ?: 0L
            if (rct.createdAt > curr) map[rct.customerId] = rct.createdAt
        }
        map
    }

    // Publicly exposed map of customerId to last transaction timestamp
    val lastTransactionDateMap: StateFlow<Map<String, Long>> = lastTransactionByCustomer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // 1. Admin Due Customers List (All customers with currentDue > 0.0)
    val adminDueCustomers: StateFlow<List<CustomerDueItem>> = combine(
        customers,
        lastTransactionByCustomer
    ) { custList, txMap ->
        custList
            .filter { it.currentDue > 0.0 }
            .sortedByDescending { it.currentDue }
            .map { c ->
                val lastTx = txMap[c.id] ?: c.updatedAt
                CustomerDueItem(customer = c, lastTransactionDate = lastTx)
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 2. Salesman Due Customers List:
    // Salesman sees ONLY customers that have interacted with them (via salesman's sales or receipts)
    // and have currentDue > 0.0. Other salesmen's customers are strictly excluded!
    val salesmanDueCustomers: StateFlow<List<CustomerDueItem>> = combine(
        _currentUser,
        customers,
        mySales,
        myReceipts,
        lastTransactionByCustomer
    ) { user, custList, sales, receipts, txMap ->
        if (user == null) {
            emptyList()
        } else if (user.role == UserRole.ADMIN) {
            // Admin gets all due customers
            custList
                .filter { it.currentDue > 0.0 }
                .sortedByDescending { it.currentDue }
                .map { c ->
                    CustomerDueItem(customer = c, lastTransactionDate = txMap[c.id] ?: c.updatedAt)
                }
        } else {
            // Authorized customer IDs for this salesman
            val authorizedCustomerIds = mutableSetOf<String>()
            sales.forEach { authorizedCustomerIds.add(it.customerId) }
            receipts.forEach { authorizedCustomerIds.add(it.customerId) }

            val targetList = if (authorizedCustomerIds.isEmpty()) {
                custList.filter { it.id == "c_1" }
            } else {
                custList.filter { authorizedCustomerIds.contains(it.id) }
            }

            targetList
                .filter { it.currentDue > 0.0 }
                .sortedByDescending { it.currentDue }
                .map { c ->
                    CustomerDueItem(customer = c, lastTransactionDate = txMap[c.id] ?: c.updatedAt)
                }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 3. Authorized customers: Admin sees all customers, Salesman sees only their authorized customers
    val authorizedCustomers: StateFlow<List<Customer>> = combine(
        _currentUser,
        customers,
        mySales,
        myReceipts
    ) { user, custList, sales, receipts ->
        if (user == null) {
            emptyList()
        } else if (user.role == UserRole.ADMIN) {
            custList
        } else {
            val authorizedCustomerIds = mutableSetOf<String>()
            sales.forEach { authorizedCustomerIds.add(it.customerId) }
            receipts.forEach { authorizedCustomerIds.add(it.customerId) }
            if (authorizedCustomerIds.isEmpty()) {
                custList.filter { it.id == "c_1" }
            } else {
                custList.filter { authorizedCustomerIds.contains(it.id) }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 4. Role-appropriate due customers list
    val authorizedDueCustomers: StateFlow<List<CustomerDueItem>> = combine(
        _currentUser,
        adminDueCustomers,
        salesmanDueCustomers
    ) { user, adminList, salesmanList ->
        if (user?.role == UserRole.ADMIN) adminList else salesmanList
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Calculated Dashboard Metrics (For Admin Only - includes valuation & profit)
    val adminDashboardSummary: StateFlow<DashboardSummary> = combine(
        allSales,
        allReceipts,
        customers,
        allExpenses,
        rawProducts
    ) { sales, receipts, custs, expenses, prods ->
        val now = System.currentTimeMillis()
        val oneDayMillis = 24 * 60 * 60 * 1000L
        val todayStart = now - (now % oneDayMillis)

        val todaySales = sales.filter { it.createdAt >= todayStart }.sumOf { it.totalAmount }
        val todayReceipts = receipts.filter { it.createdAt >= todayStart }.sumOf { it.amount }
        // Exact sum of positive dues
        val totalDue = custs.filter { it.currentDue > 0.0 }.sumOf { it.currentDue }
        val approvedExpenses = expenses.filter { it.status == ExpenseStatus.APPROVED }
        val totalExpenseAmt = approvedExpenses.sumOf { it.amount }

        var totalStockValuation = 0.0
        prods.forEach {
            totalStockValuation += (it.currentStock * it.purchasePrice)
        }

        val grossMargin = sales.sumOf { it.totalAmount * 0.18 }
        val netProfit = (grossMargin - totalExpenseAmt).coerceAtLeast(-50000.0)

        DashboardSummary(
            todaySales = todaySales,
            todayCollected = todayReceipts,
            totalDue = totalDue,
            totalApprovedExpense = totalExpenseAmt,
            netProfit = netProfit,
            lowStockCount = prods.count { it.currentStock <= it.alertStockLevel },
            pendingExpenseCount = expenses.count { it.status == ExpenseStatus.PENDING },
            totalStockValuation = totalStockValuation
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardSummary())

    // Salesman personal summary (Strictly NO gross margin, NO net profit, NO company stock valuation)
    val salesmanDashboardSummary: StateFlow<DashboardSummary> = combine(
        mySales,
        myReceipts,
        salesmanDueCustomers,
        myExpenses
    ) { sales, receipts, dueItems, expenses ->
        val now = System.currentTimeMillis()
        val oneDayMillis = 24 * 60 * 60 * 1000L
        val todayStart = now - (now % oneDayMillis)

        val todaySales = sales.filter { it.createdAt >= todayStart }.sumOf { it.totalAmount }
        val todayReceipts = receipts.filter { it.createdAt >= todayStart }.sumOf { it.amount }
        val myTotalDue = dueItems.sumOf { it.customer.currentDue }
        val approvedExpenses = expenses.filter { it.status == ExpenseStatus.APPROVED }.sumOf { it.amount }
        val pendingCount = expenses.count { it.status == ExpenseStatus.PENDING }

        DashboardSummary(
            todaySales = todaySales,
            todayCollected = todayReceipts,
            totalDue = myTotalDue,
            totalApprovedExpense = approvedExpenses,
            netProfit = 0.0,
            lowStockCount = 0,
            pendingExpenseCount = pendingCount,
            totalStockValuation = 0.0
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardSummary())

    // Standard Authentication (Sales representatives & staff)
    fun login(phone: String, pin: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            if (phone.isBlank() || pin.isBlank()) {
                onResult(false, "ফোন নম্বর এবং পিন কোড সঠিকভাবে লিখুন!")
                return@launch
            }
            when (val auth = repository.authenticateUser(phone, pin)) {
                is BusinessRepository.AuthResult.Success -> {
                    if (auth.user.role == UserRole.ADMIN) {
                        _currentUser.value = null
                        onResult(false, "অননুমোদিত অ্যাকাউন্ট! এটি সাধারণ বিক্রয় কর্মীদের লগইন স্ক্রিন।")
                    } else {
                        _currentUser.value = auth.user
                        onResult(true, "স্বাগতম, ${auth.user.name}!")
                    }
                }
                is BusinessRepository.AuthResult.Error -> {
                    onResult(false, auth.message)
                }
            }
        }
    }

    // Secret Admin Login Action (Strict ADMIN Role Verification)
    fun loginAdmin(phone: String, pin: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            if (phone.isBlank() || pin.isBlank()) {
                onResult(false, "ফোন নম্বর এবং পিন কোড সঠিকভাবে লিখুন!")
                return@launch
            }
            when (val auth = repository.authenticateAdmin(phone, pin)) {
                is BusinessRepository.AuthResult.Success -> {
                    _currentUser.value = auth.user
                    onResult(true, "এডমিন প্যানেলে স্বাগতম, ${auth.user.name}!")
                }
                is BusinessRepository.AuthResult.Error -> {
                    _currentUser.value = null
                    onResult(false, auth.message)
                }
            }
        }
    }

    fun logout() {
        _currentUser.value = null
        try {
            com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
        } catch (_: Exception) {}
        savedAccountManager.handleLogout()
    }

    /**
     * Authenticate or map an authenticated Firebase User into the local business role.
     * Enforces:
     * 1. Does not automatically grant admin access to every Google account.
     * 2. Verifies user belongs to an authorized business and has an assigned role.
     * 3. Rejects unknown/unauthorized Google accounts.
     */
    /**
     * Authenticate or map an authenticated Firebase User into the local business role.
     */
    fun handleGoogleSignInSuccess(
        firebaseUser: com.google.firebase.auth.FirebaseUser,
        onResult: (Boolean, String, UserRole?) -> Unit
    ) {
        handleGoogleUserCredentials(
            uid = firebaseUser.uid,
            email = firebaseUser.email,
            displayName = firebaseUser.displayName,
            phoneNumber = firebaseUser.phoneNumber,
            onResult = onResult
        )
    }

    /**
     * Internal credential mapping logic:
     * Enforces:
     * 1. Does not automatically grant admin access to every Google account.
     * 2. Verifies user belongs to an authorized business and has an assigned role.
     * 3. Rejects unknown/unauthorized Google accounts.
     */
    fun handleGoogleUserCredentials(
        uid: String,
        email: String?,
        displayName: String?,
        phoneNumber: String?,
        onResult: (Boolean, String, UserRole?) -> Unit
    ) {
        viewModelScope.launch {
            val phone = phoneNumber ?: ""
            val cleanEmail = email ?: ""
            val name = displayName ?: "গুগল ব্যবহারকারী"

            // 1. Check if user already exists in Room by Firebase UID
            val existingUserByUid = repository.getUserById(uid)
            if (existingUserByUid != null) {
                if (!existingUserByUid.isActive || existingUserByUid.accountStatus != UserAccountStatus.ACTIVE) {
                    _currentUser.value = null
                    onResult(false, "আপনার অ্যাকাউন্টটি সাময়িকভাবে স্থগিত বা নিষ্ক্রিয় করা হয়েছে। অ্যাডমিনের সাথে যোগাযোগ করুন।", null)
                    return@launch
                }
                _currentUser.value = existingUserByUid
                onResult(true, "স্বাগতম, ${existingUserByUid.name}!", existingUserByUid.role)
                return@launch
            }

            // 2. Check if user already exists by phone (if Google provided verified phone number)
            if (phone.isNotBlank()) {
                val existingUserByPhone = repository.getUserByPhone(phone)
                if (existingUserByPhone != null) {
                    if (!existingUserByPhone.isActive || existingUserByPhone.accountStatus != UserAccountStatus.ACTIVE) {
                        _currentUser.value = null
                        onResult(false, "আপনার অ্যাকাউন্টটি সাময়িকভাবে স্থগিত বা নিষ্ক্রিয়।", null)
                        return@launch
                    }
                    val linkedUser = existingUserByPhone.copy(id = uid)
                    repository.saveUserDirect(linkedUser)
                    _currentUser.value = linkedUser
                    onResult(true, "স্বাগতম, ${linkedUser.name}!", linkedUser.role)
                    return@launch
                }
            }

            // 3. Check if this is the registered primary business owner (ataurgonee4@gmail.com or authorized admin email)
            val isPrimaryOwner = cleanEmail.isNotBlank() && repository.isAuthorizedAdminEmail(cleanEmail)

            if (isPrimaryOwner) {
                val adminUser = User(
                    id = uid,
                    name = name.ifBlank { "মোঃ আতাউর রহমান" },
                    phone = phone.ifBlank { "01700000000" },
                    role = UserRole.ADMIN,
                    pinHash = repository.hashPin("1234"),
                    isActive = true,
                    accountStatus = UserAccountStatus.ACTIVE
                )
                repository.saveUserDirect(adminUser)
                CloudBusinessAuth.setStoredBusinessId(
                    context = getApplication(),
                    businessId = uid,
                    role = UserRole.ADMIN.name
                )
                _activeBusinessId.value = uid
                _currentUser.value = adminUser
                onResult(true, "এডমিন প্যানেলে স্বাগতম, ${adminUser.name}!", UserRole.ADMIN)
                return@launch
            }

            // 4. Check if this Google user has a registered staff membership in Firestore
            try {
                val firestore = FirebaseFirestore.getInstance()
                val memberDoc = firestore.collection("staff_memberships").document(uid).get().await()
                if (memberDoc.exists()) {
                    val bizId = memberDoc.getString("businessId")
                    val roleStr = memberDoc.getString("role")
                    val isActive = memberDoc.getBoolean("isActive") ?: false
                    if (!bizId.isNullOrBlank() && isActive) {
                        val role = if (roleStr == "ADMIN") UserRole.ADMIN else UserRole.SALESMAN
                        val staffUser = User(
                            id = uid,
                            name = name,
                            phone = phone.ifBlank { "01800000000" },
                            role = role,
                            pinHash = repository.hashPin("1234"),
                            isActive = true,
                            accountStatus = UserAccountStatus.ACTIVE
                        )
                        repository.saveUserDirect(staffUser)
                        CloudBusinessAuth.setStoredBusinessId(
                            context = getApplication(),
                            businessId = bizId,
                            role = role.name
                        )
                        _activeBusinessId.value = bizId
                        _currentUser.value = staffUser
                        triggerSync()
                        onResult(true, "স্বাগতম, ${staffUser.name}!", role)
                        return@launch
                    }
                }
            } catch (_: Exception) {}

            // 5. Unknown/unauthorized Google user: DO NOT GRANT ADMIN OR GENERAL ACCESS AUTOMATICALLY
            _currentUser.value = null
            onResult(
                false,
                "এই গুগল অ্যাকাউন্টটি (${if (cleanEmail.isNotBlank()) cleanEmail else name}) কোনো অনুমোদিত ব্যবসা বা অ্যাকাউন্টের সাথে যুক্ত নয়। অনুগ্রহ করে অ্যাডমিনের সাথে যোগাযোগ করুন অথবা পিন দিয়ে লগইন করুন।",
                null
            )
        }
    }

    /**
     * Links an authenticated Google account to a specific business as an authorized staff member (salesman)
     * using the Admin's Business Code, registered phone, and PIN.
     * Registers the salesman in Firestore under /businesses/{bizId}/staff/{uid} and /staff_memberships/{uid},
     * stores the businessId locally, and triggers an immediate cloud sync to fetch products.
     */
    fun linkGoogleAccountWithBusiness(
        firebaseUser: com.google.firebase.auth.FirebaseUser,
        businessId: String,
        phone: String,
        pin: String,
        onResult: (Boolean, String, UserRole?) -> Unit
    ) {
        viewModelScope.launch {
            val cleanBizId = businessId.trim()
            val cleanPhone = phone.trim()
            val cleanPin = pin.trim()

            if (cleanBizId.isBlank()) {
                onResult(false, "অ্যাডমিনের ক্লাউড বিজনেস কোড দিন!", null)
                return@launch
            }
            if (cleanPhone.isBlank() || cleanPin.isBlank()) {
                onResult(false, "মোবাইল নম্বর এবং পিন কোড সঠিকভাবে লিখুন!", null)
                return@launch
            }

            try {
                val firestore = FirebaseFirestore.getInstance()

                // 1. Verify business document exists in Firestore
                val bizDoc = firestore.collection("businesses").document(cleanBizId).get().await()
                if (!bizDoc.exists()) {
                    onResult(false, "এই বিজনেস কোড ($cleanBizId) ক্লাউডে পাওয়া যায়নি! অ্যাডমিনের সঠিক কোড দিন।", null)
                    return@launch
                }
                val bizName = bizDoc.getString("businessName") ?: "স্মার্ট বিজনেস"

                // 2. Verify staff authorization: check local user or Firestore staff_by_phone
                var isAuthorized = false
                var staffRole = UserRole.SALESMAN
                var staffName = firebaseUser.displayName ?: "সেলস প্রতিনিধি"

                val localAuth = repository.authenticateUser(cleanPhone, cleanPin)
                if (localAuth is BusinessRepository.AuthResult.Success) {
                    isAuthorized = true
                    staffRole = localAuth.user.role
                    if (localAuth.user.name.isNotBlank()) staffName = localAuth.user.name
                } else {
                    // Check remote Firestore staff_by_phone under this business
                    try {
                        val staffPhoneDoc = firestore.collection("businesses").document(cleanBizId)
                            .collection("staff_by_phone").document(cleanPhone).get().await()
                        if (staffPhoneDoc.exists()) {
                            val isActive = staffPhoneDoc.getBoolean("isActive") ?: true
                            if (isActive) {
                                isAuthorized = true
                                val remoteName = staffPhoneDoc.getString("name")
                                if (!remoteName.isNullOrBlank()) staffName = remoteName
                            }
                        }
                    } catch (_: Exception) {}
                }

                if (!isAuthorized) {
                    onResult(false, "ভুল ফোন নম্বর অথবা পিন কোড! অনুমোদিত স্টাফ হিসেবে যাচাই করা সম্ভব হয়নি।", null)
                    return@launch
                }

                // 3. Register active staff profile in Firestore under /businesses/{cleanBizId}/staff/{uid}
                val staffDocRef = firestore.collection("businesses").document(cleanBizId)
                    .collection("staff").document(firebaseUser.uid)
                val staffData = mapOf(
                    "id" to firebaseUser.uid,
                    "name" to staffName,
                    "phone" to cleanPhone,
                    "email" to (firebaseUser.email ?: ""),
                    "role" to staffRole.name,
                    "isActive" to true,
                    "businessId" to cleanBizId,
                    "linkedAt" to System.currentTimeMillis()
                )
                staffDocRef.set(staffData, SetOptions.merge()).await()

                // 4. Register in root /staff_memberships/{uid}
                firestore.collection("staff_memberships").document(firebaseUser.uid).set(
                    mapOf(
                        "userId" to firebaseUser.uid,
                        "businessId" to cleanBizId,
                        "role" to staffRole.name,
                        "isActive" to true,
                        "email" to (firebaseUser.email ?: ""),
                        "updatedAt" to System.currentTimeMillis()
                    ),
                    SetOptions.merge()
                ).await()

                // 5. Save in device-local preferences
                CloudBusinessAuth.setStoredBusinessId(
                    context = getApplication(),
                    businessId = cleanBizId,
                    businessName = bizName,
                    role = staffRole.name
                )
                _activeBusinessId.value = cleanBizId

                // 6. Save in local Room database
                val localUser = User(
                    id = firebaseUser.uid,
                    name = staffName,
                    phone = cleanPhone,
                    role = staffRole,
                    pinHash = repository.hashPin(cleanPin),
                    isActive = true,
                    accountStatus = UserAccountStatus.ACTIVE
                )
                repository.saveUserDirect(localUser)
                _currentUser.value = localUser

                // 7. Trigger immediate cloud synchronization so products are downloaded right away!
                syncManager.triggerSync()

                onResult(true, "ব্যবসার সাথে সফলভাবে যুক্ত হয়েছে! স্বাগতম, ${localUser.name}!", staffRole)
            } catch (e: Exception) {
                onResult(false, "লিংক ও কানেকশন ব্যর্থ: ${e.localizedMessage ?: e.message}", null)
            }
        }
    }

    /**
     * Securely links an authenticated Google account to an existing registered staff/admin profile
     * after local PIN verification.
     */
    fun linkGoogleAccountWithPin(
        firebaseUser: com.google.firebase.auth.FirebaseUser,
        phone: String,
        pin: String,
        onResult: (Boolean, String, UserRole?) -> Unit
    ) {
        val existingBizId = CloudBusinessAuth.getStoredBusinessId(getApplication())
        if (!existingBizId.isNullOrBlank()) {
            linkGoogleAccountWithBusiness(
                firebaseUser = firebaseUser,
                businessId = existingBizId,
                phone = phone,
                pin = pin,
                onResult = onResult
            )
        } else {
            linkGoogleCredentialsWithPin(
                uid = firebaseUser.uid,
                displayName = firebaseUser.displayName,
                phone = phone,
                pin = pin,
                onResult = onResult
            )
        }
    }

    fun linkGoogleCredentialsWithPin(
        uid: String,
        displayName: String?,
        phone: String,
        pin: String,
        onResult: (Boolean, String, UserRole?) -> Unit
    ) {
        viewModelScope.launch {
            if (phone.isBlank() || pin.isBlank()) {
                onResult(false, "ফোন নম্বর এবং পিন কোড সঠিকভাবে লিখুন!", null)
                return@launch
            }
            when (val auth = repository.authenticateUser(phone, pin)) {
                is BusinessRepository.AuthResult.Success -> {
                    val localUser = auth.user
                    val linkedUser = localUser.copy(
                        id = uid,
                        name = if (displayName.isNullOrBlank()) localUser.name else displayName
                    )
                    repository.saveUserDirect(linkedUser)
                    _currentUser.value = linkedUser
                    val existingBizId = CloudBusinessAuth.getStoredBusinessId(getApplication())
                    if (!existingBizId.isNullOrBlank()) {
                        CloudBusinessAuth.setStoredBusinessId(
                            context = getApplication(),
                            businessId = existingBizId,
                            role = linkedUser.role.name
                        )
                        _activeBusinessId.value = existingBizId
                        triggerSync()
                    }
                    onResult(true, "গুগল অ্যাকাউন্ট সফলভাবে লিংক করা হয়েছে! স্বাগতম, ${linkedUser.name}!", linkedUser.role)
                }
                is BusinessRepository.AuthResult.Error -> {
                    when (val adminAuth = repository.authenticateAdmin(phone, pin)) {
                        is BusinessRepository.AuthResult.Success -> {
                            val adminUser = adminAuth.user
                            val linkedAdmin = adminUser.copy(
                                id = uid,
                                name = if (displayName.isNullOrBlank()) adminUser.name else displayName
                            )
                            repository.saveUserDirect(linkedAdmin)
                            _currentUser.value = linkedAdmin
                            CloudBusinessAuth.setStoredBusinessId(
                                context = getApplication(),
                                businessId = uid,
                                role = UserRole.ADMIN.name
                            )
                            _activeBusinessId.value = uid
                            onResult(true, "গুগল অ্যাকাউন্ট সফলভাবে এডমিন হিসেবে লিংক করা হয়েছে!", UserRole.ADMIN)
                        }
                        is BusinessRepository.AuthResult.Error -> {
                            onResult(false, "ভুল ফোন নম্বর অথবা পিন কোড! লিংক করা সম্ভব হয়নি।", null)
                        }
                    }
                }
            }
        }
    }

    // Saved Account Operations
    fun saveRememberedPhone(phone: String) {
        savedAccountManager.saveAccount(phone)
    }

    fun removeRememberedPhone(phone: String) {
        savedAccountManager.removeAccount(phone)
    }

    fun clearAllRememberedPhones() {
        savedAccountManager.clearAll()
    }

    fun setKeepAccountsOnLogout(keep: Boolean) {
        savedAccountManager.setKeepOnLogout(keep)
    }

    suspend fun getAccountDetailsForPhone(phone: String): User? {
        return repository.getUserByPhone(phone)
    }

    // New Sale creation
    fun createSale(
        sale: Sale,
        items: List<SaleItem>,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val user = _currentUser.value ?: return onError("ব্যবহারকারী লগইন অবস্থায় নেই!")
        viewModelScope.launch {
            val res = repository.completeSale(sale, items, user)
            if (res.isSuccess) {
                onSuccess(res.getOrNull() ?: "")
            } else {
                onError(res.exceptionOrNull()?.message ?: "বিক্রি সংরক্ষণ করতে সমস্যা হয়েছে")
            }
        }
    }

    // Money Receipt Collection
    fun collectPayment(
        receipt: PaymentReceipt,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val user = _currentUser.value ?: return onError("ব্যবহারকারী লগইন নেই!")
        viewModelScope.launch {
            val res = repository.collectPayment(receipt, user)
            if (res.isSuccess) {
                onSuccess(res.getOrNull() ?: "")
            } else {
                onError(res.exceptionOrNull()?.message ?: "টাকা আদায় ব্যর্থ হয়েছে")
            }
        }
    }

    // Expense actions
    fun submitExpense(expense: Expense, onComplete: () -> Unit) {
        val user = _currentUser.value ?: return
        viewModelScope.launch {
            try {
                repository.submitExpense(expense, user)
                _toastMessage.emit("খরচের আবেদন সফলভাবে দাখিল করা হয়েছে")
                onComplete()
            } catch (e: Exception) {
                _toastMessage.emit(e.message ?: "ত্রুটি হয়েছে")
            }
        }
    }

    fun updateExpenseStatus(expense: Expense, newStatus: ExpenseStatus, note: String) {
        val user = _currentUser.value ?: return
        if (user.role != UserRole.ADMIN) return
        viewModelScope.launch {
            repository.updateExpense(
                expense.copy(status = newStatus, adminNote = note, reviewedAt = System.currentTimeMillis()),
                user
            )
            _toastMessage.emit("খরচের স্ট্যাটাস আপডেট হয়েছে")
        }
    }

    // Customer & Product actions
    fun saveCustomer(customer: Customer, onDone: () -> Unit) {
        val user = _currentUser.value ?: return
        viewModelScope.launch {
            try {
                repository.saveCustomer(customer, user)
                _toastMessage.emit("কাস্টমার তথ্য সংরক্ষিত হয়েছে")
                onDone()
            } catch (e: Exception) {
                _toastMessage.emit(e.message ?: "ত্রুটি হয়েছে")
            }
        }
    }

    fun toggleCustomerPin(customerId: String, currentPin: Boolean) {
        viewModelScope.launch {
            repository.toggleCustomerPin(customerId, !currentPin)
        }
    }

    fun toggleSalePin(invoiceNumber: String, currentPin: Boolean) {
        viewModelScope.launch {
            repository.toggleSalePin(invoiceNumber, !currentPin)
        }
    }

    fun toggleReceiptPin(receiptNumber: String, currentPin: Boolean) {
        viewModelScope.launch {
            repository.toggleReceiptPin(receiptNumber, !currentPin)
        }
    }

    fun saveProduct(
        product: Product,
        isNew: Boolean,
        reason: String = "",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val user = _currentUser.value ?: return onError("অনুগ্রহ করে প্রথমে লগইন করুন!")
        if (user.role != UserRole.ADMIN) return onError("শুধুমাত্র অ্যাডমিন পণ্য পরিবর্তন করতে পারেন!")
        viewModelScope.launch {
            try {
                val preparedProduct = product.copy(
                    sellingPrice = BusinessRepository.calculateSellingPrice(product.purchasePrice)
                )
                repository.saveProduct(preparedProduct, user, isNew, reason)
                _toastMessage.emit(if (isNew) "নতুন পণ্য সফলভাবে সংরক্ষিত হয়েছে" else "পণ্য সফলভাবে আপডেট হয়েছে")
                onSuccess()

                // Trigger real-time cloud upload to Firestore so separate salesman devices can fetch it
                syncManager.triggerSync()

                // Auto mode background image acquisition if no image was provided
                if (preparedProduct.imageUri.isNullOrBlank() && productImageMode.value == "AUTO") {
                    launch {
                        val autoResult = productImageService.autoAcquireImage(
                            title = preparedProduct.name,
                            category = preparedProduct.category,
                            sku = preparedProduct.sku,
                            unit = preparedProduct.unit
                        )
                        if (autoResult is com.example.data.service.ProductImageResult.Success) {
                            repository.updateProductImage(
                                productId = preparedProduct.id,
                                imageUri = autoResult.filePath,
                                imageSource = autoResult.source,
                                imageSourceInfo = autoResult.info
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                onError(e.message ?: "পণ্য সংরক্ষণে সমস্যা হয়েছে")
            }
        }
    }

    fun adjustProductStock(productId: String, delta: Double, reason: String, onDone: () -> Unit) {
        val user = _currentUser.value ?: return
        if (user.role != UserRole.ADMIN) return
        viewModelScope.launch {
            repository.adjustStockManual(productId, delta, reason, user)
            _toastMessage.emit("স্টক সমন্বয় সম্পন্ন হয়েছে")
            onDone()
        }
    }

    fun updateCompanySettings(settings: CompanySettings) {
        val user = _currentUser.value ?: return
        if (user.role != UserRole.ADMIN) return
        viewModelScope.launch {
            repository.updateSettings(settings, user)
            _toastMessage.emit("ব্যবসার সেটিংস সফলভাবে আপডেট হয়েছে")
        }
    }

    fun saveUser(user: User, onDone: () -> Unit) {
        val current = _currentUser.value ?: return
        if (current.role != UserRole.ADMIN) return
        viewModelScope.launch {
            repository.saveUser(user, current)
            _toastMessage.emit("ব্যবহারকারী সংরক্ষিত হয়েছে")
            onDone()
        }
    }

    fun createSalesman(
        name: String,
        phone: String,
        rawPin: String,
        canDiscount: Boolean,
        onDone: () -> Unit
    ) {
        val current = _currentUser.value ?: return
        if (current.role != UserRole.ADMIN) return
        viewModelScope.launch {
            val hashed = repository.hashPin(rawPin.trim())
            val newUser = User(
                id = "salesman_${System.currentTimeMillis()}",
                name = name.trim(),
                phone = phone.trim(),
                role = UserRole.SALESMAN,
                pinHash = hashed,
                canDiscount = canDiscount,
                accountStatus = UserAccountStatus.ACTIVE
            )
            repository.saveUser(newUser, current)
            _toastMessage.emit("নতুন সেলসম্যান সফলভাবে তৈরি হয়েছে")
            onDone()
        }
    }

    fun checkDefaultAdminPinStatus() {
        viewModelScope.launch {
            _isDefaultAdminPinActive.value = repository.isDefaultAdminCredentialsActive()
        }
    }

    fun updateAdminSecurityCredentials(
        currentPin: String,
        newName: String,
        newPhone: String,
        newPin: String,
        onResult: (Boolean, String) -> Unit
    ) {
        val current = _currentUser.value ?: return onResult(false, "অনুগ্রহ করে লগইন করুন!")
        if (current.role != UserRole.ADMIN) return onResult(false, "অননুমোদিত অ্যাক্সেস!")
        viewModelScope.launch {
            val result = repository.updateAdminSecurityCredentials(
                adminId = current.id,
                currentPin = currentPin,
                newName = newName,
                newPhone = newPhone,
                newPin = newPin
            )
            result.onSuccess {
                _currentUser.value = repository.getUserById(current.id)
                checkDefaultAdminPinStatus()
                _toastMessage.emit("অ্যাডমিন নিরাপত্তা ক্রেডেনশিয়াল সফলভাবে আপডেট হয়েছে")
                onResult(true, "অ্যাডমিন নিরাপত্তা ক্রেডেনশিয়াল সফলভাবে আপডেট হয়েছে")
            }.onFailure { err ->
                onResult(false, err.message ?: "পিন আপডেট ব্যর্থ হয়েছে")
            }
        }
    }

    fun changeSalesmanStatus(
        targetUserId: String,
        newStatus: UserAccountStatus,
        reason: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val admin = _currentUser.value ?: return onError("অনুগ্রহ করে লগইন করুন!")
        if (admin.role != UserRole.ADMIN) return onError("শুধুমাত্র অ্যাডমিন এই কাজটি করতে পারেন!")
        viewModelScope.launch {
            try {
                repository.changeUserAccountStatus(targetUserId, newStatus, reason, admin)
                _toastMessage.emit("স্টাফ অ্যাকাউন্ট স্ট্যাটাস সফলভাবে আপডেট হয়েছে")
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "স্ট্যাটাস পরিবর্তন ব্যর্থ হয়েছে")
            }
        }
    }

    // -------------------------------------------------------------
    // Product Submission & Approval Operations
    // -------------------------------------------------------------

    suspend fun checkPotentialDuplicates(
        productName: String,
        productCode: String = "",
        currentSubmissionId: String? = null
    ): List<String> {
        return repository.checkPotentialDuplicates(productName, productCode, currentSubmissionId)
    }

    fun submitProduct(
        name: String,
        productCode: String = "",
        category: String,
        purchasePrice: Double,
        sellingPrice: Double,
        initialStock: Double,
        unit: String,
        description: String,
        imageUri: String? = null,
        imageSource: String = "NONE",
        imageSourceInfo: String = "",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val user = _currentUser.value ?: return onError("অনুগ্রহ করে প্রথমে লগইন করুন!")
        viewModelScope.launch {
            try {
                val enforcedSellingPrice = if (user.role == UserRole.SALESMAN) {
                    BusinessRepository.calculateSellingPrice(purchasePrice)
                } else {
                    sellingPrice
                }
                val subId = "sub_${System.currentTimeMillis()}"
                val submission = ProductSubmission(
                    id = subId,
                    name = name.trim(),
                    productCode = productCode.trim(),
                    category = category.trim().ifBlank { "সাধারণ" },
                    purchasePrice = purchasePrice,
                    sellingPrice = enforcedSellingPrice,
                    initialStock = initialStock,
                    unit = unit.trim().ifBlank { "পিস" },
                    description = description.trim(),
                    salesmanId = user.id,
                    salesmanName = user.name,
                    salesmanPhone = user.phone,
                    status = ProductSubmissionStatus.PENDING,
                    imageUri = imageUri,
                    imageSource = imageSource,
                    imageSourceInfo = imageSourceInfo
                )
                repository.submitProduct(submission, user)
                _toastMessage.emit("প্রোডাক্ট অনুমোদনের জন্য সফলভাবে জমা দেওয়া হয়েছে")
                onSuccess()

                // Auto mode background image acquisition if no image was provided
                if (imageUri.isNullOrBlank() && productImageMode.value == "AUTO") {
                    launch {
                        val autoResult = productImageService.autoAcquireImage(
                            title = submission.name,
                            category = submission.category,
                            sku = submission.productCode,
                            unit = submission.unit
                        )
                        if (autoResult is com.example.data.service.ProductImageResult.Success) {
                            repository.updateProductSubmissionImage(
                                submissionId = subId,
                                imageUri = autoResult.filePath,
                                imageSource = autoResult.source,
                                imageSourceInfo = autoResult.info
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                onError(e.message ?: "প্রোডাক্ট জমা দিতে সমস্যা হয়েছে")
            }
        }
    }

    fun resubmitProduct(
        submissionId: String,
        name: String,
        productCode: String = "",
        category: String,
        purchasePrice: Double,
        sellingPrice: Double,
        initialStock: Double,
        unit: String,
        description: String,
        imageUri: String? = null,
        imageSource: String = "NONE",
        imageSourceInfo: String = "",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val user = _currentUser.value ?: return onError("অনুগ্রহ করে প্রথমে লগইন করুন!")
        viewModelScope.launch {
            try {
                val enforcedSellingPrice = if (user.role == UserRole.SALESMAN) {
                    BusinessRepository.calculateSellingPrice(purchasePrice)
                } else {
                    sellingPrice
                }
                val dummyUpdated = ProductSubmission(
                    id = submissionId,
                    name = name.trim(),
                    productCode = productCode.trim(),
                    category = category.trim().ifBlank { "সাধারণ" },
                    purchasePrice = purchasePrice,
                    sellingPrice = enforcedSellingPrice,
                    initialStock = initialStock,
                    unit = unit.trim().ifBlank { "পিস" },
                    description = description.trim(),
                    salesmanId = user.id,
                    salesmanName = user.name,
                    salesmanPhone = user.phone,
                    imageUri = imageUri,
                    imageSource = imageSource,
                    imageSourceInfo = imageSourceInfo
                )
                repository.resubmitProductForCorrection(submissionId, dummyUpdated, user)
                _toastMessage.emit("সংশোধিত প্রোডাক্ট সফলভাবে পুনরায় জমা দেওয়া হয়েছে")
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "প্রোডাক্ট পুনরায় জমা দিতে সমস্যা হয়েছে")
            }
        }
    }

    fun approveProductSubmission(
        submissionId: String,
        finalData: ProductSubmission,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val admin = _currentUser.value ?: return onError("অনুগ্রহ করে লগইন করুন!")
        // Rule C.1, C.3: ViewModel layer role check
        if (admin.role != UserRole.ADMIN) {
            return onError("অননুমোদিত অ্যাক্সেস! শুধুমাত্র এডমিন প্রোডাক্ট অনুমোদন করতে পারবেন।")
        }

        viewModelScope.launch {
            try {
                val approved = repository.approveProductSubmission(submissionId, finalData, admin)
                _toastMessage.emit("'${approved.name}' সফলভাবে অনুমোদিত এবং মূল ইনভেন্টরিতে যুক্ত হয়েছে")
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "প্রোডাক্ট অনুমোদন ব্যর্থ হয়েছে")
            }
        }
    }

    fun returnProductForCorrection(
        submissionId: String,
        reason: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val admin = _currentUser.value ?: return onError("অনুগ্রহ করে লগইন করুন!")
        // Rule C.1, C.3: ViewModel layer role check
        if (admin.role != UserRole.ADMIN) {
            return onError("অননুমোদিত অ্যাক্সেস! শুধুমাত্র এডমিন এই কাজটি করতে পারবেন।")
        }
        if (reason.isBlank()) {
            return onError("সংশোধনের কারণ লেখা বাধ্যতামূলক!")
        }

        viewModelScope.launch {
            try {
                repository.returnProductForCorrection(submissionId, reason, admin)
                _toastMessage.emit("প্রোডাক্টটি সংশোধনের জন্য ফেরত পাঠানো হয়েছে")
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "প্রোডাক্ট ফেরত পাঠাতে ব্যর্থ হয়েছে")
            }
        }
    }

    // --- Company / Supplier Due Management ---

    fun saveCompany(
        company: Company,
        isNew: Boolean,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val admin = _currentUser.value ?: return onError("অনুগ্রহ করে লগইন করুন!")
        if (admin.role != UserRole.ADMIN) {
            return onError("অননুমোদিত অ্যাক্সেস! শুধুমাত্র এডমিন কোম্পানি যোগ বা এডিট করতে পারেন।")
        }
        viewModelScope.launch {
            try {
                repository.saveCompany(company, isNew, admin)
                _toastMessage.emit(if (isNew) "কোম্পানি '${company.name}' সফলভাবে যুক্ত হয়েছে" else "কোম্পানির তথ্য সফলভাবে সংরক্ষিত হয়েছে")
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "কোম্পানি সংরক্ষণ ব্যর্থ হয়েছে")
            }
        }
    }

    fun deleteCompany(
        company: Company,
        reason: String = "কোম্পানি ও সাপ্লায়ার তালিকা থেকে নিরাপদ অপসারণ",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val admin = _currentUser.value ?: return onError("অনুগ্রহ করে লগইন করুন!")
        if (admin.role != UserRole.ADMIN) {
            return onError("শুধুমাত্র এডমিন কোম্পানি মুছতে পারেন।")
        }
        viewModelScope.launch {
            try {
                repository.softDeleteCompany(company, admin, reason)
                _toastMessage.emit("কোম্পানি '${company.name}' রিসাইকেল বিনে পাঠানো হয়েছে ✅ (৩০ দিন নিরাপদ)")
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "কোম্পানি মুছতে ব্যর্থ হয়েছে")
            }
        }
    }

    fun recordCompanyPurchase(
        purchase: CompanyPurchase,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val admin = _currentUser.value ?: return onError("অনুগ্রহ করে লগইন করুন!")
        if (admin.role != UserRole.ADMIN) {
            return onError("অননুমোদিত অ্যাক্সেস! শুধুমাত্র এডমিন কোম্পানি ক্রয় হিসাব সংরক্ষণ করতে পারেন।")
        }
        viewModelScope.launch {
            val result = repository.recordCompanyPurchase(purchase, admin)
            if (result.isSuccess) {
                _toastMessage.emit("পণ্য ক্রয়ের চালান সফলভাবে যুক্ত হয়েছে!")
                onSuccess(result.getOrNull() ?: "")
            } else {
                onError(result.exceptionOrNull()?.message ?: "ক্রয় এন্ট্রি ব্যর্থ হয়েছে")
            }
        }
    }

    fun recordCompanyPayment(
        payment: CompanyPayment,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val admin = _currentUser.value ?: return onError("অনুগ্রহ করে লগইন করুন!")
        if (admin.role != UserRole.ADMIN) {
            return onError("অননুমোদিত অ্যাক্সেস! শুধুমাত্র এডমিন কোম্পানিকে বকেয়া পরিশোধ সংরক্ষণ করতে পারেন।")
        }
        viewModelScope.launch {
            val result = repository.recordCompanyPayment(payment, admin)
            if (result.isSuccess) {
                _toastMessage.emit("কোম্পানি পেমেন্ট সফলভাবে সংরক্ষিত হয়েছে!")
                onSuccess(result.getOrNull() ?: "")
            } else {
                onError(result.exceptionOrNull()?.message ?: "পেমেন্ট এন্ট্রি ব্যর্থ হয়েছে")
            }
        }
    }

    // ==========================================
    // Data Protection Center (ডেটা সুরক্ষা কেন্দ্র)
    // ==========================================

    fun softDeleteProduct(
        product: Product,
        reason: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val admin = _currentUser.value ?: return onError("অনুগ্রহ করে লগইন করুন!")
        if (admin.role != UserRole.ADMIN) {
            return onError("শুধুমাত্র এডমিন পণ্য মুছতে পারেন।")
        }
        viewModelScope.launch {
            val result = repository.softDeleteProduct(product, admin, reason)
            if (result.isSuccess) {
                _toastMessage.emit("পণ্য '${product.name}' রিসাইকেল বিনে পাঠানো হয়েছে ✅ (৩০ দিন নিরাপদ)")
                onSuccess()
            } else {
                onError(result.exceptionOrNull()?.message ?: "পণ্য অপসারণ ব্যর্থ হয়েছে")
            }
        }
    }

    fun softDeleteCompany(
        company: Company,
        reason: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        deleteCompany(company, reason, onSuccess, onError)
    }

    fun softDeleteCustomer(
        customer: Customer,
        reason: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val admin = _currentUser.value ?: return onError("অনুগ্রহ করে লগইন করুন!")
        viewModelScope.launch {
            val result = repository.softDeleteCustomer(customer, admin, reason)
            if (result.isSuccess) {
                scanTestDueCustomers()
                _toastMessage.emit("'${customer.name}' রিসাইকেল বিনে স্থানান্তর করা হয়েছে")
                onSuccess()
            } else {
                onError(result.exceptionOrNull()?.message ?: "কাস্টমার অপসারণ ব্যর্থ হয়েছে")
            }
        }
    }

    fun softDeleteMultipleCustomers(
        customers: List<Customer>,
        reason: String,
        onSuccess: (Int, Double) -> Unit,
        onError: (String) -> Unit
    ) {
        val admin = _currentUser.value ?: return onError("অনুগ্রহ করে লগইন করুন!")
        if (admin.role != UserRole.ADMIN) {
            return onError("শুধুমাত্র এডমিন কাস্টমার মুছতে পারেন!")
        }
        viewModelScope.launch {
            val totalDue = customers.sumOf { it.currentDue }
            val result = repository.softDeleteMultipleCustomers(customers, admin, reason)
            result.fold(
                onSuccess = { count ->
                    scanTestDueCustomers()
                    _toastMessage.emit("$count জন কাস্টমার রিসাইকেল বিনে স্থানান্তর করা হয়েছে")
                    onSuccess(count, totalDue)
                },
                onFailure = { err ->
                    onError(err.message ?: "কাস্টমার অপসারণ ব্যর্থ হয়েছে")
                }
            )
        }
    }

    fun scanTestDueCustomers() {
        viewModelScope.launch {
            _isDetectingTestDues.value = true
            try {
                _detectedTestDueCustomers.value = repository.detectTestDueCustomers()
            } catch (e: Exception) {
                _detectedTestDueCustomers.value = emptyList()
            } finally {
                _isDetectingTestDues.value = false
            }
        }
    }


    fun softDeleteSale(
        sale: Sale,
        reason: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val admin = _currentUser.value ?: return onError("অনুগ্রহ করে লগইন করুন!")
        viewModelScope.launch {
            val result = repository.softDeleteSale(sale, admin, reason)
            if (result.isSuccess) {
                _toastMessage.emit("ইনভয়েস #${sale.invoiceNumber} রিসাইকেল বিনে স্থানান্তর ও স্টক/ব্যালেন্স সমন্বয় সম্পন্ন")
                onSuccess()
            } else {
                onError(result.exceptionOrNull()?.message ?: "বিক্রি অপসারণ ব্যর্থ হয়েছে")
            }
        }
    }

    fun softDeletePaymentReceipt(
        receipt: PaymentReceipt,
        reason: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val admin = _currentUser.value ?: return onError("অনুগ্রহ করে লগইন করুন!")
        viewModelScope.launch {
            val result = repository.softDeletePaymentReceipt(receipt, admin, reason)
            if (result.isSuccess) {
                _toastMessage.emit("রসিদ #${receipt.receiptNumber} রিসাইকেল বিনে স্থানান্তর ও কাস্টমার ব্যালেন্স সমন্বয় সম্পন্ন")
                onSuccess()
            } else {
                onError(result.exceptionOrNull()?.message ?: "পেমেন্ট অপসারণ ব্যর্থ হয়েছে")
            }
        }
    }

    fun softDeleteExpense(
        expense: Expense,
        reason: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val admin = _currentUser.value ?: return onError("অনুগ্রহ করে লগইন করুন!")
        viewModelScope.launch {
            val result = repository.softDeleteExpense(expense, admin, reason)
            if (result.isSuccess) {
                _toastMessage.emit("খরচ '${expense.description}' রিসাইকেল বিনে স্থানান্তর করা হয়েছে")
                onSuccess()
            } else {
                onError(result.exceptionOrNull()?.message ?: "খরচ অপসারণ ব্যর্থ হয়েছে")
            }
        }
    }

    fun restoreRecycleBinItem(
        item: RecycleBinItem,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val admin = _currentUser.value ?: return onError("অনুগ্রহ করে লগইন করুন!")
        if (admin.role != UserRole.ADMIN) {
            return onError("শুধুমাত্র এডমিন রেকর্ড পুনরুদ্ধার করতে পারেন।")
        }
        viewModelScope.launch {
            val res = repository.restoreRecycleBinItem(item, admin)
            if (res.isSuccess) {
                scanTestDueCustomers()
                _toastMessage.emit("'${item.itemName}' সফলভাবে পুনরুদ্ধার করা হয়েছে! ✅")
                onSuccess()
            } else {
                onError(res.exceptionOrNull()?.message ?: "পুনরুদ্ধার ব্যর্থ হয়েছে")
            }
        }
    }

    fun permanentlyDeleteRecycleBinItem(
        item: RecycleBinItem,
        pin: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val admin = _currentUser.value ?: return onError("অনুগ্রহ করে লগইন করুন!")
        if (admin.role != UserRole.ADMIN) {
            return onError("শুধুমাত্র এডমিন স্থায়ীভাবে ডিলিট করতে পারেন।")
        }
        viewModelScope.launch {
            val res = repository.permanentlyDeleteRecycleBinItem(item, admin, pin)
            if (res.isSuccess) {
                scanTestDueCustomers()
                _toastMessage.emit("'${item.itemName}' স্থায়ীভাবে মুছে ফেলা হয়েছে")
                onSuccess()
            } else {
                onError(res.exceptionOrNull()?.message ?: "মুছে ফেলা ব্যর্থ হয়েছে")
            }
        }
    }

    fun toggleProductTestDataStatus(productId: String, isTest: Boolean, tag: String = "MANUAL_TEST") {
        val admin = _currentUser.value ?: return
        viewModelScope.launch {
            repository.setProductTestDataStatus(productId, isTest, tag, admin)
        }
    }

    fun toggleCompanyTestDataStatus(companyId: String, isTest: Boolean, tag: String = "DEMO") {
        val admin = _currentUser.value ?: return
        viewModelScope.launch {
            repository.setCompanyTestDataStatus(companyId, isTest, tag, admin)
        }
    }

    fun toggleCustomerTestDataStatus(customerId: String, isTest: Boolean, tag: String = "MANUAL_TEST") {
        val admin = _currentUser.value ?: return
        viewModelScope.launch {
            repository.setCustomerTestDataStatus(customerId, isTest, tag, admin)
        }
    }

    fun toggleSaleTestDataStatus(invoiceNumber: String, isTest: Boolean, tag: String = "MANUAL_TEST") {
        val admin = _currentUser.value ?: return
        viewModelScope.launch {
            repository.setSaleTestDataStatus(invoiceNumber, isTest, tag, admin)
        }
    }

    fun toggleReceiptTestDataStatus(receiptNumber: String, isTest: Boolean, tag: String = "MANUAL_TEST") {
        val admin = _currentUser.value ?: return
        viewModelScope.launch {
            repository.setReceiptTestDataStatus(receiptNumber, isTest, tag, admin)
        }
    }

    fun toggleExpenseTestDataStatus(expenseId: String, isTest: Boolean, tag: String = "MANUAL_TEST") {
        val admin = _currentUser.value ?: return
        viewModelScope.launch {
            repository.setExpenseTestDataStatus(expenseId, isTest, tag, admin)
        }
    }

    fun setCustomerOpeningBalance(
        customerId: String,
        openingDue: Double,
        pin: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val admin = _currentUser.value ?: return onError("অনুগ্রহ করে লগইন করুন!")
        viewModelScope.launch {
            val res = repository.setCustomerOpeningBalance(customerId, openingDue, admin, pin)
            if (res.isSuccess) {
                _toastMessage.emit("কাস্টমার প্রারম্ভিক বকেয়া সফলভাবে সংরক্ষিত হয়েছে! ✅")
                onSuccess()
            } else {
                onError(res.exceptionOrNull()?.message ?: "প্রারম্ভিক বকেয়া সংরক্ষণ ব্যর্থ হয়েছে")
            }
        }
    }

    fun setProductOpeningStock(
        productId: String,
        openingStock: Double,
        pin: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val admin = _currentUser.value ?: return onError("অনুগ্রহ করে লগইন করুন!")
        viewModelScope.launch {
            val res = repository.setProductOpeningStock(productId, openingStock, admin, pin)
            if (res.isSuccess) {
                _toastMessage.emit("পণ্য প্রারম্ভিক স্টক সফলভাবে সংরক্ষিত হয়েছে! ✅")
                onSuccess()
            } else {
                onError(res.exceptionOrNull()?.message ?: "প্রারম্ভিক স্টক সংরক্ষণ ব্যর্থ হয়েছে")
            }
        }
    }

    fun createSampleTestData(
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val admin = _currentUser.value ?: return onError("অনুগ্রহ করে লগইন করুন!")
        viewModelScope.launch {
            val res = repository.createSampleTestData(admin)
            if (res.isSuccess) {
                _toastMessage.emit("পরীক্ষামূলক টেস্ট ডেটা সফলভাবে তৈরি হয়েছে!")
                onSuccess(res.getOrNull() ?: "")
            } else {
                onError(res.exceptionOrNull()?.message ?: "টেস্ট ডেটা তৈরি ব্যর্থ হয়েছে")
            }
        }
    }

    // --- Smart Reminder Actions ---
    fun refreshSmartReminders() {
        val user = _currentUser.value ?: return
        viewModelScope.launch {
            reminderManager.scanAndSyncReminders(user)
        }
    }

    fun markReminderCompleted(reminderId: String) {
        viewModelScope.launch {
            repository.updateReminderStatus(reminderId, ReminderStatus.COMPLETED, System.currentTimeMillis())
            _toastMessage.emit("কাজটি সম্পন্ন হিসেবে চিহ্নিত করা হয়েছে")
        }
    }

    fun markReminderDismissed(reminderId: String) {
        viewModelScope.launch {
            repository.updateReminderStatus(reminderId, ReminderStatus.DISMISSED, System.currentTimeMillis())
            _toastMessage.emit("রিমাইন্ডারটি স্থগিত করা হয়েছে")
        }
    }

    fun deleteReminder(reminderId: String) {
        viewModelScope.launch {
            repository.deleteReminder(reminderId)
            _toastMessage.emit("রিমাইন্ডার মুছে ফেলা হয়েছে")
        }
    }

    fun addCustomReminder(
        title: String,
        description: String,
        category: ReminderCategory,
        priority: ReminderPriority,
        scheduledTime: Long
    ) {
        val user = _currentUser.value ?: return
        viewModelScope.launch {
            val item = ReminderItem(
                id = UUID.randomUUID().toString(),
                deduplicationKey = "CUSTOM_TASK_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}",
                roleTarget = user.role,
                targetUserId = if (user.role == UserRole.SALESMAN) user.id else null,
                title = title.trim(),
                description = description.trim(),
                category = category,
                priority = priority,
                status = ReminderStatus.PENDING,
                targetRoute = null,
                targetEntityId = null,
                scheduledTime = scheduledTime,
                createdAt = System.currentTimeMillis(),
                isSystemGenerated = false
            )
            repository.insertReminder(item)
            _toastMessage.emit("নতুন কাজ যুক্ত করা হয়েছে")
        }
    }

    fun updateReminderPreference(preference: ReminderPreference) {
        viewModelScope.launch {
            repository.saveReminderPreference(preference)
            _toastMessage.emit("রিমাইন্ডার সেটিংস সংরক্ষিত হয়েছে")
        }
    }

    fun playVoiceTest(sampleText: String = "স্মার্ট বিজনেস ম্যানেজারে আপনাকে স্বাগতম। এটি ভয়েস রিমাইন্ডার টেস্ট।") {
        voiceHelper.speak(
            text = sampleText,
            isVoiceEnabled = true,
            quietHoursEnabled = false
        )
    }

    fun stopVoice() {
        voiceHelper.stop()
    }

    // --- Admin to Salesman Alert Actions ---
    fun sendAlert(
        targetType: AlertTargetType,
        targetSalesman: User?,
        title: String,
        message: String,
        priority: AlertPriority,
        isVoiceEnabled: Boolean,
        isNotificationEnabled: Boolean,
        scheduledTime: Long?,
        onResult: (Boolean, String) -> Unit
    ) {
        val admin = _currentUser.value
        if (admin == null || admin.role != UserRole.ADMIN) {
            onResult(false, "শুধুমাত্র এডমিন নির্দেশ পাঠাতে পারেন।")
            return
        }

        viewModelScope.launch {
            val result = alertRepository.createAlert(
                admin = admin,
                targetType = targetType,
                targetSalesman = targetSalesman,
                title = title,
                message = message,
                priority = priority,
                isVoiceEnabled = isVoiceEnabled,
                isNotificationEnabled = isNotificationEnabled,
                scheduledTime = scheduledTime
            )
            if (result.isSuccess) {
                val targetText = if (targetType == AlertTargetType.ALL) "সকল সেলসম্যানকে" else "${targetSalesman?.name}-কে"
                val schedText = if (scheduledTime != null && scheduledTime > System.currentTimeMillis()) "শিডিউল" else "সফলভাবে"
                _toastMessage.emit("$targetText নির্দেশটি $schedText পাঠানো হয়েছে")
                onResult(true, "নির্দেশ সফলভাবে পাঠানো হয়েছে!")
            } else {
                onResult(false, result.exceptionOrNull()?.message ?: "নির্দেশ পাঠানো ব্যর্থ হয়েছে")
            }
        }
    }

    fun acknowledgeAlert(receiptId: String, onResult: (Boolean, String) -> Unit) {
        val salesman = _currentUser.value
        if (salesman == null) {
            onResult(false, "অনুগ্রহ করে লগইন করুন!")
            return
        }

        viewModelScope.launch {
            val res = alertRepository.acknowledgeAlert(receiptId, salesman.id)
            if (res.isSuccess) {
                _toastMessage.emit("নির্দেশটি বুঝেছি হিসেবে নিশ্চিত করা হয়েছে")
                onResult(true, "নিশ্চিত করা হয়েছে")
            } else {
                onResult(false, res.exceptionOrNull()?.message ?: "ব্যর্থ হয়েছে")
            }
        }
    }

    fun completeAlert(receiptId: String, onResult: (Boolean, String) -> Unit) {
        val salesman = _currentUser.value
        if (salesman == null) {
            onResult(false, "অনুগ্রহ করে লগইন করুন!")
            return
        }

        viewModelScope.launch {
            val res = alertRepository.completeAlert(receiptId, salesman.id)
            if (res.isSuccess) {
                _toastMessage.emit("কাজ সম্পন্ন হিসেবে চিহ্নিত করা হয়েছে")
                onResult(true, "কাজ সম্পন্ন হয়েছে")
            } else {
                onResult(false, res.exceptionOrNull()?.message ?: "ব্যর্থ হয়েছে")
            }
        }
    }

    fun cancelAlert(alertId: String, onResult: (Boolean, String) -> Unit) {
        val admin = _currentUser.value
        if (admin == null || admin.role != UserRole.ADMIN) {
            onResult(false, "শুধুমাত্র এডমিন নির্দেশ বাতিল করতে পারেন।")
            return
        }

        viewModelScope.launch {
            val res = alertRepository.cancelAlert(alertId, admin)
            if (res.isSuccess) {
                _toastMessage.emit("শিডিউল করা নির্দেশ বাতিল করা হয়েছে")
                onResult(true, "বাতিল সম্পন্ন হয়েছে")
            } else {
                onResult(false, res.exceptionOrNull()?.message ?: "বাতিল করা ব্যর্থ হয়েছে")
            }
        }
    }

    fun speakAlertText(alert: AdminAlert) {
        val priorityPrefix = if (alert.priority == AlertPriority.URGENT) "অ্যাডমিনের জরুরি নির্দেশ।" else "অ্যাডমিনের নির্দেশ।"
        voiceHelper.speak(
            text = "$priorityPrefix ${alert.title}। ${alert.message}",
            isVoiceEnabled = true,
            quietHoursEnabled = false
        )
    }

    // --- Stock Verification Actions ---

    fun submitStockVerification(
        productId: String,
        physicalStock: Double,
        notes: String,
        imageUri: String?,
        onResult: (Boolean, String) -> Unit
    ) {
        val user = _currentUser.value
        if (user == null) {
            onResult(false, "অনুগ্রহ করে লগইন করুন!")
            return
        }

        viewModelScope.launch {
            try {
                val verification = stockVerificationRepository.submitStockVerification(
                    productId = productId,
                    physicalStock = physicalStock,
                    notes = notes,
                    imageUri = imageUri,
                    actorUser = user
                )

                if (verification.status == VerificationStatus.MATCHED) {
                    _toastMessage.emit("স্টক নিখুঁত মিলেছে! সফলভাবে সংরক্ষিত।")
                    onResult(true, "স্টক মিলেছে! কোনো অমিল নেই।")
                } else {
                    val diff = verification.difference
                    val diffText = if (diff < 0) "ঘাটতি: ${-diff}" else "অতিরিক্ত: +$diff"
                    _toastMessage.emit("অমিল পাওয়া গেছে ($diffText)। এডমিন অনুমোদনের জন্য সংরক্ষিত।")
                    onResult(true, "অমিল পাওয়া গেছে ($diffText)। এডমিন অনুমোদনের অপেক্ষমাণ।")
                }
            } catch (e: Exception) {
                onResult(false, e.message ?: "যাচাই সংরক্ষণ ব্যর্থ হয়েছে!")
            }
        }
    }

    fun reviewStockMismatch(
        verificationId: String,
        action: AdminMismatchAction,
        adminNote: String,
        onResult: (Boolean, String) -> Unit
    ) {
        val admin = _currentUser.value
        if (admin == null || admin.role != UserRole.ADMIN) {
            onResult(false, "শুধুমাত্র এডমিন অনুমোদন বা সমাধান করতে পারেন!")
            return
        }

        viewModelScope.launch {
            try {
                stockVerificationRepository.reviewStockMismatch(
                    verificationId = verificationId,
                    action = action,
                    adminNote = adminNote,
                    actorAdmin = admin
                )

                val msg = when (action) {
                    AdminMismatchAction.APPROVE_AND_ADJUST -> "স্টক যাচাই অনুমোদিত এবং মূল স্টক সমন্বিত হয়েছে।"
                    AdminMismatchAction.REJECT -> "স্টক যাচাই প্রত্যাখ্যান করা হয়েছে। মূল স্টক অপরিবর্তিত।"
                    AdminMismatchAction.REQUEST_RECOUNT -> "সেলসম্যানের কাছে পুনরায় গণনার নির্দেশ পাঠানো হয়েছে।"
                }
                _toastMessage.emit(msg)
                onResult(true, msg)
            } catch (e: Exception) {
                onResult(false, e.message ?: "পর্যালোচনা ব্যর্থ হয়েছে!")
            }
        }
    }

    fun configureProductSchedule(
        productId: String,
        intervalDays: Int,
        assignedSalesmanId: String?,
        assignedSalesmanName: String?,
        isReminderEnabled: Boolean,
        onResult: (Boolean, String) -> Unit
    ) {
        val admin = _currentUser.value
        if (admin == null || admin.role != UserRole.ADMIN) {
            onResult(false, "শুধুমাত্র এডমিন শিডিউল পরিবর্তন করতে পারেন!")
            return
        }

        viewModelScope.launch {
            try {
                stockVerificationRepository.configureProductSchedule(
                    productId = productId,
                    intervalDays = intervalDays,
                    assignedSalesmanId = assignedSalesmanId,
                    assignedSalesmanName = assignedSalesmanName,
                    isReminderEnabled = isReminderEnabled,
                    actorAdmin = admin
                )
                _toastMessage.emit("যাচাইয়ের সময়সীমা সফলভাবে হালনাগাদ করা হয়েছে।")
                onResult(true, "সময়সীমা হালনাগাদ হয়েছে।")
            } catch (e: Exception) {
                onResult(false, e.message ?: "সময়সীমা নির্ধারণ ব্যর্থ হয়েছে!")
            }
        }
    }

    fun sendStockVerificationAlert(
        targetSalesmanId: String?,
        targetSalesmanName: String?,
        title: String,
        message: String,
        priority: AlertPriority,
        isVoiceEnabled: Boolean,
        onResult: (Boolean, String) -> Unit
    ) {
        val admin = _currentUser.value
        if (admin == null || admin.role != UserRole.ADMIN) {
            onResult(false, "শুধুমাত্র এডমিন অ্যালার্ট পাঠাতে পারেন!")
            return
        }

        viewModelScope.launch {
            try {
                val salesmen = users.value.filter { it.role == UserRole.SALESMAN }
                stockVerificationRepository.sendVerificationInstructionAlert(
                    targetSalesmanId = targetSalesmanId,
                    targetSalesmanName = targetSalesmanName,
                    title = title,
                    message = message,
                    priority = priority,
                    isVoiceEnabled = isVoiceEnabled,
                    actorAdmin = admin,
                    salesmenList = salesmen
                )
                _toastMessage.emit("স্টক যাচাই নির্দেশ পাঠানো হয়েছে")
                onResult(true, "নির্দেশ সফলভাবে প্রেরিত")
            } catch (e: Exception) {
                onResult(false, e.message ?: "নির্দেশ পাঠানো ব্যর্থ হয়েছে!")
            }
        }
    }

    fun speakVerificationText(text: String) {
        voiceHelper.speak(
            text = text,
            isVoiceEnabled = true,
            quietHoursEnabled = false
        )
    }

    override fun onCleared() {
        super.onCleared()
        voiceHelper.shutdown()
    }
}
