package com.example.ui.screens

import androidx.compose.animation.*
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: BusinessViewModel,
    onNavigateToAdminLogin: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    val savedAccounts by viewModel.savedAccounts.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    var isGoogleLoading by remember { mutableStateOf(false) }
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

    // Consent checkbox state: "এই ডিভাইসে নম্বরটি মনে রাখুন"
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

        // If currently entered phone is not in saved accounts and input is blank,
        // preselect the most recent saved account if available and not explicitly on manual mode
        if (phone.isBlank() && savedAccounts.isNotEmpty() && !showManualInputOnly) {
            phone = savedAccounts.first().phone
        }
    }

    // 5-Tap Secret Gesture Counter for Hidden Admin Login
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // App Logo Badge - Secret 5-tap target (No visual cue)
        Surface(
            modifier = Modifier
                .size(90.dp)
                .testTag("app_logo_secret_trigger")
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    handleSecretTap()
                },
            shape = RoundedCornerShape(20.dp),
            color = PrimaryNavy,
            shadowElevation = 6.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Storefront,
                    contentDescription = "স্মার্ট বিজনেস",
                    tint = Color.White,
                    modifier = Modifier.size(54.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // App Name Title - Also secret 5-tap target (No visual cue)
        Text(
            text = "Smart Business Manager",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
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
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        // MAIN LOGIN CARD
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = "লগইন করুন",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "আপনার মোবাইল নম্বর ও পিন কোড দিয়ে প্রবেশ করুন",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                Spacer(modifier = Modifier.height(18.dp))

                // SAVED ACCOUNTS SECTION (If accounts are saved and not solely manual mode)
                if (savedAccounts.isNotEmpty() && !showManualInputOnly) {
                    Text(
                        text = "সংরক্ষিত অ্যাকাউন্ট নির্বাচন করুন",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
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
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                },
                                tonalElevation = if (isSelected) 3.dp else 0.dp,
                                border = if (isSelected) {
                                    androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                                } else null
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // User / Account Avatar Icon
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outlineVariant
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = null,
                                            tint = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = displayName,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        // Partially masked phone for privacy
                                        Text(
                                            text = acc.maskedPhone,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    // Remove Number Button
                                    IconButton(
                                        onClick = { accountToRemove = acc },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .testTag("remove_saved_account_${acc.phone}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "সংরক্ষিত নম্বর সরান",
                                            tint = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Option to enter a different number
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                showManualInputOnly = true
                                phone = ""
                                errorMessage = ""
                            },
                            modifier = Modifier.testTag("use_another_account_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("অন্য অ্যাকাউন্ট ব্যবহার করুন", fontSize = 13.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                }

                // If user pressed "Use another account", provide button to switch back to saved list
                if (showManualInputOnly && savedAccounts.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "নতুন / অন্য মোবাইল নম্বর",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(
                            onClick = {
                                showManualInputOnly = false
                                if (savedAccounts.isNotEmpty()) {
                                    phone = savedAccounts.first().phone
                                }
                            },
                            modifier = Modifier.testTag("show_saved_accounts_list_button")
                        ) {
                            Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("সংরক্ষিত তালিকা দেখুন", fontSize = 12.sp)
                        }
                    }
                }

                // Phone Input Field
                OutlinedTextField(
                    value = phone,
                    onValueChange = {
                        phone = it
                        errorMessage = ""
                    },
                    label = { Text("মোবাইল নম্বর") },
                    placeholder = { Text("01XXXXXXXXX") },
                    leadingIcon = {
                        Icon(Icons.Default.Phone, contentDescription = null)
                    },
                    trailingIcon = {
                        if (phone.isNotBlank()) {
                            IconButton(onClick = { phone = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "পরিষ্কার করুন")
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("login_phone_input")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // PIN Input Field (Always required, never stored!)
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        pin = it
                        errorMessage = ""
                    },
                    label = { Text("গোপন পিন কোড") },
                    placeholder = { Text("আপনার পিন কোড দিন") },
                    leadingIcon = {
                        Icon(Icons.Default.Lock, contentDescription = null)
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
                        .testTag("login_pin_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                // "Remember Mobile Number on this device" Consent Checkbox
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { rememberThisPhone = !rememberThisPhone }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = rememberThisPhone,
                        onCheckedChange = { rememberThisPhone = it },
                        modifier = Modifier.testTag("remember_phone_checkbox")
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "এই ডিভাইসে নম্বরটি মনে রাখুন",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "নিরাপত্তার স্বার্থে পিন কোড কখনই সংরক্ষণ করা হয় না",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            fontSize = 11.sp
                        )
                    }
                }

                if (errorMessage.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Submit Button
                Button(
                    onClick = {
                        val trimmedPhone = phone.trim()
                        val trimmedPin = pin.trim()
                        isLoading = true
                        errorMessage = ""

                        viewModel.login(trimmedPhone, trimmedPin) { success, msg ->
                            isLoading = false
                            if (success) {
                                // Manage device-local phone retention based on user consent
                                if (rememberThisPhone) {
                                    viewModel.saveRememberedPhone(trimmedPhone)
                                } else {
                                    // If user unchecked consent for this number, remove if previously saved
                                    viewModel.removeRememberedPhone(trimmedPhone)
                                }
                                onLoginSuccess()
                            } else {
                                errorMessage = msg
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("login_submit_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = "প্রবেশ করুন",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // OR Divider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = " অথবা ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Google Sign-In Button
                GoogleSignInButton(
                    isLoading = isGoogleLoading,
                    onClick = {
                        isGoogleLoading = true
                        errorMessage = ""
                        GoogleSignInHelper.launchGoogleSignIn(
                            context = context,
                            scope = coroutineScope,
                            onAuthSuccess = { fUser ->
                                viewModel.handleGoogleSignInSuccess(fUser) { success, msg, role ->
                                    isGoogleLoading = false
                                    if (success) {
                                        onLoginSuccess()
                                    } else {
                                        errorMessage = msg
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
                                // User dismissed account picker - reset loading without error
                            },
                            onError = { err ->
                                isGoogleLoading = false
                                errorMessage = err
                            }
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }

    // Account Linking Dialog (when an authenticated Google user needs local staff/admin verification)
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
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "গুগল অ্যাকাউন্ট সংযুক্তকরণ",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "সাইন-ইনকৃত অ্যাকাউন্ট: ${fUser.email ?: fUser.displayName ?: "গুগল আইডি"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "নিরাপত্তার স্বার্থে অ্যাডমিনের ক্লাউড বিজনেস কোড, নিবন্ধিত মোবাইল নম্বর ও পিন কোড দিয়ে অ্যাকাউন্টটি অনুমোদন করুন:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = linkBusinessIdInput,
                        onValueChange = { linkBusinessIdInput = it; linkErrorMessage = "" },
                        label = { Text("ক্লাউড বিজনেস কোড *") },
                        placeholder = { Text("অ্যাডমিনের বিজনেস কোড পেস্ট করুন") },
                        singleLine = true,
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

                    OutlinedTextField(
                        value = linkPhoneInput,
                        onValueChange = { linkPhoneInput = it; linkErrorMessage = "" },
                        label = { Text("নিবন্ধিত মোবাইল নম্বর *") },
                        placeholder = { Text("01XXXXXXXXX") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth().testTag("link_phone_input")
                    )

                    OutlinedTextField(
                        value = linkPinInput,
                        onValueChange = { linkPinInput = it; linkErrorMessage = "" },
                        label = { Text("গোপন পিন কোড *") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("link_pin_input")
                    )

                    if (linkErrorMessage.isNotBlank()) {
                        Text(
                            text = linkErrorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isLinkingLoading = true
                        linkErrorMessage = ""
                        val cleanBiz = linkBusinessIdInput.trim()
                        if (cleanBiz.isNotBlank()) {
                            viewModel.linkGoogleAccountWithBusiness(
                                firebaseUser = fUser,
                                businessId = cleanBiz,
                                phone = linkPhoneInput.trim(),
                                pin = linkPinInput.trim()
                            ) { success, msg, role ->
                                isLinkingLoading = false
                                if (success) {
                                    pendingGoogleUser = null
                                    onLoginSuccess()
                                } else {
                                    linkErrorMessage = msg
                                }
                            }
                        } else {
                            viewModel.linkGoogleAccountWithPin(
                                firebaseUser = fUser,
                                phone = linkPhoneInput.trim(),
                                pin = linkPinInput.trim()
                            ) { success, msg, role ->
                                isLinkingLoading = false
                                if (success) {
                                    pendingGoogleUser = null
                                    onLoginSuccess()
                                } else {
                                    linkErrorMessage = msg
                                }
                            }
                        }
                    },
                    enabled = !isLinkingLoading,
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
                    enabled = !isLinkingLoading
                ) {
                    Text("বাতিল")
                }
            }
        )
    }

    // Confirmation dialog for removing a saved account
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
