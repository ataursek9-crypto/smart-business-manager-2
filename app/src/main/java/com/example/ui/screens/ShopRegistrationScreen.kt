package com.example.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PlatformBusiness
import com.example.data.repository.SuperAdminRepository
import com.example.ui.theme.PrimaryNavy
import com.example.util.GoogleSignInHelper
import com.example.util.PhoneAuthHelper
import com.example.viewmodel.BusinessViewModel
import com.example.viewmodel.SuperAdminViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopRegistrationScreen(
    viewModel: BusinessViewModel,
    superAdminViewModel: SuperAdminViewModel? = null,
    onBack: () -> Unit,
    onRegistrationSuccess: () -> Unit
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val activity = context as? Activity

    // Registration Mode Tab: 0 = Mobile Phone (SMS OTP), 1 = Google Sign-In
    var selectedMethodTab by remember { mutableStateOf(0) }

    // Form Fields
    var shopName by remember { mutableStateOf("") }
    var ownerName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var district by remember { mutableStateOf("") }
    var selectedBusinessType by remember { mutableStateOf("মুদি ও জেনারেল স্টোর") }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var showPin by remember { mutableStateOf(false) }
    var agreedToTerms by remember { mutableStateOf(true) }

    // Phone Authentication (Option A) State
    var verificationId by remember { mutableStateOf("") }
    var resendToken by remember { mutableStateOf<PhoneAuthProvider.ForceResendingToken?>(null) }
    var enteredOtp by remember { mutableStateOf("") }
    var isSendingOtp by remember { mutableStateOf(false) }
    var isVerifyingOtp by remember { mutableStateOf(false) }
    var isPhoneVerified by remember { mutableStateOf(false) }
    var otpCooldownSeconds by remember { mutableStateOf(0) }
    var authenticatedPhoneUser by remember { mutableStateOf<FirebaseUser?>(null) }

    // Google Authentication (Option B) State
    var isGoogleSigningIn by remember { mutableStateOf(false) }
    var authenticatedGoogleUser by remember {
        mutableStateOf(try {
            val user = FirebaseAuth.getInstance().currentUser
            if (user != null && !user.email.isNullOrBlank()) user else null
        } catch (_: Exception) { null })
    }

    // Existing Shop Detection State
    var existingShopFound by remember { mutableStateOf<PlatformBusiness?>(null) }
    var isCheckingExistingShop by remember { mutableStateOf(false) }

    // Overall Loading & Error State
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var successMessage by remember { mutableStateOf("") }
    var successRegisteredShopId by remember { mutableStateOf<String?>(null) }

    // Timer countdown effect for OTP Resend
    LaunchedEffect(otpCooldownSeconds) {
        if (otpCooldownSeconds > 0) {
            delay(1000L)
            otpCooldownSeconds -= 1
        }
    }

    // Auto-fill from Google account if authenticated
    LaunchedEffect(authenticatedGoogleUser) {
        val user = authenticatedGoogleUser
        if (user != null) {
            if (ownerName.isBlank() && !user.displayName.isNullOrBlank()) {
                ownerName = user.displayName!!
            }
            // Check if this Google user already has a shop
            coroutineScope.launch {
                isCheckingExistingShop = true
                val superAdminRepo = SuperAdminRepository.getInstance(context)
                val existing = superAdminRepo.findBusinessByOwner(user.uid, user.email ?: "")
                isCheckingExistingShop = false
                existingShopFound = existing
            }
        }
    }

    val businessTypes = listOf(
        "মুদি ও জেনারেল স্টোর",
        "ফার্মেসি ও ঔষধ",
        "কাপড়ের দোকান ও টেইলার্স",
        "ইলেকট্রনিক্স ও মোবাইল",
        "হার্ডওয়্যার ও স্যানিটারি",
        "হোটেল ও রেস্টুরেন্ট",
        "কসমেটিক্স ও গিফট শপ",
        "পাইকারি ট্রেডার্স",
        "অন্যান্য ব্যবসা"
    )

    val commonDistricts = listOf(
        "ঢাকা", "চট্টগ্রাম", "রাজশাহী", "খুলনা", "বরিশাল",
        "সিলেট", "রংপুর", "ময়মনসিংহ", "কুমিল্লা", "বগুড়া"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "নতুন দোকান রেজিস্ট্রেশন",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "সম্পূর্ণ বিনামূল্যে আজীবন ব্যবহার",
                            fontSize = 12.sp,
                            color = Color(0xFFFFD54F) // Amber
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("shop_registration_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "ফিরে যান")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryNavy,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Free Policy Banner Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2E7D32)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CardGiftcard,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "বিনামূল্যে ব্যবহারের স্পষ্ট নীতিমালা",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1B5E20),
                            fontSize = 14.sp
                        )
                        Text(
                            text = "কোনো ক্রেডিট কার্ড বা মাসিক ফি ছাড়াই আজীবন বেসিক ফিচার (বেচাকেনা, বাকি খাতা, স্টক, রসিদ) ফ্রি ব্যবহার করতে পারবেন।",
                            fontSize = 11.sp,
                            color = Color(0xFF2E7D32),
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Tab Selector: Option A (Phone Auth) vs Option B (Google Auth)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "নিবন্ধনের পদ্ধতি নির্বাচন করুন:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    PrimaryTabRow(
                        selectedTabIndex = selectedMethodTab,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    ) {
                        Tab(
                            selected = selectedMethodTab == 0,
                            onClick = {
                                selectedMethodTab = 0
                                errorMessage = ""
                            },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("মোবাইল নম্বর (SMS OTP)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        )
                        Tab(
                            selected = selectedMethodTab == 1,
                            onClick = {
                                selectedMethodTab = 1
                                errorMessage = ""
                            },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    GoogleLogoMini(modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Google অ্যাকাউন্ট", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Option A: Mobile Phone Authentication Card
            if (selectedMethodTab == 0) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 560.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
                    border = BorderStroke(1.dp, Color(0xFFBBF7D0))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PhoneInTalk, contentDescription = null, tint = Color(0xFF15803D), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "পদ্ধতি ১: মোবাইল নম্বর ও SMS ওটিপি যাচাই",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color(0xFF15803D)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "আপনার ১১ ডিজিটের বাংলাদেশী মোবাইল নম্বরে Firebase থেকে একটি ৬ সংখ্যার SMS OTP পাঠানো হবে।",
                            fontSize = 11.sp,
                            color = Color(0xFF166534)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Phone Input Field
                        OutlinedTextField(
                            value = phone,
                            onValueChange = {
                                phone = it
                                errorMessage = ""
                                if (isPhoneVerified && authenticatedPhoneUser?.phoneNumber != null) {
                                    // reset if user changes verified phone
                                    isPhoneVerified = false
                                    authenticatedPhoneUser = null
                                }
                            },
                            label = { Text("মোবাইল নম্বর (১১ ডিজিট) *") },
                            placeholder = { Text("017XXXXXXXX") },
                            leadingIcon = {
                                Icon(Icons.Default.Phone, contentDescription = null, tint = PrimaryNavy)
                            },
                            trailingIcon = {
                                if (isPhoneVerified) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = "যাচাইকৃত", tint = Color(0xFF16A34A))
                                }
                            },
                            enabled = !isPhoneVerified && !isSendingOtp,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("reg_phone_input")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Send OTP / Resend Button
                        if (!isPhoneVerified) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        val cleanPhone = phone.trim()
                                        if (cleanPhone.length < 11) {
                                            errorMessage = "সঠিক ১১ ডিজিটের মোবাইল নম্বর দিন!"
                                            return@Button
                                        }
                                        if (activity == null) {
                                            errorMessage = "সিস্টেম ত্রুটি: অ্যাক্টিভিটি পাওয়া যায়নি!"
                                            return@Button
                                        }
                                        isSendingOtp = true
                                        errorMessage = ""
                                        successMessage = "SMS OTP পাঠানো হচ্ছে..."

                                        PhoneAuthHelper.sendVerificationCode(
                                            activity = activity,
                                            phone = cleanPhone,
                                            forceResendingToken = resendToken,
                                            onCodeSent = { vId, token ->
                                                isSendingOtp = false
                                                verificationId = vId
                                                resendToken = token
                                                otpCooldownSeconds = 60
                                                successMessage = "আপনার মোবাইল নম্বরে ৬ সংখ্যার ওটিপি (OTP) পাঠানো হয়েছে!"
                                            },
                                            onAutoVerified = { user ->
                                                isSendingOtp = false
                                                authenticatedPhoneUser = user
                                                isPhoneVerified = true
                                                verificationId = ""
                                                successMessage = "মোবাইল নম্বর সফলভাবে যাচাই হয়েছে!"
                                                // Check existing business
                                                coroutineScope.launch {
                                                    val superAdminRepo = SuperAdminRepository.getInstance(context)
                                                    existingShopFound = superAdminRepo.findBusinessByOwner(user.uid, "")
                                                }
                                            },
                                            onError = { err ->
                                                isSendingOtp = false
                                                errorMessage = err
                                                successMessage = ""
                                            }
                                        )
                                    },
                                    enabled = !isSendingOtp && otpCooldownSeconds == 0,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                                    modifier = Modifier.weight(1f).height(44.dp).testTag("reg_send_otp_button")
                                ) {
                                    if (isSendingOtp) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            if (verificationId.isNotBlank()) "পুনরায় কোড পাঠান" else "ওটিপি (OTP) পাঠান",
                                            fontSize = 13.sp
                                        )
                                    }
                                }

                                if (otpCooldownSeconds > 0) {
                                    Text(
                                        text = "$otpCooldownSeconds সে.",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF15803D)
                                    )
                                }
                            }
                        }

                        // In-card Error & Fallback Banner for Phone Auth
                        if (!isPhoneVerified && errorMessage.isNotBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.Top) {
                                        Icon(
                                            Icons.Default.ErrorOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = errorMessage,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    if (PhoneAuthHelper.isProviderOrSmsRegionDisabledMessage(errorMessage)) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Button(
                                            onClick = {
                                                selectedMethodTab = 1
                                                errorMessage = ""
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D4ED8)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth().height(40.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                GoogleLogoMini(modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Google অ্যাকাউন্ট দিয়ে নিবন্ধন করুন (বিকল্প)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // OTP Code Entry Section
                        if (verificationId.isNotBlank() && !isPhoneVerified) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                color = Color.White,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFF86EFAC)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "মোবাইলে আসা ৬ সংখ্যার ওটিপি কোড লিখুন:",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF15803D)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = enteredOtp,
                                            onValueChange = {
                                                enteredOtp = it.filter { c -> c.isDigit() }.take(6)
                                                errorMessage = ""
                                            },
                                            placeholder = { Text("৬ সংখ্যার কোড") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            modifier = Modifier.weight(1f).testTag("reg_otp_input")
                                        )
                                        Button(
                                            onClick = {
                                                if (enteredOtp.length != 6) {
                                                    errorMessage = "৬ সংখ্যার কোডটি সঠিকভাবে লিখুন!"
                                                    return@Button
                                                }
                                                isVerifyingOtp = true
                                                errorMessage = ""
                                                PhoneAuthHelper.verifyCodeAndSignIn(
                                                    verificationId = verificationId,
                                                    code = enteredOtp,
                                                    onSuccess = { user ->
                                                        isVerifyingOtp = false
                                                        authenticatedPhoneUser = user
                                                        isPhoneVerified = true
                                                        verificationId = ""
                                                        successMessage = "মোবাইল নম্বর সফলভাবে যাচাই হয়েছে!"
                                                        // Check if this phone user already has a shop
                                                        coroutineScope.launch {
                                                            val superAdminRepo = SuperAdminRepository.getInstance(context)
                                                            existingShopFound = superAdminRepo.findBusinessByOwner(user.uid, "")
                                                        }
                                                    },
                                                    onError = { err ->
                                                        isVerifyingOtp = false
                                                        errorMessage = err
                                                    }
                                                )
                                            },
                                            enabled = !isVerifyingOtp && enteredOtp.length == 6,
                                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy),
                                            modifier = Modifier.height(52.dp).testTag("reg_verify_otp_button")
                                        ) {
                                            if (isVerifyingOtp) {
                                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                            } else {
                                                Text("যাচাই করুন", fontSize = 13.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Verified Status Badge
                        if (isPhoneVerified) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                color = Color(0xFFDCFCE7),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = Color(0xFF15803D), modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "মোবাইল নম্বর সফলভাবে যাচাইকৃত (Firebase Phone Verified)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF15803D)
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Option B: Google Sign-In Authentication Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 560.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                    border = BorderStroke(1.dp, Color(0xFFBFDBFE))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GoogleLogoMini(modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "পদ্ধতি ২: Google অ্যাকাউন্ট দিয়ে সরাসরি নিবন্ধন",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color(0xFF1D4ED8)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "আপনার বিশ্বস্ত Google অ্যাকাউন্ট দিয়ে এক ক্লিকে সাইন-ইন করে দোকান নিবন্ধন করুন। ক্লাউড ব্যাকআপ স্বয়ংক্রিয়ভাবে সক্রিয় হবে।",
                            fontSize = 11.sp,
                            color = Color(0xFF1E40AF)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        if (authenticatedGoogleUser != null && !authenticatedGoogleUser?.email.isNullOrBlank()) {
                            Surface(
                                color = Color(0xFFDCFCE7),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF15803D), modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "সংযুক্ত Google: ${authenticatedGoogleUser?.email}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF15803D)
                                        )
                                        Text(
                                            text = "মালিকের পরিচয় Firebase UID দ্বারা সুরক্ষিত।",
                                            fontSize = 10.sp,
                                            color = Color(0xFF166534)
                                        )
                                    }
                                }
                            }
                        } else {
                            Button(
                                onClick = {
                                    isGoogleSigningIn = true
                                    errorMessage = ""
                                    successMessage = ""
                                    GoogleSignInHelper.launchGoogleSignIn(
                                        context = context,
                                        scope = coroutineScope,
                                        onAuthSuccess = { fUser ->
                                            isGoogleSigningIn = false
                                            authenticatedGoogleUser = fUser
                                            if (ownerName.isBlank() && !fUser.displayName.isNullOrBlank()) {
                                                ownerName = fUser.displayName!!
                                            }
                                        },
                                        onCancelled = { isGoogleSigningIn = false },
                                        onError = { err ->
                                            isGoogleSigningIn = false
                                            errorMessage = "Google সাইন-ইন ত্রুটি: $err"
                                        }
                                    )
                                },
                                enabled = !isGoogleSigningIn,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF374151)),
                                border = BorderStroke(1.dp, Color(0xFFD1D5DB)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("shop_registration_google_signin_button")
                            ) {
                                if (isGoogleSigningIn) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = PrimaryNavy)
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        GoogleLogoMini(modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text("Google দিয়ে সাইন-ইন করুন", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Existing Shop Detected Alert (Prevents Overwriting & Duplicate Creation)
            existingShopFound?.let { existing ->
                Spacer(modifier = Modifier.height(14.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 560.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                    border = BorderStroke(1.dp, Color(0xFFFCD34D))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFB45309), modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ইতিমধ্যে নিবন্ধিত দোকান পাওয়া গেছে!",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color(0xFFB45309)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "আপনার এই অ্যাকাউন্টে ইতিমধ্যে '${existing.businessName}' নামের একটি দোকান নিবন্ধিত রয়েছে। একই অ্যাকাউন্টে একাধিক দোকান তৈরির পরিবর্তে আপনি সরাসরি আপনার দোকানে প্রবেশ করতে পারেন।",
                            fontSize = 12.sp,
                            color = Color(0xFF92400E),
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val gUser = authenticatedGoogleUser
                                if (gUser != null) {
                                    isLoading = true
                                    viewModel.handleGoogleSignInSuccess(gUser) { success, msg, _ ->
                                        isLoading = false
                                        if (success) {
                                            onRegistrationSuccess()
                                        } else {
                                            errorMessage = msg
                                        }
                                    }
                                } else {
                                    onRegistrationSuccess()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                            modifier = Modifier.fillMaxWidth().height(44.dp)
                        ) {
                            Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("বিদ্যমান দোকানে প্রবেশ করুন", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main Registration Form Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "দোকান ও মালিকের তথ্য পূরণ করুন",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // 1. Shop Name
                    OutlinedTextField(
                        value = shopName,
                        onValueChange = {
                            shopName = it
                            errorMessage = ""
                        },
                        label = { Text("দোকানের নাম *") },
                        placeholder = { Text("যেমন: আল-মদিনা জেনারেল স্টোর") },
                        leadingIcon = {
                            Icon(Icons.Default.Storefront, contentDescription = null, tint = PrimaryNavy)
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reg_shop_name_input")
                    )

                    // 2. Owner Name
                    OutlinedTextField(
                        value = ownerName,
                        onValueChange = {
                            ownerName = it
                            errorMessage = ""
                        },
                        label = { Text("দোকান মালিকের নাম *") },
                        placeholder = { Text("যেমন: মোঃ আব্দুর রহিম") },
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = null, tint = PrimaryNavy)
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reg_owner_name_input")
                    )

                    // If Google registration was chosen, provide Phone input for shop contact/login
                    if (selectedMethodTab == 1) {
                        OutlinedTextField(
                            value = phone,
                            onValueChange = {
                                phone = it
                                errorMessage = ""
                            },
                            label = { Text("দোকানের মোবাইল নম্বর *") },
                            placeholder = { Text("01XXXXXXXXX") },
                            leadingIcon = {
                                Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = PrimaryNavy)
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("reg_phone_input_google_tab")
                        )
                    }

                    // 3. Shop Address
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("দোকানের ঠিকানা / বাজার *") },
                        placeholder = { Text("যেমন: ধানমন্ডি ২৭, ঢাকা") },
                        leadingIcon = {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = PrimaryNavy)
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reg_address_input")
                    )

                    // 4. District
                    OutlinedTextField(
                        value = district,
                        onValueChange = { district = it },
                        label = { Text("জেলা / শহর *") },
                        placeholder = { Text("যেমন: ঢাকা") },
                        leadingIcon = {
                            Icon(Icons.Default.Map, contentDescription = null, tint = PrimaryNavy)
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reg_district_input")
                    )

                    // Quick District Chips
                    Column {
                        Text(
                            text = "জনপ্রিয় জেলা নির্বাচন করুন:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            commonDistricts.take(4).forEach { d ->
                                SuggestionChip(
                                    onClick = { district = d },
                                    label = { Text(d, fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    // 5. Business Type Dropdown
                    Column {
                        Text(
                            text = "ব্যবসার ধরন নির্বাচন করুন *",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        var expandedTypeDropdown by remember { mutableStateOf(false) }

                        ExposedDropdownMenuBox(
                            expanded = expandedTypeDropdown,
                            onExpandedChange = { expandedTypeDropdown = it }
                        ) {
                            OutlinedTextField(
                                value = selectedBusinessType,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedTypeDropdown)
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Category, contentDescription = null, tint = PrimaryNavy)
                                },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth()
                                    .testTag("reg_business_type_dropdown")
                            )
                            ExposedDropdownMenu(
                                expanded = expandedTypeDropdown,
                                onDismissRequest = { expandedTypeDropdown = false }
                            ) {
                                businessTypes.forEach { type ->
                                    DropdownMenuItem(
                                        text = { Text(type) },
                                        onClick = {
                                            selectedBusinessType = type
                                            expandedTypeDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // 6. Security PIN Setup
                    Text(
                        text = "নিরাপদ পিন কোড সেটআপ (৪-৬ সংখ্যা)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    OutlinedTextField(
                        value = pin,
                        onValueChange = {
                            pin = it.filter { c -> c.isDigit() }.take(6)
                            errorMessage = ""
                        },
                        label = { Text("নতুন পিন কোড (৪-৬ সংখ্যা) *") },
                        placeholder = { Text("যেমন: ১২৩৪") },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = PrimaryNavy)
                        },
                        trailingIcon = {
                            IconButton(onClick = { showPin = !showPin }) {
                                Icon(
                                    imageVector = if (showPin) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "পিন দেখুন"
                                )
                            }
                        },
                        singleLine = true,
                        visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reg_pin_input")
                    )

                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = {
                            confirmPin = it.filter { c -> c.isDigit() }.take(6)
                            errorMessage = ""
                        },
                        label = { Text("পিন কোড নিশ্চিত করুন (পুনরায় লিখুন) *") },
                        placeholder = { Text("একই পিন পুনরায় লিখুন") },
                        leadingIcon = {
                            Icon(Icons.Default.LockReset, contentDescription = null, tint = PrimaryNavy)
                        },
                        singleLine = true,
                        visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reg_confirm_pin_input")
                    )

                    // Terms Consent Checkbox
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { agreedToTerms = !agreedToTerms }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = agreedToTerms,
                            onCheckedChange = { agreedToTerms = it },
                            modifier = Modifier.testTag("reg_terms_checkbox")
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "আমি শর্তাবলী এবং সম্পূর্ণ বিনামূল্যে ব্যবহারের নীতিমালায় সম্মত।",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Success Message
                    if (successMessage.isNotBlank()) {
                        Surface(
                            color = Color(0xFFDCFCE7),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = successMessage,
                                color = Color(0xFF15803D),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    // Error Message Banner
                    if (errorMessage.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = errorMessage,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (PhoneAuthHelper.isProviderOrSmsRegionDisabledMessage(errorMessage) && selectedMethodTab == 0) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = {
                                            selectedMethodTab = 1
                                            errorMessage = ""
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            GoogleLogoMini(modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Google অ্যাকাউন্ট দিয়ে নিবন্ধন করুন (বিকল্প)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Registration Submit Button
                    Button(
                        onClick = {
                            val cleanShop = shopName.trim()
                            val cleanOwner = ownerName.trim()
                            val cleanPhone = phone.trim()
                            val cleanAddress = address.trim()
                            val cleanDistrict = district.trim()
                            val cleanPin = pin.trim()
                            val cleanConfirmPin = confirmPin.trim()

                            if (cleanShop.length < 2) {
                                errorMessage = "দোকানের নাম সঠিকভাবে লিখুন (কমপক্ষে ২ অক্ষর)!"
                                return@Button
                            }
                            if (cleanOwner.length < 2) {
                                errorMessage = "দোকান মালিকের নাম লিখুন!"
                                return@Button
                            }
                            if (cleanPhone.length < 11) {
                                errorMessage = "সঠিক ১১ ডিজিটের মোবাইল নম্বর দিন!"
                                return@Button
                            }
                            if (cleanPin.length < 4 || cleanPin.length > 6) {
                                errorMessage = "পিন কোড ৪ থেকে ৬ সংখ্যার হতে হবে!"
                                return@Button
                            }
                            if (cleanPin != cleanConfirmPin) {
                                errorMessage = "উভয় পিন কোড হুবহু এক হতে হবে!"
                                return@Button
                            }
                            if (!agreedToTerms) {
                                errorMessage = "অনুগ্রহ করে শর্তাবলীতে সম্মতি প্রদান করুন!"
                                return@Button
                            }

                            // Verify Authentication Provider Requirements
                            val resolvedUid: String
                            val resolvedEmail: String

                            if (selectedMethodTab == 0) {
                                // Mobile Number (Option A)
                                val phoneUser = authenticatedPhoneUser
                                    ?: try {
                                        val u = FirebaseAuth.getInstance().currentUser
                                        if (u != null && !u.phoneNumber.isNullOrBlank()) u else null
                                    } catch (_: Exception) { null }

                                if (phoneUser == null || !isPhoneVerified) {
                                    errorMessage = "দোকান নিবন্ধনের আগে ওটিপি (OTP) কোড যাচাই সম্পন্ন করুন!"
                                    return@Button
                                }
                                resolvedUid = phoneUser.uid
                                resolvedEmail = phoneUser.email ?: ""
                            } else {
                                // Google Sign-In (Option B)
                                val googleUser = authenticatedGoogleUser
                                    ?: try {
                                        val u = FirebaseAuth.getInstance().currentUser
                                        if (u != null && !u.email.isNullOrBlank()) u else null
                                    } catch (_: Exception) { null }

                                if (googleUser == null) {
                                    errorMessage = "দোকান নিবন্ধনের আগে Google অ্যাকাউন্ট দিয়ে সাইন-ইন সম্পন্ন করুন!"
                                    return@Button
                                }
                                resolvedUid = googleUser.uid
                                resolvedEmail = googleUser.email ?: ""
                            }

                            isLoading = true
                            errorMessage = ""
                            successMessage = ""

                            viewModel.registerNewShop(
                                shopName = cleanShop,
                                ownerName = cleanOwner,
                                phone = cleanPhone,
                                address = cleanAddress,
                                district = cleanDistrict,
                                businessType = selectedBusinessType,
                                pin = cleanPin,
                                isPhoneVerified = (selectedMethodTab == 0 && isPhoneVerified),
                                ownerEmail = resolvedEmail,
                                ownerUid = resolvedUid,
                                superAdminViewModel = superAdminViewModel
                            ) { success, msg, newShopId ->
                                isLoading = false
                                if (success) {
                                    successRegisteredShopId = newShopId
                                } else {
                                    errorMessage = msg
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("reg_submit_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Storefront, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "দোকান নিবন্ধন সম্পন্ন করুন — বিনামূল্যে",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Success Dialog on Successful Registration
    successRegisteredShopId?.let { shopId ->
        AlertDialog(
            onDismissRequest = { /* user must tap continue */ },
            icon = {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(
                    text = "অভিনন্দন! রেজিস্ট্রেশন সম্পন্ন",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "আপনার দোকান '${shopName.trim()}' সফলভাবে নিবন্ধিত হয়েছে।",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("ইউনিক বিজনেস কোড: $shopId", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("লগইন নম্বর: ${phone.trim()}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("প্ল্যান: সম্পূর্ণ ফ্রি ব্যবহার (আজীবন)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1B5E20))
                        }
                    }
                    Text(
                        text = "আপনার দোকানটি ক্লাউড ডাটাবেস ও প্ল্যাটফর্ম সুপার এডমিন কনসোলের সঙ্গে সংযুক্ত হয়েছে। আপনি এখনই সম্পূর্ণ বিনামূল্যে আপনার দোকানের বেচাকেনা পরিচালনা শুরু করতে পারেন।",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        successRegisteredShopId = null
                        onRegistrationSuccess()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("reg_success_continue_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy)
                ) {
                    Icon(Icons.Default.Dashboard, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("এডমিন ড্যাশবোর্ডে প্রবেশ করুন")
                }
            }
        )
    }
}
