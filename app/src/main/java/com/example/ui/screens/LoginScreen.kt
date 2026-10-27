package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.User
import com.example.data.storage.SavedAccount
import com.example.util.GoogleSignInHelper
import com.example.viewmodel.BusinessViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: BusinessViewModel,
    onNavigateToAdminLogin: () -> Unit,
    onNavigateToRegistration: () -> Unit = {},
    onLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    val savedAccounts by viewModel.savedAccounts.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    var isGoogleLoading by remember { mutableStateOf(false) }
    var showGoogleDiagDialog by remember { mutableStateOf(false) }
    var showForgotPinDialog by remember { mutableStateOf(false) }
    var googleStatusMessage by remember { mutableStateOf("") }
    var pendingGoogleUser by remember { mutableStateOf<com.google.firebase.auth.FirebaseUser?>(null) }
    var linkBusinessIdInput by remember { mutableStateOf("") }
    var linkPhoneInput by remember { mutableStateOf("") }
    var linkPinInput by remember { mutableStateOf("") }
    var linkErrorMessage by remember { mutableStateOf("") }
    var isLinkingLoading by remember { mutableStateOf(false) }

    var phone by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var showPin by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isOtpMode by remember { mutableStateOf(false) }
    var otpCode by remember { mutableStateOf("") }
    var otpSentNotice by remember { mutableStateOf("") }

    // Tab state: 0 = Mobile, 1 = Google
    var selectedLoginTab by remember { mutableStateOf(0) }

    // Consent checkbox state: "আমাকে মনে রাখুন"
    var rememberThisPhone by remember { mutableStateOf(true) }

    // Whether user manually chose "Use another account / manual number input"
    var showManualInputOnly by remember { mutableStateOf(false) }

    // State for account being removed (confirmation dialog)
    var accountToRemove by remember { mutableStateOf<SavedAccount?>(null) }

    // Map to cache registered user info for saved accounts
    var accountUsersMap by remember { mutableStateOf<Map<String, User?>>(emptyMap()) }

    // Load user metadata for saved accounts when list updates
    LaunchedEffect(savedAccounts) {
        val newMap = mutableMapOf<String, User?>()
        savedAccounts.forEach { acc ->
            newMap[acc.phone] = viewModel.getAccountDetailsForPhone(acc.phone)
        }
        accountUsersMap = newMap

        if (phone.isBlank() && savedAccounts.isNotEmpty() && !showManualInputOnly) {
            phone = savedAccounts.first().phone
        }
    }

    // 5-Tap Secret Gesture Counter for Hidden Super Admin Login
    var secretTapCount by remember { mutableStateOf(0) }
    var lastTapTime by remember { mutableStateOf(0L) }

    val handleSecretTap = {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastTapTime > 2000L) {
            secretTapCount = 1
        } else {
            secretTapCount++
        }
        lastTapTime = currentTime

        if (secretTapCount >= 5) {
            secretTapCount = 0
            onNavigateToAdminLogin()
        }
    }

    // Color Palette matching design reference
    val primaryBlue = Color(0xFF0284C7)      // Vibrant cyan-blue
    val darkBlueText = Color(0xFF1E3A8A)     // Deep navy for title
    val brandBlueText = Color(0xFF1D4ED8)    // App brand title blue
    val textPrimary = Color(0xFF0F172A)      // Dark slate
    val textSecondary = Color(0xFF64748B)    // Muted slate
    val cardBorder = Color(0xFFE2E8F0)
    val inputBorder = Color(0xFFCBD5E1)

    val triggerGoogleSignIn = {
        isGoogleLoading = true
        errorMessage = ""
        googleStatusMessage = ""
        GoogleSignInHelper.launchGoogleSignIn(
            context = context,
            scope = coroutineScope,
            onAuthSuccess = { fUser ->
                viewModel.handleGoogleSignInSuccess(fUser) { success, msg, _ ->
                    isGoogleLoading = false
                    if (success) {
                        onLoginSuccess()
                    } else {
                        googleStatusMessage = msg
                        pendingGoogleUser = fUser
                        linkBusinessIdInput = viewModel.getActiveBusinessId()
                        linkPhoneInput = phone.ifBlank { "" }
                        linkPinInput = ""
                        linkErrorMessage = ""
                    }
                }
            },
            onCancelled = {
                isGoogleLoading = false
                googleStatusMessage = "গুগল সাইন-ইন বাতিল করা হয়েছে।"
            },
            onError = { err ->
                isGoogleLoading = false
                googleStatusMessage = err
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFFE0F2FE),
                        Color(0xFFF0F9FF),
                        Color(0xFFF8FAFC)
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // =================================================================
            // 1. HEADER SECTION (Store Logo + Title + Slogan + Illustration)
            // =================================================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                // Header Content
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Central Store Icon Squircle
                    Surface(
                        modifier = Modifier
                            .size(68.dp)
                            .testTag("app_logo_secret_trigger")
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                handleSecretTap()
                            },
                        shape = RoundedCornerShape(18.dp),
                        color = primaryBlue,
                        shadowElevation = 4.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Storefront,
                                contentDescription = "স্মার্ট বিজনেস লোগো",
                                tint = Color.White,
                                modifier = Modifier.size(38.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // App Title
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .testTag("app_title_secret_trigger")
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                handleSecretTap()
                            }
                    ) {
                        Text(
                            text = "Smart Business",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 23.sp,
                                color = brandBlueText
                            ),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Manager",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 23.sp,
                                color = brandBlueText
                            ),
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Bengali Slogan
                    Text(
                        text = "স্মার্ট ব্যবসা, সহজ ব্যবস্থাপনা",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = textPrimary
                        ),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    // Feature Bullets
                    Text(
                        text = "বিক্রি • স্টক • বকেয়া • হিসাব — সব এক জায়গায়",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.5.sp,
                            color = textSecondary
                        ),
                        textAlign = TextAlign.Center
                    )
                }

                // Decorative miniature storefront graphic on the right
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 16.dp, end = 2.dp)
                ) {
                    StoreMiniIllustration(modifier = Modifier.size(76.dp, 64.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // =================================================================
            // 2. MAIN LOGIN CARD (White Container with Rounded Corners)
            // =================================================================
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.5.dp),
                border = BorderStroke(1.dp, cardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp)
                ) {
                    // Card Header
                    Text(
                        text = "আপনার অ্যাকাউন্টে প্রবেশ করুন",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = darkBlueText
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "আগে থেকে থাকলে লগইন করুন, নাহলে নতুন দোকান খুলুন।",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            color = textSecondary
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // -------------------------------------------------------------
                    // TOP METHOD TABS: [📱 মোবাইল নম্বর] & [ Google ]
                    // -------------------------------------------------------------
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Mobile Tab
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { selectedLoginTab = 0 },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedLoginTab == 0) primaryBlue else Color.White,
                            border = if (selectedLoginTab == 0) null else BorderStroke(1.dp, inputBorder)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhoneAndroid,
                                    contentDescription = null,
                                    tint = if (selectedLoginTab == 0) Color.White else textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "মোবাইল নম্বর",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selectedLoginTab == 0) Color.White else textPrimary
                                )
                            }
                        }

                        // Google Tab
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    selectedLoginTab = 1
                                    triggerGoogleSignIn()
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedLoginTab == 1) Color(0xFFEFF6FF) else Color.White,
                            border = BorderStroke(1.dp, if (selectedLoginTab == 1) primaryBlue else inputBorder)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                GoogleLogoMini(modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Google",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textPrimary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // -------------------------------------------------------------
                    // SAVED ACCOUNTS PICKER (If accounts remembered)
                    // -------------------------------------------------------------
                    if (savedAccounts.isNotEmpty() && !showManualInputOnly) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "সংরক্ষিত অ্যাকাউন্ট নির্বাচন করুন",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = primaryBlue
                                )
                            )
                            TextButton(
                                onClick = {
                                    showManualInputOnly = true
                                    phone = ""
                                    errorMessage = ""
                                },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .height(26.dp)
                                    .testTag("use_another_account_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = primaryBlue)
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("অন্য নম্বর", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = primaryBlue)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("saved_accounts_container")
                        ) {
                            savedAccounts.forEach { acc ->
                                val isSelected = phone.trim() == acc.phone.trim()
                                val userObj = accountUsersMap[acc.phone]
                                val displayName = userObj?.name ?: "সংরক্ষিত অ্যাকাউন্ট"

                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("saved_account_card_${acc.phone}")
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            phone = acc.phone
                                            errorMessage = ""
                                        },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) Color(0xFFF0F9FF) else Color(0xFFF8FAFC),
                                    border = if (isSelected) BorderStroke(1.5.dp, primaryBlue) else BorderStroke(1.dp, cardBorder)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(if (isSelected) primaryBlue else Color(0xFF94A3B8)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Person,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(10.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = displayName,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 13.sp
                                                ),
                                                color = textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = acc.maskedPhone,
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                                color = textSecondary
                                            )
                                        }

                                        IconButton(
                                            onClick = { accountToRemove = acc },
                                            modifier = Modifier
                                                .size(28.dp)
                                                .testTag("remove_saved_account_${acc.phone}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "সংরক্ষিত নম্বর সরান",
                                                tint = Color(0xFF94A3B8),
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    if (showManualInputOnly && savedAccounts.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "মোবাইল নম্বর দিন",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = primaryBlue
                                )
                            )
                            TextButton(
                                onClick = {
                                    showManualInputOnly = false
                                    if (savedAccounts.isNotEmpty()) {
                                        phone = savedAccounts.first().phone
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .height(26.dp)
                                    .testTag("show_saved_accounts_list_button")
                            ) {
                                Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(14.dp), tint = primaryBlue)
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("সংরক্ষিত তালিকা", fontSize = 11.sp, color = primaryBlue)
                            }
                        }
                    }

                    // -------------------------------------------------------------
                    // 1. MOBILE NUMBER INPUT FIELD
                    // -------------------------------------------------------------
                    OutlinedTextField(
                        value = phone,
                        onValueChange = {
                            phone = it
                            errorMessage = ""
                        },
                        placeholder = { Text("মোবাইল নম্বর (01XXXXXXXXX)", fontSize = 13.5.sp, color = Color(0xFF94A3B8)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = null,
                                tint = primaryBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        trailingIcon = {
                            if (phone.isNotBlank()) {
                                IconButton(onClick = { phone = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "মুছুন", modifier = Modifier.size(18.dp), tint = Color(0xFF94A3B8))
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryBlue,
                            unfocusedBorderColor = inputBorder,
                            focusedTextColor = textPrimary,
                            unfocusedTextColor = textPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_phone_input")
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // -------------------------------------------------------------
                    // 2. PIN / OTP INPUT FIELD
                    // -------------------------------------------------------------
                    if (isOtpMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = otpCode,
                                onValueChange = {
                                    otpCode = it
                                    errorMessage = ""
                                },
                                placeholder = { Text("৪ ডিজিটের ওটিপি (1234)", fontSize = 13.sp, color = Color(0xFF94A3B8)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Lock, contentDescription = null, tint = primaryBlue, modifier = Modifier.size(20.dp))
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = primaryBlue,
                                    unfocusedBorderColor = inputBorder
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("login_otp_input")
                            )

                            Button(
                                onClick = {
                                    val trimmedPhone = phone.trim()
                                    if (trimmedPhone.length < 11) {
                                        errorMessage = "ওটিপি পাঠানোর জন্য সঠিক মোবাইল নম্বর দিন"
                                    } else {
                                        otpSentNotice = "আপনার নম্বরে ৪ ডিজিটের ওটিপি পাঠানো হয়েছে: 1234"
                                        otpCode = "1234"
                                        errorMessage = ""
                                    }
                                },
                                modifier = Modifier
                                    .height(52.dp)
                                    .testTag("send_otp_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = primaryBlue)
                            ) {
                                Text("ওটিপি পাঠান", fontSize = 12.sp)
                            }
                        }

                        if (otpSentNotice.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = otpSentNotice,
                                style = MaterialTheme.typography.bodySmall,
                                color = primaryBlue,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        // Standard PIN / Password Input
                        OutlinedTextField(
                            value = pin,
                            onValueChange = {
                                pin = it
                                errorMessage = ""
                            },
                            placeholder = { Text("পাসওয়ার্ড / PIN", fontSize = 13.5.sp, color = Color(0xFF94A3B8)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = primaryBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = { showPin = !showPin }) {
                                    Icon(
                                        imageVector = if (showPin) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "পিন দেখুন",
                                        tint = Color(0xFF64748B),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            },
                            singleLine = true,
                            visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryBlue,
                                unfocusedBorderColor = inputBorder,
                                focusedTextColor = textPrimary,
                                unfocusedTextColor = textPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("login_pin_input")
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // -------------------------------------------------------------
                    // 3. REMEMBER ME & FORGOT PIN ROW
                    // -------------------------------------------------------------
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: Checkbox + "আমাকে মনে রাখুন"
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { rememberThisPhone = !rememberThisPhone }
                                .padding(vertical = 2.dp)
                        ) {
                            Checkbox(
                                checked = rememberThisPhone,
                                onCheckedChange = { rememberThisPhone = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = primaryBlue,
                                    uncheckedColor = Color(0xFF94A3B8)
                                ),
                                modifier = Modifier
                                    .size(20.dp)
                                    .testTag("remember_phone_checkbox")
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "আমাকে মনে রাখুন",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = textPrimary
                                )
                            )
                        }

                        // Right: "PIN ভুলে গেছেন?"
                        TextButton(
                            onClick = { showForgotPinDialog = true },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                            modifier = Modifier.testTag("toggle_otp_pin_mode_button")
                        ) {
                            Text(
                                text = "PIN ভুলে গেছেন?",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = primaryBlue
                                )
                            )
                        }
                    }

                    // Mobile Login Error Message
                    if (errorMessage.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = Color(0xFFFEF2F2),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFFFCA5A5)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = Color(0xFFDC2626),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = errorMessage,
                                    color = Color(0xFF991B1B),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // -------------------------------------------------------------
                    // 4. PRIMARY BLUE LOGIN BUTTON: "প্রবেশ করুন ➔"
                    // -------------------------------------------------------------
                    Button(
                        onClick = {
                            val trimmedPhone = phone.trim()
                            val trimmedPin = pin.trim()
                            val trimmedOtp = otpCode.trim()
                            isLoading = true
                            errorMessage = ""
                            googleStatusMessage = ""

                            if (isOtpMode) {
                                viewModel.loginWithPhoneOtp(trimmedPhone, trimmedOtp) { success, msg, _ ->
                                    isLoading = false
                                    if (success) {
                                        if (rememberThisPhone) {
                                            viewModel.saveRememberedPhone(trimmedPhone)
                                        } else {
                                            viewModel.removeRememberedPhone(trimmedPhone)
                                        }
                                        onLoginSuccess()
                                    } else {
                                        errorMessage = msg
                                    }
                                }
                            } else {
                                viewModel.login(trimmedPhone, trimmedPin) { success, msg ->
                                    isLoading = false
                                    if (success) {
                                        if (rememberThisPhone) {
                                            viewModel.saveRememberedPhone(trimmedPhone)
                                        } else {
                                            viewModel.removeRememberedPhone(trimmedPhone)
                                        }
                                        onLoginSuccess()
                                    } else {
                                        errorMessage = msg
                                    }
                                }
                            }
                        },
                        enabled = !isLoading && phone.isNotBlank() && (if (isOtpMode) otpCode.isNotBlank() else pin.isNotBlank()),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("login_submit_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryBlue,
                            disabledContainerColor = Color(0xFFCBD5E1)
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "প্রবেশ করুন",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // -------------------------------------------------------------
                    // "অথবা" DIVIDER
                    // -------------------------------------------------------------
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = cardBorder)
                        Text(
                            text = "অথবা",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = textSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f), color = cardBorder)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // -------------------------------------------------------------
                    // 5. GOOGLE SIGN-IN BUTTON: "Google দিয়ে সাইন ইন করুন"
                    // -------------------------------------------------------------
                    OutlinedButton(
                        onClick = {
                            selectedLoginTab = 1
                            triggerGoogleSignIn()
                        },
                        enabled = !isGoogleLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("google_sign_in_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF374151)
                        ),
                        border = BorderStroke(1.dp, inputBorder)
                    ) {
                        if (isGoogleLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = primaryBlue
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                GoogleLogoMini(modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Google দিয়ে সাইন ইন করুন",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF374151)
                                )
                            }
                        }
                    }

                    // Google Sign-In Status / Error Message
                    if (googleStatusMessage.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = Color(0xFFFEF3C7),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFFFDE68A)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = Color(0xFFB45309),
                                    modifier = Modifier.size(18.dp).padding(top = 2.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = googleStatusMessage,
                                    color = Color(0xFF92400E),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // -------------------------------------------------------------
                    // 6. NEW SHOP REGISTRATION BOX (Light Tinted Card)
                    // -------------------------------------------------------------
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFEFF6FF),
                        border = BorderStroke(1.dp, Color(0xFFBAE6FD))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp)
                        ) {
                            // Top Info Row with Chevron
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onNavigateToRegistration() }
                                    .padding(bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    modifier = Modifier.size(36.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFFDBEAFE)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Storefront,
                                            contentDescription = null,
                                            tint = primaryBlue,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "নতুন দোকান খুলবেন?",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = textPrimary
                                        )
                                    )
                                    Text(
                                        text = "কয়েকটি তথ্য দিয়ে আপনার দোকান রেজিস্ট্রেশন করুন",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.5.sp,
                                            color = textSecondary
                                        )
                                    )
                                }

                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "রেজিস্ট্রেশন করুন",
                                    tint = primaryBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Full Width Button inside Registration Box
                            Button(
                                onClick = onNavigateToRegistration,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .testTag("navigate_to_shop_registration_button"),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = primaryBlue)
                            ) {
                                Text(
                                    text = "+ নতুন দোকান রেজিস্ট্রেশন",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // =================================================================
            // 3. FOOTER SECTION (Version + 3 Feature Badges)
            // =================================================================
            Text(
                text = "Smart Business Manager v1.0",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    color = textSecondary
                ),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 3 Feature Badges Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFE0F2FE).copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Badge 1: Secure Login
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(primaryBlue),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "নিরাপদ লগইন",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary
                        )
                        Text(
                            text = "আপনার তথ্য সুরক্ষিত",
                            fontSize = 9.sp,
                            color = textSecondary
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .width(1.dp)
                        .background(Color(0xFFBAE6FD))
                )

                // Badge 2: Cloud Sync
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(primaryBlue),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cloud,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "ক্লাউড সিঙ্ক",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary
                        )
                        Text(
                            text = "সবসময় আপডেটেড",
                            fontSize = 9.sp,
                            color = textSecondary
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .width(1.dp)
                        .background(Color(0xFFBAE6FD))
                )

                // Badge 3: Multi-device
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(primaryBlue),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "সব ডিভাইসে",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary
                        )
                        Text(
                            text = "সহজ ও দ্রুত",
                            fontSize = 9.sp,
                            color = textSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Diagnostic assistance link (preserved for developer / verification check)
            TextButton(
                onClick = { showGoogleDiagDialog = true },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .testTag("google_signin_diagnostic_button")
            ) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp), tint = textSecondary)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Google Sign-In সহায়তা ও কনফিগারেশন",
                    fontSize = 11.sp,
                    color = textSecondary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // =================================================================
    // FORGOT PIN DIALOG
    // =================================================================
    if (showForgotPinDialog) {
        AlertDialog(
            onDismissRequest = { showForgotPinDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = primaryBlue,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "PIN ভুলে গেছেন?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = textPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "নিরাপত্তার স্বার্থে পিন কোড সরাসরি প্রদর্শন করা হয় না। পিন ভুলে গেলে করণীয়:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = textPrimary
                    )
                    Text(
                        text = "১. দোকানের প্রধান অ্যাডমিন / মালিকের সাথে যোগাযোগ করুন — অ্যাডমিন প্যানেল থেকে আপনার PIN সহজেই পরিবর্তন করা যাবে।",
                        style = MaterialTheme.typography.bodySmall,
                        color = textSecondary
                    )
                    Text(
                        text = "২. অথবা আপনার সংযুক্ত গুগল অ্যাকাউন্ট থাকলে 'Google দিয়ে সাইন ইন করুন' বোতাম চেপে সরাসরি প্রবেশ করুন।",
                        style = MaterialTheme.typography.bodySmall,
                        color = textSecondary
                    )
                    Text(
                        text = "৩. ওটিপি (OTP) মোড ব্যবহার করে ৪ ডিজিটের ওটিপি দিয়েও লগইন করতে পারেন।",
                        style = MaterialTheme.typography.bodySmall,
                        color = textSecondary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isOtpMode = true
                        showForgotPinDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryBlue),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("ওটিপি দিয়ে চেষ্টা করুন")
                }
            },
            dismissButton = {
                TextButton(onClick = { showForgotPinDialog = false }) {
                    Text("বুঝেছি")
                }
            }
        )
    }

    // =================================================================
    // DIAGNOSTIC DIALOG (Verified Keystore Fingerprints & Config)
    // =================================================================
    if (showGoogleDiagDialog) {
        com.example.util.GoogleSignInDiagnosticDialog(
            onDismiss = { showGoogleDiagDialog = false }
        )
    }

    // =================================================================
    // ACCOUNT LINKING DIALOG (Safe ownership validation with PIN)
    // =================================================================
    if (pendingGoogleUser != null) {
        val fUser = pendingGoogleUser!!
        AlertDialog(
            onDismissRequest = {
                if (!isLinkingLoading) pendingGoogleUser = null
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    tint = primaryBlue,
                    modifier = Modifier.size(34.dp)
                )
            },
            title = {
                Text(
                    text = "গুগল অ্যাকাউন্ট সংযুক্তকরণ",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    color = textPrimary
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "সাইন-ইনকৃত জিমেইল: ${fUser.email ?: fUser.displayName ?: "গুগল আইডি"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = primaryBlue,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "নিরাপত্তার স্বার্থে আপনার নিবন্ধিত মোবাইল নম্বর ও গোপন পিন কোড দিয়ে অ্যাকাউন্টটির মালিকানা নিশ্চিত করুন:",
                        style = MaterialTheme.typography.bodySmall,
                        color = textSecondary
                    )

                    OutlinedTextField(
                        value = linkPhoneInput,
                        onValueChange = { linkPhoneInput = it; linkErrorMessage = "" },
                        label = { Text("নিবন্ধিত মোবাইল নম্বর *") },
                        placeholder = { Text("01XXXXXXXXX") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().testTag("link_phone_input")
                    )

                    OutlinedTextField(
                        value = linkPinInput,
                        onValueChange = { linkPinInput = it; linkErrorMessage = "" },
                        label = { Text("গোপন পিন কোড *") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().testTag("link_pin_input")
                    )

                    OutlinedTextField(
                        value = linkBusinessIdInput,
                        onValueChange = { linkBusinessIdInput = it; linkErrorMessage = "" },
                        label = { Text("ক্লাউড বিজনেস কোড (ঐচ্ছিক)") },
                        placeholder = { Text("সেলসম্যান হলে অ্যাডমিনের বিজনেস কোড দিন") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        trailingIcon = {
                            IconButton(onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                                if (!clip.isNullOrBlank()) {
                                    linkBusinessIdInput = clip.trim()
                                    linkErrorMessage = ""
                                }
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "পেস্ট করুন")
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("link_business_id_input")
                    )

                    if (linkErrorMessage.isNotBlank()) {
                        Text(
                            text = linkErrorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    HorizontalDivider(color = cardBorder)

                    Text(
                        text = "নতুন দোকানদার? কোনো বিদ্যমান দোকানে যুক্ত না হয়ে নতুন দোকান খুলতে চাইলে:",
                        style = MaterialTheme.typography.bodySmall,
                        color = textSecondary
                    )

                    OutlinedButton(
                        onClick = {
                            pendingGoogleUser = null
                            onNavigateToRegistration()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("google_link_register_new_shop_button"),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, primaryBlue)
                    ) {
                        Icon(Icons.Default.Storefront, contentDescription = null, tint = primaryBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("নতুন দোকান খুলুন (প্রথম অ্যাডমিন)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = primaryBlue)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isLinkingLoading = true
                        linkErrorMessage = ""
                        val cleanBiz = linkBusinessIdInput.trim()
                        viewModel.linkGoogleAccountWithPin(
                            firebaseUser = fUser,
                            phone = linkPhoneInput.trim(),
                            pin = linkPinInput.trim(),
                            businessId = cleanBiz.ifBlank { null }
                        ) { success, msg, role ->
                            isLinkingLoading = false
                            if (success) {
                                pendingGoogleUser = null
                                onLoginSuccess()
                            } else {
                                linkErrorMessage = msg
                            }
                        }
                    },
                    enabled = !isLinkingLoading && linkPhoneInput.isNotBlank() && linkPinInput.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryBlue),
                    modifier = Modifier.testTag("confirm_link_account_button")
                ) {
                    if (isLinkingLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("সংযুক্ত করুন ও প্রবেশ করুন")
                    }
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { pendingGoogleUser = null },
                    enabled = !isLinkingLoading,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("বাতিল")
                }
            }
        )
    }

    // =================================================================
    // SAVED ACCOUNT REMOVAL CONFIRMATION DIALOG
    // =================================================================
    if (accountToRemove != null) {
        val acc = accountToRemove!!
        AlertDialog(
            onDismissRequest = { accountToRemove = null },
            title = {
                Text("সংরক্ষিত নম্বর সরান", fontWeight = FontWeight.Bold)
            },
            text = {
                Text("আপনি কি এই ডিভাইস থেকে ${acc.maskedPhone} নম্বরটি সরিয়ে ফেলতে চান?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.removeRememberedPhone(acc.phone)
                        if (phone.trim() == acc.phone.trim()) {
                            phone = ""
                        }
                        accountToRemove = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("confirm_remove_account_button")
                ) {
                    Text("সরিয়ে ফেলুন")
                }
            },
            dismissButton = {
                TextButton(onClick = { accountToRemove = null }) {
                    Text("বাতিল")
                }
            }
        )
    }
}

/**
 * Miniature Google 'G' icon drawn with official brand colors.
 */
@Composable
fun GoogleLogoMini(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val sizePx = size.minDimension
        val stroke = sizePx * 0.22f
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = (sizePx - stroke) / 2f
        val rectTopLeft = Offset(center.x - radius, center.y - radius)
        val rectSize = Size(radius * 2, radius * 2)

        // Blue Horizontal Bar
        drawLine(
            color = Color(0xFF4285F4),
            start = center,
            end = Offset(center.x + radius, center.y),
            strokeWidth = stroke,
            cap = StrokeCap.Square
        )

        // Arc Blue (top right)
        drawArc(
            color = Color(0xFF4285F4),
            startAngle = 0f,
            sweepAngle = -45f,
            useCenter = false,
            topLeft = rectTopLeft,
            size = rectSize,
            style = Stroke(width = stroke)
        )

        // Arc Red (top)
        drawArc(
            color = Color(0xFFEA4335),
            startAngle = -45f,
            sweepAngle = -90f,
            useCenter = false,
            topLeft = rectTopLeft,
            size = rectSize,
            style = Stroke(width = stroke)
        )

        // Arc Yellow (left)
        drawArc(
            color = Color(0xFFFBBC05),
            startAngle = -135f,
            sweepAngle = -90f,
            useCenter = false,
            topLeft = rectTopLeft,
            size = rectSize,
            style = Stroke(width = stroke)
        )

        // Arc Green (bottom)
        drawArc(
            color = Color(0xFF34A853),
            startAngle = 30f,
            sweepAngle = 105f,
            useCenter = false,
            topLeft = rectTopLeft,
            size = rectSize,
            style = Stroke(width = stroke)
        )
    }
}

/**
 * Cute decorative store illustration with awning & checklist matching reference image.
 */
@Composable
fun StoreMiniIllustration(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Store building base
        drawRoundRect(
            color = Color(0xFFE0F2FE),
            topLeft = Offset(w * 0.15f, h * 0.25f),
            size = Size(w * 0.65f, h * 0.7f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
        )

        // Store awning (blue stripes)
        drawRoundRect(
            color = Color(0xFF0284C7),
            topLeft = Offset(w * 0.1f, h * 0.15f),
            size = Size(w * 0.75f, h * 0.22f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
        )
        // White awning stripes
        drawRect(
            color = Color.White.copy(alpha = 0.8f),
            topLeft = Offset(w * 0.25f, h * 0.15f),
            size = Size(w * 0.12f, h * 0.22f)
        )
        drawRect(
            color = Color.White.copy(alpha = 0.8f),
            topLeft = Offset(w * 0.55f, h * 0.15f),
            size = Size(w * 0.12f, h * 0.22f)
        )

        // Store window
        drawRoundRect(
            color = Color(0xFF38BDF8).copy(alpha = 0.6f),
            topLeft = Offset(w * 0.22f, h * 0.45f),
            size = Size(w * 0.22f, h * 0.25f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
        )

        // Store door
        drawRoundRect(
            color = Color(0xFF0284C7),
            topLeft = Offset(w * 0.52f, h * 0.45f),
            size = Size(w * 0.2f, h * 0.5f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
        )

        // Checklist clipboard on the right
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(w * 0.72f, h * 0.35f),
            size = Size(w * 0.26f, h * 0.55f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
        )
        // Clipboard top clip
        drawRoundRect(
            color = Color(0xFF0284C7),
            topLeft = Offset(w * 0.78f, h * 0.3f),
            size = Size(w * 0.14f, h * 0.1f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
        )
        // Checkmarks
        drawLine(
            color = Color(0xFF0284C7),
            start = Offset(w * 0.76f, h * 0.48f),
            end = Offset(w * 0.92f, h * 0.48f),
            strokeWidth = 3f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color(0xFF0284C7),
            start = Offset(w * 0.76f, h * 0.62f),
            end = Offset(w * 0.92f, h * 0.62f),
            strokeWidth = 3f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color(0xFF0284C7),
            start = Offset(w * 0.76f, h * 0.76f),
            end = Offset(w * 0.88f, h * 0.76f),
            strokeWidth = 3f,
            cap = StrokeCap.Round
        )

        // Small plant on the left
        drawCircle(
            color = Color(0xFF22C55E),
            radius = w * 0.08f,
            center = Offset(w * 0.12f, h * 0.78f)
        )
    }
}
