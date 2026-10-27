package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.User
import com.example.data.storage.SavedAccount
import com.example.ui.theme.PrimaryNavy
import com.example.util.GoogleSignInButton
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

    // Consent checkbox state: "এই ডিভাইসে নম্বরটি মনে রাখুন" (never saves PIN!)
    var rememberThisPhone by remember { mutableStateOf(true) }

    // Whether user manually chose "Use another account / manual number input"
    var showManualInputOnly by remember { mutableStateOf(false) }

    // State for account being removed (confirmation dialog)
    var accountToRemove by remember { mutableStateOf<SavedAccount?>(null) }

    // Map to cache registered user info for saved accounts (name, role) for display
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

    // Modern clean Blue & White Color Palette
    val brandBlue = PrimaryNavy
    val lightSurface = Color(0xFFF8FAFC)
    val cardBorderColor = Color(0xFFE2E8F0)
    val textPrimary = Color(0xFF0F172A)
    val textSecondary = Color(0xFF64748B)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(lightSurface)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // =================================================================
            // 1. BRANDING HEADER (Logo + App Name + Subtitle)
            // =================================================================
            Surface(
                modifier = Modifier
                    .size(76.dp)
                    .testTag("app_logo_secret_trigger")
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        handleSecretTap()
                    },
                shape = RoundedCornerShape(20.dp),
                color = brandBlue,
                shadowElevation = 4.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Storefront,
                        contentDescription = "স্মার্ট বিজনেস",
                        tint = Color.White,
                        modifier = Modifier.size(42.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Smart Business Manager",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = brandBlue
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .testTag("app_title_secret_trigger")
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        handleSecretTap()
                    }
            )

            Text(
                text = "আধুনিক ব্যবসায়িক হিসাব ও সেলস সমাধান",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp,
                    color = textSecondary
                ),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            // =================================================================
            // 2. MAIN LOGIN CARD (Clean White Card on Soft Slate)
            // =================================================================
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                border = BorderStroke(1.dp, cardBorderColor)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    // Title & Description
                    Text(
                        text = "আপনার অ্যাকাউন্টে প্রবেশ করুন",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = textPrimary
                        )
                    )
                    Text(
                        text = "দোকানের হিসাব, বিক্রি ও বাকি পরিচালনায় লগইন করুন",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            color = textSecondary
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // -------------------------------------------------------------
                    // SAVED ACCOUNTS SECTION (If accounts are saved & not manual only)
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
                                    color = brandBlue
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
                                    .height(28.dp)
                                    .testTag("use_another_account_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("অন্য নম্বর", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
                                    color = if (isSelected) Color(0xFFEFF6FF) else Color(0xFFF8FAFC),
                                    border = if (isSelected) BorderStroke(1.5.dp, brandBlue) else BorderStroke(1.dp, Color(0xFFE2E8F0))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .background(if (isSelected) brandBlue else Color(0xFF94A3B8)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Person,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp)
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
                                                .size(32.dp)
                                                .testTag("remove_saved_account_${acc.phone}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "সংরক্ষিত নম্বর সরান",
                                                tint = Color(0xFF94A3B8),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // If user manually switched to another account, show a button to switch back
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
                                    color = brandBlue
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
                                    .height(28.dp)
                                    .testTag("show_saved_accounts_list_button")
                            ) {
                                Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("সংরক্ষিত তালিকা", fontSize = 11.sp)
                            }
                        }
                    }

                    // -------------------------------------------------------------
                    // MOBILE NUMBER INPUT
                    // -------------------------------------------------------------
                    OutlinedTextField(
                        value = phone,
                        onValueChange = {
                            phone = it
                            errorMessage = ""
                        },
                        label = { Text("মোবাইল নম্বর", fontSize = 13.sp) },
                        placeholder = { Text("01XXXXXXXXX", fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Phone, contentDescription = null, tint = brandBlue, modifier = Modifier.size(20.dp))
                        },
                        trailingIcon = {
                            if (phone.isNotBlank()) {
                                IconButton(onClick = { phone = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "মুছুন", modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = brandBlue,
                            unfocusedBorderColor = Color(0xFFCBD5E1),
                            focusedLabelColor = brandBlue
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_phone_input")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // -------------------------------------------------------------
                    // PIN OR OTP INPUT
                    // -------------------------------------------------------------
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isOtpMode) "লগইন পদ্ধতি: ওটিপি (OTP)" else "লগইন পদ্ধতি: গোপন পিন (PIN)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = textSecondary
                            )
                        )
                        TextButton(
                            onClick = {
                                isOtpMode = !isOtpMode
                                errorMessage = ""
                                otpSentNotice = ""
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier
                                .height(28.dp)
                                .testTag("toggle_otp_pin_mode_button")
                        ) {
                            Text(
                                text = if (isOtpMode) "পিন দিয়ে লগইন" else "ওটিপি দিয়ে লগইন",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = brandBlue
                            )
                        }
                    }

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
                                label = { Text("৪ ডিজিটের ওটিপি", fontSize = 13.sp) },
                                placeholder = { Text("১২৩৪", fontSize = 13.sp) },
                                leadingIcon = {
                                    Icon(Icons.Default.Lock, contentDescription = null, tint = brandBlue, modifier = Modifier.size(20.dp))
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(12.dp),
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
                                colors = ButtonDefaults.buttonColors(containerColor = brandBlue)
                            ) {
                                Text("ওটিপি পাঠান", fontSize = 12.sp)
                            }
                        }

                        if (otpSentNotice.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = otpSentNotice,
                                style = MaterialTheme.typography.bodySmall,
                                color = brandBlue,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        // PIN Input Field
                        OutlinedTextField(
                            value = pin,
                            onValueChange = {
                                pin = it
                                errorMessage = ""
                            },
                            label = { Text("গোপন পিন কোড", fontSize = 13.sp) },
                            placeholder = { Text("আপনার পিন কোড দিন", fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = brandBlue, modifier = Modifier.size(20.dp))
                            },
                            trailingIcon = {
                                IconButton(onClick = { showPin = !showPin }) {
                                    Icon(
                                        imageVector = if (showPin) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "পিন দেখুন",
                                        tint = textSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            },
                            singleLine = true,
                            visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = brandBlue,
                                unfocusedBorderColor = Color(0xFFCBD5E1),
                                focusedLabelColor = brandBlue
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("login_pin_input")
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // -------------------------------------------------------------
                    // "এই ডিভাইসে নম্বরটি মনে রাখুন" OPTION (Never saves PIN!)
                    // -------------------------------------------------------------
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { rememberThisPhone = !rememberThisPhone }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = rememberThisPhone,
                            onCheckedChange = { rememberThisPhone = it },
                            colors = CheckboxDefaults.colors(checkedColor = brandBlue),
                            modifier = Modifier.testTag("remember_phone_checkbox")
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = "এই ডিভাইসে নম্বরটি মনে রাখুন",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                ),
                                color = textPrimary
                            )
                            Text(
                                text = "নিরাপত্তার স্বার্থে পিন কোড সংরক্ষণ করা হয় না",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = textSecondary
                            )
                        }
                    }

                    // Mobile Login Error Message Banner
                    if (errorMessage.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
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
                                    Icons.Default.ErrorOutline,
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

                    Spacer(modifier = Modifier.height(18.dp))

                    // -------------------------------------------------------------
                    // 3. PRIMARY BLUE "প্রবেশ করুন" BUTTON
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
                            containerColor = brandBlue,
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
                            Text(
                                text = "প্রবেশ করুন",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // -------------------------------------------------------------
                    // 4. "নতুন দোকান রেজিস্ট্রেশন" BUTTON
                    // -------------------------------------------------------------
                    OutlinedButton(
                        onClick = onNavigateToRegistration,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("navigate_to_shop_registration_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.5.dp, brandBlue),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = brandBlue)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storefront,
                            contentDescription = null,
                            tint = brandBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "নতুন দোকান রেজিস্ট্রেশন — বিনামূল্যে",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = brandBlue
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // -------------------------------------------------------------
                    // "অথবা" DIVIDER
                    // -------------------------------------------------------------
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFFE2E8F0))
                        Text(
                            text = " অথবা ",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = textSecondary,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFFE2E8F0))
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // -------------------------------------------------------------
                    // 5. "Google অ্যাকাউন্ট দিয়ে সাইন ইন" BUTTON
                    // -------------------------------------------------------------
                    GoogleSignInButton(
                        isLoading = isGoogleLoading,
                        onClick = {
                            isGoogleLoading = true
                            errorMessage = ""
                            googleStatusMessage = ""
                            GoogleSignInHelper.launchGoogleSignIn(
                                context = context,
                                scope = coroutineScope,
                                onAuthSuccess = { fUser ->
                                    viewModel.handleGoogleSignInSuccess(fUser) { success, msg, role ->
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
                                    googleStatusMessage = "গুগল সাইন-ইন উইন্ডো বন্ধ করা হয়েছে।"
                                },
                                onError = { err ->
                                    isGoogleLoading = false
                                    googleStatusMessage = err
                                }
                            )
                        }
                    )

                    // -------------------------------------------------------------
                    // 6. GOOGLE SIGN-IN STATUS / ERROR MESSAGE
                    // -------------------------------------------------------------
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
                                    Icons.Default.Info,
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

                    Spacer(modifier = Modifier.height(8.dp))

                    // Diagnostic link for inspecting verified SHA-1/SHA-256
                    TextButton(
                        onClick = { showGoogleDiagDialog = true },
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .testTag("google_signin_diagnostic_button")
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(15.dp), tint = brandBlue)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Google Sign-In সহায়তা ও কনফিগারেশন",
                            fontSize = 12.sp,
                            color = brandBlue,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
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
                    tint = brandBlue,
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
                        color = brandBlue,
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

                    HorizontalDivider(color = Color(0xFFE2E8F0))

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
                        border = BorderStroke(1.dp, brandBlue)
                    ) {
                        Icon(Icons.Default.Storefront, contentDescription = null, tint = brandBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("নতুন দোকান খুলুন (প্রথম অ্যাডমিন)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = brandBlue)
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
                    colors = ButtonDefaults.buttonColors(containerColor = brandBlue),
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
