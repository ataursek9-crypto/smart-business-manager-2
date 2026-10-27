package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.model.*
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * SuperAdminRepository coordinates platform-level business management, subscription plans,
 * payment verifications, and audit logging with strict multi-tenant isolation.
 */
class SuperAdminRepository(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
) {
    companion object {
        private const val TAG = "SuperAdminRepo"

        val AUTHORIZED_SUPER_ADMIN_EMAILS = setOf(
            "buddavaibinodon@gmail.com",
            "ataursek9@gmail.com",
            "ataurgonee4@gmail.com",
            "support@smartbiz.local",
            "gonee8666@gmail.com",
            "ataulgonee786@gmail.com",
            "ataurpos@gmail.com",
            "ataur50305@gmail.com",
            "a48268993@gmail.com",
            "jamesfashion792@gmail.com"
        )

        fun isSuperAdminEmail(email: String?): Boolean {
            if (email.isNullOrBlank()) return false
            return AUTHORIZED_SUPER_ADMIN_EMAILS.contains(email.trim().lowercase())
        }

        fun getDefaultPlans(): List<SubscriptionPlan> {
            return listOf(
                SubscriptionPlan(
                    id = "plan_free",
                    code = "FREE",
                    name = "Free Perpetual",
                    nameBn = "সম্পূর্ণ ফ্রি ব্যবহার",
                    descriptionBn = "নতুন ব্যবসার জন্য আজীবন বিনামূল্যে সকল মৌলিক ফিচার।",
                    priceMonthly = 0.0,
                    priceYearly = 0.0,
                    maxStaff = 2,
                    maxProducts = 100,
                    features = listOf(
                        "বর্তমান পলিসি: সম্পূর্ণ ফ্রি ব্যবহার",
                        "দৈনিক বেচাকেনা ও রসিদ তৈরি",
                        "কাস্টমার বাকি হিসাব খাতা",
                        "মৌলিক লাভ-ক্ষতি প্রতিবেদন",
                        "১ জন অ্যাডমিন ও ১ জন সেলসম্যান",
                        "কোনো জোরপূর্বক মেয়াদোত্তীর্ণ বা অটো-পেমেন্ট নেই"
                    ),
                    isPopular = false,
                    isActive = true
                ),
                SubscriptionPlan(
                    id = "plan_basic",
                    code = "BASIC",
                    name = "Basic Shop",
                    nameBn = "বেসিক ব্যবসা",
                    descriptionBn = "ছোট ও মাঝারি দোকান পরিচালনার জন্য আদর্শ।",
                    priceMonthly = 500.0,
                    priceYearly = 5000.0,
                    maxStaff = 3,
                    maxProducts = 500,
                    features = listOf(
                        "বেসিক সকল ফিচার",
                        "রিসাইকেল বিন ও ডিলিট হিস্ট্রি",
                        "কাস্টমার এসএমএস নোটিফিকেশন",
                        "পিডিএফ রসিদ ও শেয়ারিং",
                        "সর্বোচ্চ ৩ জন কর্মী"
                    ),
                    isPopular = false,
                    isActive = true
                ),
                SubscriptionPlan(
                    id = "plan_pro",
                    code = "PRO",
                    name = "Pro Store",
                    nameBn = "প্রো স্টোর",
                    descriptionBn = "দ্রুত বর্ধনশীল শপ ও পাইকারি ব্যবসার জন্য সেরা পছন্দ।",
                    priceMonthly = 1000.0,
                    priceYearly = 10000.0,
                    maxStaff = 8,
                    maxProducts = 5000,
                    features = listOf(
                        "ক্লাউড ব্যাকআপ ও রিয়েলটাইম সিঙ্ক",
                        "কোম্পানি ক্রয় ও বাকি ব্যবস্থাপনা",
                        "স্মার্ট রিমাইন্ডার ও বকেয়া অ্যালার্ট",
                        "স্টক ভেরিফিকেশন ও অডিট লগ",
                        "সর্বোচ্চ ৮ জন কর্মী"
                    ),
                    isPopular = true,
                    isActive = true
                ),
                SubscriptionPlan(
                    id = "plan_enterprise",
                    code = "ENTERPRISE",
                    name = "Enterprise VIP",
                    nameBn = "এন্টারপ্রাইজ / আনলিমিটেড",
                    descriptionBn = "বৃহৎ শপিং মল ও চেইন শপের জন্য পূর্ণাঙ্গ সমাধান।",
                    priceMonthly = 2000.0,
                    priceYearly = 20000.0,
                    maxStaff = 100,
                    maxProducts = 100000,
                    features = listOf(
                        "সকল আনলিমিটেড ফিচার",
                        "AI বিজনেস অ্যাসিস্ট্যান্ট পূর্ণ অ্যাক্সেস",
                        "অগ্রাধিকারমূলক ভিআইপি সাপোর্ট",
                        "কাস্টমাইজড রিপোর্ট ও অটো সিঙ্ক"
                    ),
                    isPopular = false,
                    isActive = true
                )
            )
        }

        private val _sharedBusinesses = MutableStateFlow<List<PlatformBusiness>>(emptyList())
        private val _sharedPlans = MutableStateFlow<List<SubscriptionPlan>>(getDefaultPlans())
        private val _sharedPayments = MutableStateFlow<List<PlatformPayment>>(emptyList())
        private val _sharedAuditLogs = MutableStateFlow<List<PlatformAuditLog>>(emptyList())
        private val _sharedSettings = MutableStateFlow(PlatformSystemSettings())

        @Volatile
        private var INSTANCE: SuperAdminRepository? = null

        fun getInstance(context: Context): SuperAdminRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SuperAdminRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val _businesses get() = _sharedBusinesses
    val businesses: StateFlow<List<PlatformBusiness>> = _sharedBusinesses.asStateFlow()

    private val _plans get() = _sharedPlans
    val plans: StateFlow<List<SubscriptionPlan>> = _sharedPlans.asStateFlow()

    private val _payments get() = _sharedPayments
    val payments: StateFlow<List<PlatformPayment>> = _sharedPayments.asStateFlow()

    private val _auditLogs get() = _sharedAuditLogs
    val auditLogs: StateFlow<List<PlatformAuditLog>> = _sharedAuditLogs.asStateFlow()

    private val _settings get() = _sharedSettings
    val settings: StateFlow<PlatformSystemSettings> = _sharedSettings.asStateFlow()

    private fun serializeBusiness(b: PlatformBusiness): org.json.JSONObject {
        val obj = org.json.JSONObject()
        obj.put("id", b.id)
        obj.put("businessName", b.businessName)
        obj.put("ownerUid", b.ownerUid)
        obj.put("ownerName", b.ownerName)
        obj.put("ownerEmail", b.ownerEmail)
        obj.put("ownerPhone", b.ownerPhone)
        obj.put("status", b.status.name)
        obj.put("statusReason", b.statusReason)
        obj.put("planId", b.planId)
        obj.put("planName", b.planName)
        obj.put("subscriptionStatus", b.subscriptionStatus.name)
        obj.put("subscriptionExpiresAt", b.subscriptionExpiresAt)
        obj.put("staffCount", b.staffCount)
        obj.put("maxStaffAllowed", b.maxStaffAllowed)
        obj.put("createdAt", b.createdAt)
        obj.put("firstUseAt", b.firstUseAt)
        obj.put("lastActiveAt", b.lastActiveAt)
        obj.put("notes", b.notes)
        obj.put("businessType", b.businessType)
        obj.put("address", b.address)
        obj.put("district", b.district)
        obj.put("isAutoExpiryDisabled", b.isAutoExpiryDisabled)
        obj.put("activeDaysCount", b.activeDaysCount)
        obj.put("phoneVerified", b.phoneVerified)
        obj.put("proposedFee", b.proposedFee)
        if (b.proposedStartDate != null) obj.put("proposedStartDate", b.proposedStartDate)
        if (b.noticeReminderDate != null) obj.put("noticeReminderDate", b.noticeReminderDate)
        obj.put("subscriptionNoticeNote", b.subscriptionNoticeNote)
        obj.put("hasActiveNotice", b.hasActiveNotice)
        obj.put("scheduledPolicyChange", b.scheduledPolicyChange)
        if (b.scheduledPolicyEffectiveAt != null) obj.put("scheduledPolicyEffectiveAt", b.scheduledPolicyEffectiveAt)
        return obj
    }

    private fun deserializeBusiness(obj: org.json.JSONObject): PlatformBusiness {
        return PlatformBusiness(
            id = obj.optString("id", ""),
            businessName = obj.optString("businessName", ""),
            ownerUid = obj.optString("ownerUid", ""),
            ownerName = obj.optString("ownerName", ""),
            ownerEmail = obj.optString("ownerEmail", ""),
            ownerPhone = obj.optString("ownerPhone", ""),
            status = try { BusinessAccountStatus.valueOf(obj.optString("status", BusinessAccountStatus.ACTIVE.name)) } catch (_: Exception) { BusinessAccountStatus.ACTIVE },
            statusReason = obj.optString("statusReason", ""),
            planId = obj.optString("planId", "plan_free"),
            planName = obj.optString("planName", "সম্পূর্ণ ফ্রি ব্যবহার"),
            subscriptionStatus = try { SubscriptionStatus.valueOf(obj.optString("subscriptionStatus", SubscriptionStatus.ACTIVE.name)) } catch (_: Exception) { SubscriptionStatus.ACTIVE },
            subscriptionExpiresAt = obj.optLong("subscriptionExpiresAt", 0L),
            staffCount = obj.optInt("staffCount", 1),
            maxStaffAllowed = obj.optInt("maxStaffAllowed", 2),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            firstUseAt = obj.optLong("firstUseAt", obj.optLong("createdAt", System.currentTimeMillis())),
            lastActiveAt = obj.optLong("lastActiveAt", System.currentTimeMillis()),
            notes = obj.optString("notes", ""),
            businessType = obj.optString("businessType", "সাধারণ ব্যবসা"),
            address = obj.optString("address", ""),
            district = obj.optString("district", ""),
            isAutoExpiryDisabled = obj.optBoolean("isAutoExpiryDisabled", true),
            activeDaysCount = obj.optInt("activeDaysCount", 1),
            phoneVerified = obj.optBoolean("phoneVerified", false),
            proposedFee = obj.optDouble("proposedFee", 0.0),
            proposedStartDate = if (obj.has("proposedStartDate") && !obj.isNull("proposedStartDate")) obj.optLong("proposedStartDate") else null,
            noticeReminderDate = if (obj.has("noticeReminderDate") && !obj.isNull("noticeReminderDate")) obj.optLong("noticeReminderDate") else null,
            subscriptionNoticeNote = obj.optString("subscriptionNoticeNote", ""),
            hasActiveNotice = obj.optBoolean("hasActiveNotice", false),
            scheduledPolicyChange = obj.optString("scheduledPolicyChange", ""),
            scheduledPolicyEffectiveAt = if (obj.has("scheduledPolicyEffectiveAt") && !obj.isNull("scheduledPolicyEffectiveAt")) obj.optLong("scheduledPolicyEffectiveAt") else null
        )
    }

    private fun saveBusinessesLocally(list: List<PlatformBusiness>) {
        try {
            val sp = context.getSharedPreferences("super_admin_businesses_store", Context.MODE_PRIVATE)
            val arr = org.json.JSONArray()
            list.forEach { b -> arr.put(serializeBusiness(b)) }
            sp.edit().putString("businesses_json", arr.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "saveBusinessesLocally error: ${e.message}")
        }
    }

    private fun loadBusinessesLocally(): List<PlatformBusiness> {
        return try {
            val sp = context.getSharedPreferences("super_admin_businesses_store", Context.MODE_PRIVATE)
            val jsonStr = sp.getString("businesses_json", null) ?: return emptyList()
            val arr = org.json.JSONArray(jsonStr)
            val result = mutableListOf<PlatformBusiness>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(deserializeBusiness(obj))
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "loadBusinessesLocally error: ${e.message}")
            emptyList()
        }
    }

    init {
        // Initialize in-memory seed data for offline & initial preview
        seedInitialPlatformData()
        // If Firebase is available, listen to remote collections
        initRemoteListeners()
    }

    private fun isFirebaseConfigured(): Boolean {
        return try {
            FirebaseApp.getApps(context).isNotEmpty() && FirebaseAuth.getInstance().currentUser != null
        } catch (_: Exception) {
            false
        }
    }

    private fun seedInitialPlatformData() {
        if (_businesses.value.isNotEmpty()) return

        val persisted = loadBusinessesLocally()
        if (persisted.isNotEmpty()) {
            _businesses.value = persisted
            return
        }

        val seedBusinesses = listOf(
            PlatformBusiness(
                id = "biz_smart_store_1",
                businessName = "স্মার্ট জেনারেল স্টোর",
                ownerUid = "admin_1",
                ownerName = "মোঃ আতাউর রহমান",
                ownerEmail = "ataurgonee4@gmail.com",
                ownerPhone = "01700000000",
                status = BusinessAccountStatus.ACTIVE,
                planId = "plan_pro",
                planName = "প্রো স্টোর",
                subscriptionStatus = SubscriptionStatus.ACTIVE,
                subscriptionExpiresAt = System.currentTimeMillis() + (45L * 24 * 60 * 60 * 1000),
                staffCount = 3,
                maxStaffAllowed = 8,
                notes = "প্রাথমিক প্রধান ব্যবসা প্রতিষ্ঠান"
            ),
            PlatformBusiness(
                id = "biz_fashion_house_2",
                businessName = "আল-নূর ফ্যাশন হাউস",
                ownerUid = "owner_usr_2",
                ownerName = "মাহমুদুল হাসান",
                ownerEmail = "alnoor.fashion@gmail.com",
                ownerPhone = "01911223344",
                status = BusinessAccountStatus.ACTIVE,
                planId = "plan_basic",
                planName = "বেসিক ব্যবসা",
                subscriptionStatus = SubscriptionStatus.ACTIVE,
                subscriptionExpiresAt = System.currentTimeMillis() + (15L * 24 * 60 * 60 * 1000),
                staffCount = 2,
                maxStaffAllowed = 3,
                notes = "রেডিমেড পোশাকের পাইকারি দোকান"
            ),
            PlatformBusiness(
                id = "biz_dhaka_traders_3",
                businessName = "ঢাকা পাইকারি ট্রেডার্স",
                ownerUid = "owner_usr_3",
                ownerName = "শামীম রেজা",
                ownerEmail = "dhaka.traders99@gmail.com",
                ownerPhone = "01688776655",
                status = BusinessAccountStatus.SUSPENDED,
                statusReason = "সাবস্ক্রিপশন ফি বকেয়া ও নীতি লঙ্ঘনের অভিযোগ পর্যালোচনাধীন",
                planId = "plan_basic",
                planName = "বেসিক ব্যবসা",
                subscriptionStatus = SubscriptionStatus.EXPIRED,
                subscriptionExpiresAt = System.currentTimeMillis() - (5L * 24 * 60 * 60 * 1000),
                staffCount = 2,
                maxStaffAllowed = 3,
                notes = "সাময়িক স্থগিত অ্যাকাউন্ট"
            )
        )
        _businesses.value = seedBusinesses

        val seedPayments = listOf(
            PlatformPayment(
                id = "pay_bkash_881920",
                businessId = "biz_fashion_house_2",
                businessName = "আল-নূর ফ্যাশন হাউস",
                amount = 1000.0,
                currency = "BDT",
                paymentMethod = PlatformPaymentMethod.BKASH,
                transactionReference = "9K8A77L91B",
                senderNumber = "01911223344",
                status = PlatformPaymentStatus.PENDING,
                targetPlanId = "plan_pro",
                durationMonths = 1,
                submittedAt = System.currentTimeMillis() - (2L * 60 * 60 * 1000),
                notes = "প্রো প্যাকেজে আপগ্রেড করার জন্য bKash সেন্ড মানি"
            ),
            PlatformPayment(
                id = "pay_nagad_772110",
                businessId = "biz_smart_store_1",
                businessName = "স্মার্ট জেনারেল স্টোর",
                amount = 10000.0,
                currency = "BDT",
                paymentMethod = PlatformPaymentMethod.NAGAD,
                transactionReference = "NGD8823190A",
                senderNumber = "01700000000",
                status = PlatformPaymentStatus.APPROVED,
                targetPlanId = "plan_pro",
                durationMonths = 12,
                submittedAt = System.currentTimeMillis() - (5L * 24 * 60 * 60 * 1000),
                reviewedAt = System.currentTimeMillis() - (4L * 24 * 60 * 60 * 1000),
                reviewedByUid = "super_admin_system",
                reviewedByName = "সুপার এডমিন",
                notes = "বার্ষিক সাবস্ক্রিপশন পরিশোধিত"
            )
        )
        _payments.value = seedPayments

        val seedLogs = listOf(
            PlatformAuditLog(
                id = "log_init_1",
                actorUid = "super_admin_system",
                actorName = "সিস্টেম",
                actorEmail = "support@smartbiz.local",
                action = "সিস্টেম ইনিশিয়ালাইজেশন",
                targetBusinessId = "GLOBAL",
                targetBusinessName = "প্ল্যাটফর্ম",
                details = "সুপার এডমিন কন্ট্রোল ও সাবস্ক্রিপশন পলিসি সক্রিয় করা হয়েছে।"
            ),
            PlatformAuditLog(
                id = "log_pay_approve_2",
                actorUid = "super_admin_system",
                actorName = "সুপার এডমিন",
                actorEmail = "support@smartbiz.local",
                action = "পেমেন্ট অনুমোদন ও সাবস্ক্রিপশন নবায়ন",
                targetBusinessId = "biz_smart_store_1",
                targetBusinessName = "স্মার্ট জেনারেল স্টোর",
                details = "১০,০০০ টাকা নাগাদ পেমেন্ট (Trx: NGD8823190A) অনুমোদিত। মেয়াদ ১২ মাস বৃদ্ধি করা হয়েছে।"
            )
        )
        _auditLogs.value = seedLogs
    }

    @androidx.annotation.VisibleForTesting
    fun resetStateForTesting() {
        _sharedBusinesses.value = emptyList()
        _sharedPayments.value = emptyList()
        _sharedAuditLogs.value = emptyList()
        _sharedPlans.value = getDefaultPlans()
        try {
            context.getSharedPreferences("super_admin_businesses_store", Context.MODE_PRIVATE).edit().clear().commit()
        } catch (_: Exception) {}
        seedInitialPlatformData()
    }

    private fun initRemoteListeners() {
        if (!isFirebaseConfigured()) return
        scope.launch {
            try {
                val firestore = FirebaseFirestore.getInstance()

                // 1. Remote Businesses
                firestore.collection("businesses").addSnapshotListener { snapshot, err ->
                    if (err != null || snapshot == null) return@addSnapshotListener
                    val remoteList = snapshot.documents.mapNotNull { doc ->
                        try {
                            val id = doc.id
                            val name = doc.getString("name") ?: doc.getString("businessName") ?: "ব্যবসা #$id"
                            val ownerUid = doc.getString("ownerUid") ?: id
                            val ownerName = doc.getString("ownerName") ?: "অ্যাডমিন"
                            val ownerEmail = doc.getString("ownerEmail") ?: ""
                            val ownerPhone = doc.getString("ownerPhone") ?: doc.getString("phone") ?: ""
                            val statusStr = doc.getString("status") ?: "ACTIVE"
                            val status = try { BusinessAccountStatus.valueOf(statusStr) } catch (_: Exception) { BusinessAccountStatus.ACTIVE }
                            val planId = doc.getString("planId") ?: "plan_free"
                            val planName = doc.getString("planName") ?: "সম্পূর্ণ ফ্রি ব্যবহার"
                            val subStatusStr = doc.getString("subscriptionStatus") ?: "ACTIVE"
                            val subStatus = try { SubscriptionStatus.valueOf(subStatusStr) } catch (_: Exception) { SubscriptionStatus.ACTIVE }
                            val expiresAt = doc.getLong("subscriptionExpiresAt") ?: 0L
                            val staffCount = (doc.getLong("staffCount") ?: 1).toInt()
                            val maxStaff = (doc.getLong("maxStaffAllowed") ?: 5).toInt()
                            val createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                            val lastActiveAt = doc.getLong("lastActiveAt") ?: System.currentTimeMillis()
                            val businessType = doc.getString("businessType") ?: "সাধারণ ব্যবসা"
                            val address = doc.getString("address") ?: ""
                            val district = doc.getString("district") ?: ""
                            val isAutoExpiryDisabled = doc.getBoolean("isAutoExpiryDisabled") ?: true
                            val activeDaysCount = (doc.getLong("activeDaysCount") ?: 1).toInt()
                            val proposedFee = doc.getDouble("proposedFee") ?: 0.0
                            val proposedStartDate = doc.getLong("proposedStartDate")
                            val noticeReminderDate = doc.getLong("noticeReminderDate")
                            val subscriptionNoticeNote = doc.getString("subscriptionNoticeNote") ?: ""
                            val hasActiveNotice = doc.getBoolean("hasActiveNotice") ?: (proposedFee > 0.0)

                            PlatformBusiness(
                                id = id,
                                businessName = name,
                                ownerUid = ownerUid,
                                ownerName = ownerName,
                                ownerEmail = ownerEmail,
                                ownerPhone = ownerPhone,
                                status = status,
                                statusReason = doc.getString("statusReason") ?: "",
                                planId = planId,
                                planName = planName,
                                subscriptionStatus = subStatus,
                                subscriptionExpiresAt = expiresAt,
                                staffCount = staffCount,
                                maxStaffAllowed = maxStaff,
                                createdAt = createdAt,
                                lastActiveAt = lastActiveAt,
                                notes = doc.getString("notes") ?: "",
                                businessType = businessType,
                                address = address,
                                district = district,
                                isAutoExpiryDisabled = isAutoExpiryDisabled,
                                activeDaysCount = activeDaysCount,
                                proposedFee = proposedFee,
                                proposedStartDate = proposedStartDate,
                                noticeReminderDate = noticeReminderDate,
                                subscriptionNoticeNote = subscriptionNoticeNote,
                                hasActiveNotice = hasActiveNotice
                            )
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (remoteList.isNotEmpty()) {
                        _businesses.value = remoteList
                    }
                }

                // 2. Remote Audit Logs
                firestore.collection("platform_audit_logs")
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .limit(100)
                    .addSnapshotListener { snapshot, err ->
                        if (err != null || snapshot == null) return@addSnapshotListener
                        val list = snapshot.documents.mapNotNull { doc ->
                            try {
                                PlatformAuditLog(
                                    id = doc.id,
                                    actorUid = doc.getString("actorUid") ?: "",
                                    actorName = doc.getString("actorName") ?: "অ্যাডমিন",
                                    actorEmail = doc.getString("actorEmail") ?: "",
                                    action = doc.getString("action") ?: "",
                                    targetBusinessId = doc.getString("targetBusinessId") ?: "",
                                    targetBusinessName = doc.getString("targetBusinessName") ?: "",
                                    details = doc.getString("details") ?: "",
                                    timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()
                                )
                            } catch (_: Exception) {
                                null
                            }
                        }
                        if (list.isNotEmpty()) {
                            _auditLogs.value = list
                        }
                    }

                // 3. Remote Payments
                firestore.collection("platform_payments")
                    .orderBy("submittedAt", Query.Direction.DESCENDING)
                    .limit(100)
                    .addSnapshotListener { snapshot, err ->
                        if (err != null || snapshot == null) return@addSnapshotListener
                        val list = snapshot.documents.mapNotNull { doc ->
                            try {
                                val methodStr = doc.getString("paymentMethod") ?: "BKASH"
                                val method = try { PlatformPaymentMethod.valueOf(methodStr) } catch (_: Exception) { PlatformPaymentMethod.BKASH }
                                val statusStr = doc.getString("status") ?: "PENDING"
                                val status = try { PlatformPaymentStatus.valueOf(statusStr) } catch (_: Exception) { PlatformPaymentStatus.PENDING }

                                PlatformPayment(
                                    id = doc.id,
                                    businessId = doc.getString("businessId") ?: "",
                                    businessName = doc.getString("businessName") ?: "",
                                    amount = doc.getDouble("amount") ?: 0.0,
                                    currency = doc.getString("currency") ?: "BDT",
                                    paymentMethod = method,
                                    transactionReference = doc.getString("transactionReference") ?: "",
                                    senderNumber = doc.getString("senderNumber") ?: "",
                                    status = status,
                                    targetPlanId = doc.getString("targetPlanId") ?: "plan_pro",
                                    durationMonths = (doc.getLong("durationMonths") ?: 1).toInt(),
                                    submittedAt = doc.getLong("submittedAt") ?: System.currentTimeMillis(),
                                    reviewedAt = doc.getLong("reviewedAt"),
                                    reviewedByUid = doc.getString("reviewedByUid"),
                                    reviewedByName = doc.getString("reviewedByName"),
                                    rejectionReason = doc.getString("rejectionReason") ?: "",
                                    notes = doc.getString("notes") ?: ""
                                )
                            } catch (_: Exception) {
                                null
                            }
                        }
                        if (list.isNotEmpty()) {
                            _payments.value = list
                        }
                    }

            } catch (e: Exception) {
                Log.w(TAG, "initRemoteListeners note: ${e.message}")
            }
        }
    }

    /**
     * Updates business account status (ACTIVE, SUSPENDED, DEACTIVATED).
     * Strictly records an audit log. Never deletes business data upon suspension.
     */
    suspend fun updateBusinessStatus(
        businessId: String,
        newStatus: BusinessAccountStatus,
        reason: String,
        actorUid: String,
        actorName: String,
        actorEmail: String
    ): Result<Unit> {
        if (!isSuperAdminEmail(actorEmail)) {
            return Result.failure(SecurityException("অননুমোদিত অ্যাক্সেস! শুধুমাত্র প্ল্যাটফর্ম সুপার এডমিন এই অপারেশন সম্পাদন করতে পারেন।"))
        }
        val currentList = _businesses.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == businessId }
        if (index == -1) {
            return Result.failure(IllegalArgumentException("ব্যবসা প্রতিষ্ঠানটি খুঁজে পাওয়া যায়নি!"))
        }

        val target = currentList[index]
        val updated = target.copy(
            status = newStatus,
            statusReason = reason,
            lastActiveAt = System.currentTimeMillis()
        )
        currentList[index] = updated
        _businesses.value = currentList
        saveBusinessesLocally(currentList)

        // Record Audit Log
        val actionTitle = when (newStatus) {
            BusinessAccountStatus.ACTIVE -> "ব্যবসা প্রতিষ্ঠান পুনর্সক্রিয়করণ"
            BusinessAccountStatus.SUSPENDED -> "ব্যবসা প্রতিষ্ঠান সাময়িক স্থগিতকরণ"
            BusinessAccountStatus.DEACTIVATED -> "ব্যবসা প্রতিষ্ঠান নিষ্ক্রিয়করণ"
        }
        recordAuditLog(
            actorUid = actorUid,
            actorName = actorName,
            actorEmail = actorEmail,
            action = actionTitle,
            targetBusinessId = businessId,
            targetBusinessName = target.businessName,
            details = "স্ট্যাটাস পরিবর্তন: $newStatus। কারণ: ${reason.ifBlank { "সুপার এডমিন কর্তৃক সম্পাদিত" }}"
        )

        // Write to Firestore if configured
        if (isFirebaseConfigured()) {
            try {
                FirebaseFirestore.getInstance().collection("businesses").document(businessId)
                    .set(
                        mapOf(
                            "status" to newStatus.name,
                            "statusReason" to reason,
                            "lastActiveAt" to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    ).await()
            } catch (e: Exception) {
                Log.w(TAG, "updateBusinessStatus remote note: ${e.message}")
            }
        }
        return Result.success(Unit)
    }

    /**
     * Updates a business's subscription plan and extends expiry.
     */
    suspend fun updateBusinessPlan(
        businessId: String,
        planId: String,
        durationDays: Int,
        actorUid: String,
        actorName: String,
        actorEmail: String
    ): Result<Unit> {
        if (!isSuperAdminEmail(actorEmail)) {
            return Result.failure(SecurityException("অননুমোদিত অ্যাক্সেস! শুধুমাত্র প্ল্যাটফর্ম সুপার এডমিন এই অপারেশন সম্পাদন করতে পারেন।"))
        }
        val plan = _plans.value.find { it.id == planId }
            ?: return Result.failure(IllegalArgumentException("নির্বাচিত প্ল্যানটি পাওয়া যায়নি!"))

        val currentList = _businesses.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == businessId }
        if (index == -1) {
            return Result.failure(IllegalArgumentException("ব্যবসা প্রতিষ্ঠানটি খুঁজে পাওয়া যায়নি!"))
        }

        val target = currentList[index]
        val newExpiry = maxOf(System.currentTimeMillis(), target.subscriptionExpiresAt) + (durationDays.toLong() * 24 * 60 * 60 * 1000)

        val updated = target.copy(
            planId = plan.id,
            planName = plan.nameBn,
            subscriptionStatus = SubscriptionStatus.ACTIVE,
            subscriptionExpiresAt = newExpiry,
            maxStaffAllowed = plan.maxStaff,
            lastActiveAt = System.currentTimeMillis()
        )
        currentList[index] = updated
        _businesses.value = currentList
        saveBusinessesLocally(currentList)

        recordAuditLog(
            actorUid = actorUid,
            actorName = actorName,
            actorEmail = actorEmail,
            action = "প্ল্যান আপগ্রেড / পরিবর্তন",
            targetBusinessId = businessId,
            targetBusinessName = target.businessName,
            details = "নতুন প্ল্যান: ${plan.nameBn}। মেয়াদ বৃদ্ধি: $durationDays দিন।"
        )

        if (isFirebaseConfigured()) {
            try {
                FirebaseFirestore.getInstance().collection("businesses").document(businessId)
                    .set(
                        mapOf(
                            "planId" to plan.id,
                            "planName" to plan.nameBn,
                            "subscriptionStatus" to SubscriptionStatus.ACTIVE.name,
                            "subscriptionExpiresAt" to newExpiry,
                            "maxStaffAllowed" to plan.maxStaff,
                            "lastActiveAt" to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    ).await()
            } catch (e: Exception) {
                Log.w(TAG, "updateBusinessPlan remote note: ${e.message}")
            }
        }
        return Result.success(Unit)
    }

    /**
     * Reviews a manual bKash/Nagad subscription payment.
     * Approving immediately extends the subscription. Rejection records the reason.
     * Idempotent: already processed payments cannot be duplicated.
     */
    suspend fun reviewPayment(
        paymentId: String,
        isApproved: Boolean,
        rejectionReason: String,
        actorUid: String,
        actorName: String,
        actorEmail: String
    ): Result<Unit> {
        if (!isSuperAdminEmail(actorEmail)) {
            return Result.failure(SecurityException("অননুমোদিত অ্যাক্সেস! শুধুমাত্র প্ল্যাটফর্ম সুপার এডমিন এই অপারেশন সম্পাদন করতে পারেন।"))
        }
        val currentPayments = _payments.value.toMutableList()
        val index = currentPayments.indexOfFirst { it.id == paymentId }
        if (index == -1) {
            return Result.failure(IllegalArgumentException("পেমেন্ট রেকর্ডটি পাওয়া যায়নি!"))
        }

        val payment = currentPayments[index]
        if (payment.status != PlatformPaymentStatus.PENDING) {
            return Result.failure(IllegalStateException("এই পেমেন্টটি ইতিমধ্যে পর্যালোচনা করা হয়েছে (${payment.status})!"))
        }

        val newStatus = if (isApproved) PlatformPaymentStatus.APPROVED else PlatformPaymentStatus.REJECTED
        val reviewedPayment = payment.copy(
            status = newStatus,
            reviewedAt = System.currentTimeMillis(),
            reviewedByUid = actorUid,
            reviewedByName = actorName,
            rejectionReason = rejectionReason
        )
        currentPayments[index] = reviewedPayment
        _payments.value = currentPayments

        if (isApproved) {
            // Extend target business subscription safely
            val durationDays = payment.durationMonths * 30
            updateBusinessPlan(
                businessId = payment.businessId,
                planId = payment.targetPlanId,
                durationDays = durationDays,
                actorUid = actorUid,
                actorName = actorName,
                actorEmail = actorEmail
            )
            recordAuditLog(
                actorUid = actorUid,
                actorName = actorName,
                actorEmail = actorEmail,
                action = "পেমেন্ট অনুমোদন ও সাবস্ক্রিপশন নবায়ন",
                targetBusinessId = payment.businessId,
                targetBusinessName = payment.businessName,
                details = "পেমেন্ট #${payment.id} (${payment.amount} ${payment.currency}, Trx: ${payment.transactionReference}) সফলভাবে অনুমোদিত হয়েছে।"
            )
        } else {
            recordAuditLog(
                actorUid = actorUid,
                actorName = actorName,
                actorEmail = actorEmail,
                action = "পেমেন্ট বাতিলকরণ",
                targetBusinessId = payment.businessId,
                targetBusinessName = payment.businessName,
                details = "পেমেন্ট #${payment.id} বাতিল করা হয়েছে। কারণ: ${rejectionReason.ifBlank { "সুপার এডমিন কর্তৃক যাচাইকরণে ত্রুটি" }}"
            )
        }

        if (isFirebaseConfigured()) {
            try {
                FirebaseFirestore.getInstance().collection("platform_payments").document(paymentId)
                    .set(
                        mapOf(
                            "status" to newStatus.name,
                            "reviewedAt" to System.currentTimeMillis(),
                            "reviewedByUid" to actorUid,
                            "reviewedByName" to actorName,
                            "rejectionReason" to rejectionReason
                        ),
                        SetOptions.merge()
                    ).await()
            } catch (e: Exception) {
                Log.w(TAG, "reviewPayment remote note: ${e.message}")
            }
        }
        return Result.success(Unit)
    }

    /**
     * Saves or updates a subscription plan definition.
     */
    suspend fun savePlan(
        plan: SubscriptionPlan,
        actorUid: String,
        actorName: String,
        actorEmail: String
    ): Result<Unit> {
        if (!isSuperAdminEmail(actorEmail)) {
            return Result.failure(SecurityException("অননুমোদিত অ্যাক্সেস! শুধুমাত্র প্ল্যাটফর্ম সুপার এডমিন এই অপারেশন সম্পাদন করতে পারেন।"))
        }
        val currentPlans = _plans.value.toMutableList()
        val index = currentPlans.indexOfFirst { it.id == plan.id }
        if (index >= 0) {
            currentPlans[index] = plan
        } else {
            currentPlans.add(plan)
        }
        _plans.value = currentPlans

        recordAuditLog(
            actorUid = actorUid,
            actorName = actorName,
            actorEmail = actorEmail,
            action = "সাবস্ক্রিপশন প্ল্যান কনফিগারেশন",
            targetBusinessId = "GLOBAL",
            targetBusinessName = "প্ল্যাটফর্ম পলিসি",
            details = "প্ল্যান '${plan.nameBn}' আপডেট করা হয়েছে। মাসিক মূল্য: ৳${plan.priceMonthly}, কর্মী সীমা: ${plan.maxStaff}"
        )

        if (isFirebaseConfigured()) {
            try {
                FirebaseFirestore.getInstance().collection("platform_plans").document(plan.id)
                    .set(plan, SetOptions.merge()).await()
            } catch (e: Exception) {
                Log.w(TAG, "savePlan remote note: ${e.message}")
            }
        }
        return Result.success(Unit)
    }

    /**
     * Records a secure audit log for Super Admin operations.
     */
    suspend fun recordAuditLog(
        actorUid: String,
        actorName: String,
        actorEmail: String,
        action: String,
        targetBusinessId: String,
        targetBusinessName: String,
        details: String
    ) {
        val log = PlatformAuditLog(
            id = "log_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}",
            actorUid = actorUid,
            actorName = actorName,
            actorEmail = actorEmail,
            action = action,
            targetBusinessId = targetBusinessId,
            targetBusinessName = targetBusinessName,
            details = details,
            timestamp = System.currentTimeMillis()
        )
        val list = _auditLogs.value.toMutableList()
        list.add(0, log)
        _auditLogs.value = list

        if (isFirebaseConfigured()) {
            try {
                FirebaseFirestore.getInstance().collection("platform_audit_logs").document(log.id)
                    .set(log).await()
            } catch (e: Exception) {
                Log.w(TAG, "recordAuditLog remote note: ${e.message}")
            }
        }
    }

    /**
     * Updates platform system settings.
     */
    suspend fun updateSettings(
        newSettings: PlatformSystemSettings,
        actorUid: String,
        actorName: String,
        actorEmail: String
    ): Result<Unit> {
        if (!isSuperAdminEmail(actorEmail)) {
            return Result.failure(SecurityException("অননুমোদিত অ্যাক্সেস! শুধুমাত্র প্ল্যাটফর্ম সুপার এডমিন এই অপারেশন সম্পাদন করতে পারেন।"))
        }
        _settings.value = newSettings
        recordAuditLog(
            actorUid = actorUid,
            actorName = actorName,
            actorEmail = actorEmail,
            action = "সিস্টেম সেটিংস পরিবর্তন",
            targetBusinessId = "GLOBAL",
            targetBusinessName = "প্ল্যাটফর্ম",
            details = "বিকাশ মার্চেন্ট: ${newSettings.bKashMerchantNumber}, নগদ: ${newSettings.nagadMerchantNumber}, মেইনটেন্যান্স: ${newSettings.maintenanceMode}"
        )

        if (isFirebaseConfigured()) {
            try {
                FirebaseFirestore.getInstance().collection("platform_settings").document("global_config")
                    .set(newSettings, SetOptions.merge()).await()
            } catch (e: Exception) {
                Log.w(TAG, "updateSettings remote note: ${e.message}")
            }
        }
        return Result.success(Unit)
    }

    /**
     * Registers a new shop in the Super Admin repository and syncs to cloud if connected.
     * Guaranteed to appear in the Super Admin Console immediately.
     */
    suspend fun registerNewShop(business: PlatformBusiness): Result<PlatformBusiness> {
        val currentList = _businesses.value.toMutableList()
        val existingIndex = currentList.indexOfFirst {
            it.id == business.id || (it.ownerPhone == business.ownerPhone && business.ownerPhone.isNotBlank())
        }
        if (existingIndex >= 0) {
            currentList[existingIndex] = business
        } else {
            currentList.add(0, business)
        }
        _businesses.value = currentList
        saveBusinessesLocally(currentList)

        recordAuditLog(
            actorUid = business.ownerUid,
            actorName = business.ownerName,
            actorEmail = business.ownerEmail.ifBlank { "shop_owner" },
            action = "নতুন দোকান রেজিস্ট্রেশন",
            targetBusinessId = business.id,
            targetBusinessName = business.businessName,
            details = "দোকান: ${business.businessName}, মালিক: ${business.ownerName}, মোবাইল: ${business.ownerPhone}, ধরন: ${business.businessType}, ঠিকানা: ${business.address} (পলিসি: সম্পূর্ণ ফ্রি ব্যবহার)"
        )

        if (isFirebaseConfigured()) {
            try {
                val data = mapOf(
                    "id" to business.id,
                    "name" to business.businessName,
                    "businessName" to business.businessName,
                    "ownerUid" to business.ownerUid,
                    "ownerName" to business.ownerName,
                    "ownerPhone" to business.ownerPhone,
                    "phone" to business.ownerPhone,
                    "status" to business.status.name,
                    "planId" to business.planId,
                    "planName" to business.planName,
                    "subscriptionStatus" to business.subscriptionStatus.name,
                    "subscriptionExpiresAt" to business.subscriptionExpiresAt,
                    "staffCount" to business.staffCount,
                    "maxStaffAllowed" to business.maxStaffAllowed,
                    "createdAt" to business.createdAt,
                    "firstUseAt" to business.firstUseAt,
                    "lastActiveAt" to business.lastActiveAt,
                    "businessType" to business.businessType,
                    "address" to business.address,
                    "district" to business.district,
                    "isAutoExpiryDisabled" to business.isAutoExpiryDisabled,
                    "activeDaysCount" to business.activeDaysCount,
                    "phoneVerified" to business.phoneVerified,
                    "scheduledPolicyChange" to business.scheduledPolicyChange,
                    "scheduledPolicyEffectiveAt" to business.scheduledPolicyEffectiveAt,
                    "notes" to business.notes
                )
                FirebaseFirestore.getInstance().collection("businesses").document(business.id)
                    .set(data, SetOptions.merge()).await()
            } catch (e: Exception) {
                Log.w(TAG, "registerNewShop remote write note: ${e.message}")
            }
        }
        return Result.success(business)
    }

    /**
     * Sends or updates a subscription proposal / notice to a shop.
     * Strictly acts as an informative notice or proposal without cutting or interrupting free access.
     */
    suspend fun sendSubscriptionNotice(
        businessId: String,
        proposedFee: Double,
        proposedStartDate: Long?,
        reminderDate: Long?,
        note: String,
        actorUid: String,
        actorName: String,
        actorEmail: String
    ): Result<Unit> {
        if (!isSuperAdminEmail(actorEmail)) {
            return Result.failure(SecurityException("অননুমোদিত অ্যাক্সেস! শুধুমাত্র প্ল্যাটফর্ম সুপার এডমিন এই অপারেশন সম্পাদন করতে পারেন।"))
        }
        val currentList = _businesses.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == businessId }
        if (index == -1) {
            return Result.failure(IllegalArgumentException("ব্যবসা প্রতিষ্ঠানটি খুঁজে পাওয়া যায়নি!"))
        }

        val target = currentList[index]
        val updated = target.copy(
            proposedFee = proposedFee,
            proposedStartDate = proposedStartDate,
            noticeReminderDate = reminderDate,
            subscriptionNoticeNote = note,
            hasActiveNotice = true,
            lastActiveAt = System.currentTimeMillis()
        )
        currentList[index] = updated
        _businesses.value = currentList
        saveBusinessesLocally(currentList)

        val startDateStr = if (proposedStartDate != null) {
            java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("bn", "BD")).format(java.util.Date(proposedStartDate))
        } else "উন্মুক্ত"
        val reminderDateStr = if (reminderDate != null) {
            java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("bn", "BD")).format(java.util.Date(reminderDate))
        } else "নির্ধারিত নয়"

        recordAuditLog(
            actorUid = actorUid,
            actorName = actorName,
            actorEmail = actorEmail,
            action = "সাবস্ক্রিপশন ফি নোটিশ প্রেরণ",
            targetBusinessId = businessId,
            targetBusinessName = target.businessName,
            details = "প্রস্তাবিত ফি: ৳$proposedFee, কার্যকরের তারিখ: $startDateStr, রিমাইন্ডার: $reminderDateStr, নোট: $note (দোকানের ফ্রি অ্যাক্সেস পূর্ণ বহাল থাকবে)"
        )

        if (isFirebaseConfigured()) {
            try {
                FirebaseFirestore.getInstance().collection("businesses").document(businessId)
                    .set(
                        mapOf(
                            "proposedFee" to proposedFee,
                            "proposedStartDate" to proposedStartDate,
                            "noticeReminderDate" to reminderDate,
                            "subscriptionNoticeNote" to note,
                            "hasActiveNotice" to true,
                            "lastActiveAt" to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    ).await()
            } catch (e: Exception) {
                Log.w(TAG, "sendSubscriptionNotice remote note: ${e.message}")
            }
        }
        return Result.success(Unit)
    }

    /**
     * Schedules a future subscription policy change for a business.
     * Does not revoke or interrupt the store's current active free access.
     */
    suspend fun schedulePolicyChange(
        businessId: String,
        policyDescription: String,
        effectiveDate: Long,
        actorUid: String,
        actorName: String,
        actorEmail: String
    ): Result<Unit> {
        if (!isSuperAdminEmail(actorEmail)) {
            return Result.failure(SecurityException("অননুমোদিত অ্যাক্সেস! শুধুমাত্র প্ল্যাটফর্ম সুপার এডমিন এই অপারেশন সম্পাদন করতে পারেন।"))
        }
        val currentList = _businesses.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == businessId }
        if (index == -1) {
            return Result.failure(IllegalArgumentException("দোকানটি খুঁজে পাওয়া যায়নি!"))
        }

        val target = currentList[index]
        val updated = target.copy(
            scheduledPolicyChange = policyDescription,
            scheduledPolicyEffectiveAt = effectiveDate,
            lastActiveAt = System.currentTimeMillis()
        )
        currentList[index] = updated
        _businesses.value = currentList
        saveBusinessesLocally(currentList)

        val dateStr = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("bn", "BD")).format(java.util.Date(effectiveDate))
        recordAuditLog(
            actorUid = actorUid,
            actorName = actorName,
            actorEmail = actorEmail,
            action = "পলিসি পরিবর্তন শিডিউল",
            targetBusinessId = businessId,
            targetBusinessName = target.businessName,
            details = "দোকান: ${target.businessName}, শিডিউলকৃত পলিসি: '$policyDescription', কার্যকরের তারিখ: $dateStr"
        )

        if (isFirebaseConfigured()) {
            try {
                FirebaseFirestore.getInstance().collection("businesses").document(businessId)
                    .set(
                        mapOf(
                            "scheduledPolicyChange" to policyDescription,
                            "scheduledPolicyEffectiveAt" to effectiveDate,
                            "lastActiveAt" to updated.lastActiveAt
                        ),
                        SetOptions.merge()
                    ).await()
            } catch (e: Exception) {
                Log.w(TAG, "schedulePolicyChange remote write: ${e.message}")
            }
        }
        return Result.success(Unit)
    }

    /**
     * Generates aggregated platform overview metrics.
     */
    fun computeOverviewStats(): PlatformOverviewStats {
        val bList = _businesses.value
        val pList = _payments.value

        val totalB = bList.size
        val activeB = bList.count { it.status == BusinessAccountStatus.ACTIVE }
        val suspendedB = bList.count { it.status == BusinessAccountStatus.SUSPENDED }

        val now = System.currentTimeMillis()
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000

        val activeSubs = bList.count {
            it.subscriptionStatus == SubscriptionStatus.ACTIVE &&
            (it.isAutoExpiryDisabled || it.subscriptionExpiresAt > now)
        }
        val expiringSubs = bList.count { !it.isAutoExpiryDisabled && it.subscriptionExpiresAt in now..(now + sevenDaysMs) }
        val expiredSubs = bList.count { !it.isAutoExpiryDisabled && it.subscriptionExpiresAt < now }

        val pendingPay = pList.count { it.status == PlatformPaymentStatus.PENDING }
        val verifiedRev = pList.filter { it.status == PlatformPaymentStatus.APPROVED }.sumOf { it.amount }

        val planDist = bList.groupBy { it.planName }.mapValues { it.value.size }

        return PlatformOverviewStats(
            totalBusinesses = totalB,
            activeBusinesses = activeB,
            suspendedBusinesses = suspendedB,
            activeSubscriptions = activeSubs,
            expiringSubscriptions = expiringSubs,
            expiredSubscriptions = expiredSubs,
            pendingPaymentsCount = pendingPay,
            verifiedRevenue = verifiedRev,
            planDistribution = planDist,
            isCloudConnected = isFirebaseConfigured(),
            lastUpdated = now
        )
    }
}
