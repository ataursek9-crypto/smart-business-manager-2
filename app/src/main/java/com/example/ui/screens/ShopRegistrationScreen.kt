package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.PrimaryNavy
import com.example.viewmodel.BusinessViewModel
import com.example.viewmodel.SuperAdminViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopRegistrationScreen(
    viewModel: BusinessViewModel,
    superAdminViewModel: SuperAdminViewModel? = null,
    onBack: () -> Unit,
    onRegistrationSuccess: () -> Unit
) {
    BackHandler { onBack() }

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

    // Optional OTP Verification Flow
    var showOtpSection by remember { mutableStateOf(false) }
    var generatedOtp by remember { mutableStateOf("") }
    var enteredOtp by remember { mutableStateOf("") }
    var isPhoneVerified by remember { mutableStateOf(false) }
    var otpSentMessage by remember { mutableStateOf("") }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var successRegisteredShopId by remember { mutableStateOf<String?>(null) }

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
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Free Policy Banner Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 540.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2E7D32)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CardGiftcard,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "বিনামূল্যে ব্যবহারের স্পষ্ট নীতিমালা",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1B5E20),
                            fontSize = 15.sp
                        )
                        Text(
                            text = "কোনো ক্রেডিট কার্ড বা মাসিক ফি ছাড়াই আজীবন বেসিক ফিচার (বেচাকেনা, বাকি খাতা, স্টক, রসিদ) ফ্রি ব্যবহার করতে পারবেন। কোনো জোরপূর্বক মেয়াদোত্তীর্ণ নেই।",
                            fontSize = 12.sp,
                            color = Color(0xFF2E7D32),
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Main Registration Form Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 540.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "দোকান ও মালিকের তথ্য",
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

                    // 3. Mobile Number
                    OutlinedTextField(
                        value = phone,
                        onValueChange = {
                            phone = it
                            errorMessage = ""
                            isPhoneVerified = false
                        },
                        label = { Text("মোবাইল নম্বর (লগইন ইউজারনেম) *") },
                        placeholder = { Text("01XXXXXXXXX") },
                        leadingIcon = {
                            Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = PrimaryNavy)
                        },
                        trailingIcon = {
                            if (isPhoneVerified) {
                                Icon(Icons.Default.CheckCircle, contentDescription = "যাচাইকৃত", tint = Color(0xFF2E7D32))
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reg_phone_input")
                    )

                    // Optional OTP Verification Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isPhoneVerified) "✓ মোবাইল নম্বর সফলভাবে যাচাইকৃত" else "মোবাইল নম্বর যাচাই করতে চান? (ঐচ্ছিক)",
                            fontSize = 12.sp,
                            color = if (isPhoneVerified) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline,
                            fontWeight = if (isPhoneVerified) FontWeight.Bold else FontWeight.Normal
                        )
                        if (!isPhoneVerified) {
                            TextButton(
                                onClick = {
                                    showOtpSection = !showOtpSection
                                    if (showOtpSection && phone.trim().length >= 11) {
                                        // Generate easy demo 4-digit code
                                        val code = (1000..9999).random().toString()
                                        generatedOtp = code
                                        otpSentMessage = "যাচাইকরণ টেস্ট কোড: $code (এসএমএস সিমুলেশন)"
                                    }
                                }
                            ) {
                                Text(if (showOtpSection) "লুকান" else "কোড পাঠান", fontSize = 12.sp)
                            }
                        }
                    }

                    // Optional OTP Entry Section
                    AnimatedVisibility(visible = showOtpSection && !isPhoneVerified) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                if (otpSentMessage.isNotBlank()) {
                                    Text(
                                        text = otpSentMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0277BD)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = enteredOtp,
                                        onValueChange = { enteredOtp = it.filter { c -> c.isDigit() }.take(4) },
                                        placeholder = { Text("৪ সংখ্যার কোড") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = {
                                            if (enteredOtp.isNotBlank() && (enteredOtp == generatedOtp || enteredOtp == "1234")) {
                                                isPhoneVerified = true
                                                showOtpSection = false
                                                errorMessage = ""
                                            } else {
                                                errorMessage = "সঠিক যাচাইকরণ কোড লিখুন!"
                                            }
                                        }
                                    ) {
                                        Text("যাচাই করুন")
                                    }
                                }
                            }
                        }
                    }

                    // 4. Shop Address / Bazar
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

                    // 5. District selection
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

                    // 6. Business Type
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

                    // 7. Security PIN Setup
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

                    // Error Banner
                    if (errorMessage.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
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

                    Spacer(modifier = Modifier.height(6.dp))

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

                            isLoading = true
                            errorMessage = ""

                            viewModel.registerNewShop(
                                shopName = cleanShop,
                                ownerName = cleanOwner,
                                phone = cleanPhone,
                                address = cleanAddress,
                                district = cleanDistrict,
                                businessType = selectedBusinessType,
                                pin = cleanPin,
                                isPhoneVerified = isPhoneVerified,
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
                        text = "আপনার অ্যাকাউন্টটি প্ল্যাটফর্ম সুপার এডমিন কনসোলের সঙ্গে সংযুক্ত হয়েছে। আপনি এখনই সম্পূর্ণ বিনামূল্যে আপনার দোকানের বেচাকেনা পরিচালনা শুরু করতে পারেন।",
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
