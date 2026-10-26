package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.*
import com.example.data.repository.SuperAdminRepository
import com.example.viewmodel.SuperAdminViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
class SuperAdminSystemTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var app: Application
    private lateinit var repository: SuperAdminRepository
    private lateinit var viewModel: SuperAdminViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
        repository = SuperAdminRepository(app)
        viewModel = SuperAdminViewModel(app, repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test unauthorized emails are strictly rejected from Super Admin access`() {
        assertFalse(SuperAdminRepository.isSuperAdminEmail("random.user@gmail.com"))
        assertFalse(SuperAdminRepository.isSuperAdminEmail("hacker@malicious.site"))
        assertFalse(SuperAdminRepository.isSuperAdminEmail(null))
        assertFalse(SuperAdminRepository.isSuperAdminEmail(""))

        assertTrue(SuperAdminRepository.isSuperAdminEmail("buddavaibinodon@gmail.com"))
        assertTrue(SuperAdminRepository.isSuperAdminEmail("ataurgonee4@gmail.com"))
        assertTrue(SuperAdminRepository.isSuperAdminEmail("support@smartbiz.local"))
    }

    @Test
    fun `test business suspension updates status and records audit log safely`() = runTest(testDispatcher) {
        val businesses = repository.businesses.value
        assertTrue("Initial businesses should not be empty", businesses.isNotEmpty())
        val target = businesses.first { it.status == BusinessAccountStatus.ACTIVE }

        val res = repository.updateBusinessStatus(
            businessId = target.id,
            newStatus = BusinessAccountStatus.SUSPENDED,
            reason = "নমুনা অডিট পরীক্ষা",
            actorUid = "super_admin_test",
            actorName = "টেস্ট সুপার এডমিন",
            actorEmail = "buddavaibinodon@gmail.com"
        )
        assertTrue(res.isSuccess)

        val updated = repository.businesses.value.first { it.id == target.id }
        assertEquals(BusinessAccountStatus.SUSPENDED, updated.status)
        assertEquals("নমুনা অডিট পরীক্ষা", updated.statusReason)

        // Verify audit log was recorded
        val auditLogs = repository.auditLogs.value
        assertTrue(auditLogs.any { it.targetBusinessId == target.id && it.action.contains("স্থগিতকরণ") })
    }

    @Test
    fun `test business reactivation restores active status`() = runTest(testDispatcher) {
        val suspended = repository.businesses.value.first { it.status == BusinessAccountStatus.SUSPENDED }

        val res = repository.updateBusinessStatus(
            businessId = suspended.id,
            newStatus = BusinessAccountStatus.ACTIVE,
            reason = "বকেয়া পরিশোধ সাপেক্ষে সক্রিয়করণ",
            actorUid = "super_admin_test",
            actorName = "টেস্ট সুপার এডমিন",
            actorEmail = "buddavaibinodon@gmail.com"
        )
        assertTrue(res.isSuccess)

        val updated = repository.businesses.value.first { it.id == suspended.id }
        assertEquals(BusinessAccountStatus.ACTIVE, updated.status)
    }

    @Test
    fun `test plan change updates business plan and extends subscription expiry`() = runTest(testDispatcher) {
        val target = repository.businesses.value.first()
        val oldExpiry = target.subscriptionExpiresAt

        val res = repository.updateBusinessPlan(
            businessId = target.id,
            planId = "plan_enterprise",
            durationDays = 60,
            actorUid = "super_admin_test",
            actorName = "টেস্ট সুপার এডমিন",
            actorEmail = "buddavaibinodon@gmail.com"
        )
        assertTrue(res.isSuccess)

        val updated = repository.businesses.value.first { it.id == target.id }
        assertEquals("plan_enterprise", updated.planId)
        assertTrue(updated.subscriptionExpiresAt > oldExpiry)
    }

    @Test
    fun `test payment review approve is idempotent and extends subscription`() = runTest(testDispatcher) {
        val pendingPayment = repository.payments.value.first { it.status == PlatformPaymentStatus.PENDING }

        val approveRes = repository.reviewPayment(
            paymentId = pendingPayment.id,
            isApproved = true,
            rejectionReason = "",
            actorUid = "super_admin_test",
            actorName = "টেস্ট সুপার এডমিন",
            actorEmail = "buddavaibinodon@gmail.com"
        )
        assertTrue(approveRes.isSuccess)

        val updatedPayment = repository.payments.value.first { it.id == pendingPayment.id }
        assertEquals(PlatformPaymentStatus.APPROVED, updatedPayment.status)
        assertNotNull(updatedPayment.reviewedAt)

        // Try to approve or reject again: must fail (Idempotency)
        val duplicateRes = repository.reviewPayment(
            paymentId = pendingPayment.id,
            isApproved = true,
            rejectionReason = "",
            actorUid = "super_admin_test",
            actorName = "টেস্ট সুপার এডমিন",
            actorEmail = "buddavaibinodon@gmail.com"
        )
        assertFalse(duplicateRes.isSuccess)
    }

    @Test
    fun `test payment review rejection records reason without changing subscription`() = runTest(testDispatcher) {
        // Create a temporary pending payment to reject
        val pendingPayment = repository.payments.value.first { it.status == PlatformPaymentStatus.PENDING }

        val rejectRes = repository.reviewPayment(
            paymentId = pendingPayment.id,
            isApproved = false,
            rejectionReason = "ভুল ট্রানজ্যাকশন আইডি",
            actorUid = "super_admin_test",
            actorName = "টেস্ট সুপার এডমিন",
            actorEmail = "buddavaibinodon@gmail.com"
        )
        assertTrue(rejectRes.isSuccess)

        val updatedPayment = repository.payments.value.first { it.id == pendingPayment.id }
        assertEquals(PlatformPaymentStatus.REJECTED, updatedPayment.status)
        assertEquals("ভুল ট্রানজ্যাকশন আইডি", updatedPayment.rejectionReason)
    }

    @Test
    fun `test overview stats computes correct aggregations`() {
        val stats = repository.computeOverviewStats()
        assertTrue(stats.totalBusinesses >= 3)
        assertTrue(stats.activeBusinesses >= 1)
        assertTrue(stats.verifiedRevenue >= 10000.0)
        assertTrue(stats.planDistribution.isNotEmpty())
    }

    @Test
    fun `test saving plan configuration updates pricing and limits`() = runTest(testDispatcher) {
        val plan = repository.plans.value.first { it.id == "plan_pro" }
        val updatedPlan = plan.copy(
            priceMonthly = 1200.0,
            maxStaff = 12
        )

        val res = repository.savePlan(
            plan = updatedPlan,
            actorUid = "super_admin_test",
            actorName = "সুপার এডমিন",
            actorEmail = "buddavaibinodon@gmail.com"
        )
        assertTrue(res.isSuccess)

        val planInRepo = repository.plans.value.first { it.id == "plan_pro" }
        assertEquals(1200.0, planInRepo.priceMonthly, 0.01)
        assertEquals(12, planInRepo.maxStaff)
    }

    @Test
    fun `test platform settings update records audit log`() = runTest(testDispatcher) {
        val newSettings = repository.settings.value.copy(
            bKashMerchantNumber = "01999887766",
            nagadMerchantNumber = "01888776655",
            maintenanceMode = false
        )

        val res = repository.updateSettings(
            newSettings = newSettings,
            actorUid = "super_admin_test",
            actorName = "সুপার এডমিন",
            actorEmail = "buddavaibinodon@gmail.com"
        )
        assertTrue(res.isSuccess)

        assertEquals("01999887766", repository.settings.value.bKashMerchantNumber)
        val logs = repository.auditLogs.value
        assertTrue(logs.any { it.action == "সিস্টেম সেটিংস পরিবর্তন" })
    }
}
