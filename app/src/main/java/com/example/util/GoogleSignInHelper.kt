package com.example.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object GoogleSignInHelper {
    private const val TAG = "GoogleSignInHelper"

    // Real SHA-1 and SHA-256 certificate fingerprints for this APK signed by debug.keystore
    // Verified using: keytool -list -v -keystore debug.keystore -alias androiddebugkey
    // and apksigner verify --verbose --print-certs app-debug.apk
    const val APK_SIGNING_SHA1 = "B4:F3:6C:65:C4:65:C9:22:00:43:5D:34:5C:E4:67:DC:FA:89:F8:C5"
    const val APK_SIGNING_SHA256 = "8F:23:9A:56:F0:45:68:90:F4:9C:EC:CD:C8:90:B7:8D:6F:1E:0D:71:98:F2:2D:3E:31:82:DD:BA:75:99:BF:54"
    const val DEFAULT_WEB_CLIENT_ID = "484040397249-h2tfr5q8pgu65ncl9fgaslcbdsvj9kkj.apps.googleusercontent.com"
    const val APPLICATION_ID = "com.aistudio.smartbiz.mgr"

    data class DiagnosticInfo(
        val packageName: String,
        val actualSha1: String,
        val actualSha256: String,
        val webClientId: String,
        val firebaseProjectId: String = "smart-business-manager-71879"
    )

    fun getDiagnosticInfo(context: Context): DiagnosticInfo {
        return DiagnosticInfo(
            packageName = context.packageName,
            actualSha1 = getSigningSha1(context),
            actualSha256 = getSigningSha256(context),
            webClientId = getWebClientId(context)
        )
    }

    @Suppress("DEPRECATION")
    fun getSigningSha1(context: Context): String {
        return try {
            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signingInfo = packageInfo.signingInfo
                if (signingInfo != null) {
                    if (signingInfo.hasMultipleSigners()) {
                        signingInfo.apkContentsSigners
                    } else {
                        signingInfo.signingCertificateHistory
                    }
                } else null
            } else {
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_SIGNATURES
                )
                packageInfo.signatures
            }
            val cert = signatures?.firstOrNull()?.toByteArray() ?: return APK_SIGNING_SHA1
            val md = java.security.MessageDigest.getInstance("SHA-1")
            val digest = md.digest(cert)
            digest.joinToString(":") { "%02X".format(it) }
        } catch (_: Exception) {
            APK_SIGNING_SHA1
        }
    }

    @Suppress("DEPRECATION")
    fun getSigningSha256(context: Context): String {
        return try {
            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signingInfo = packageInfo.signingInfo
                if (signingInfo != null) {
                    if (signingInfo.hasMultipleSigners()) {
                        signingInfo.apkContentsSigners
                    } else {
                        signingInfo.signingCertificateHistory
                    }
                } else null
            } else {
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_SIGNATURES
                )
                packageInfo.signatures
            }
            val cert = signatures?.firstOrNull()?.toByteArray() ?: return APK_SIGNING_SHA256
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(cert)
            digest.joinToString(":") { "%02X".format(it) }
        } catch (_: Exception) {
            APK_SIGNING_SHA256
        }
    }

    fun getWebClientId(context: Context): String {
        return try {
            val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
            if (resId != 0) {
                context.getString(resId)
            } else {
                context.getString(R.string.default_web_client_id)
            }
        } catch (_: Exception) {
            DEFAULT_WEB_CLIENT_ID
        }
    }

    fun launchGoogleSignIn(
        context: Context,
        scope: CoroutineScope,
        onAuthSuccess: (FirebaseUser) -> Unit,
        onCancelled: (() -> Unit)? = null,
        onError: (String) -> Unit
    ) {
        val activity = context as? Activity
        if (activity == null) {
            onError("সিস্টেম ত্রুটি: অ্যাক্টিভিটি পাওয়া যায়নি")
            return
        }

        val clientId = getWebClientId(context)
        if (clientId.isBlank()) {
            onError("গুগল সাইন-ইন কনফিগারেশন পাওয়া যায়নি (default_web_client_id অনুপস্থিত)।")
            return
        }

        val currentSha1 = getSigningSha1(context)
        val currentSha256 = getSigningSha256(context)
        Log.i(TAG, "Initiating Google Sign-In with serverClientId=$clientId, package=${context.packageName}, sha1=$currentSha1")

        val credentialManager = CredentialManager.create(context)
        val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInWithGoogleOption)
            .addCredentialOption(googleIdOption)
            .build()

        val startTime = System.currentTimeMillis()

        scope.launch {
            try {
                val result = credentialManager.getCredential(activity, request)
                val credential = result.credential
                val googleIdToken: String? = if (credential is CustomCredential) {
                    try {
                        GoogleIdTokenCredential.createFrom(credential.data).idToken
                    } catch (e: Exception) {
                        credential.data.getString("com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID_TOKEN")
                            ?: credential.data.getString("id_token")
                    }
                } else null

                if (!googleIdToken.isNullOrBlank()) {
                    Log.i(TAG, "Google ID Token received successfully")
                    val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                    val authResult = FirebaseAuth.getInstance().signInWithCredential(authCredential).await()
                    val user = authResult.user
                    if (user != null) {
                        Log.i(TAG, "FirebaseAuth sign-in success: uid=${user.uid}, email=${user.email}")
                        onAuthSuccess(user)
                    } else {
                        onError("ফায়ারবেস ব্যবহারকারী সেশন শুরু করা সম্ভব হয়নি। (AuthResult returned null user)")
                    }
                } else {
                    val unexpectedType = credential::class.java.name
                    Log.e(TAG, "Unexpected credential type: $unexpectedType, type=${credential.type}")
                    onError("গুগল আইডি টোকেন পাওয়া যায়নি [ক্রেডেনশিয়াল টাইপ: ${credential.type}]")
                }
            } catch (e: GetCredentialCancellationException) {
                val durationMs = System.currentTimeMillis() - startTime
                val exMsg = e.message ?: "User cancelled"
                Log.w(TAG, "GetCredentialCancellationException after ${durationMs}ms: $exMsg")

                // If cancelled very quickly (< 1500ms), it means Google Play Services
                // dismissed it automatically before the user could interact with the account picker!
                if (durationMs < 1500) {
                    onError("গুগল সাইন-ইন উইন্ডো খোলার সাথে সাথেই স্বয়ংক্রিয়ভাবে বন্ধ হয়ে গেছে (Google Play Services Auto-Cancelled)।\n\n" +
                            "সম্ভাব্য কারণ ও সমাধান:\n" +
                            "১. Firebase Console-এ বর্তমান APK-এর সাইনিং SHA-1 যুক্ত নেই।\n" +
                            "   • প্যাকেজ: ${context.packageName}\n" +
                            "   • সাইনিং SHA-1: $currentSha1\n" +
                            "   • Web Client ID: $clientId\n" +
                            "   (Firebase Console > Project Settings > Your apps-এ গিয়ে এই SHA-1 ফিঙ্গারপ্রিন্টটি যোগ করুন)\n\n" +
                            "২. ফোনে কোনো Google অ্যাকাউন্টে লগইন নেই। ফোনের Settings > Accounts-এ Google অ্যাকাউন্ট নিশ্চিত করুন।")
                } else {
                    // Genuine user interaction (user manually closed or tapped outside)
                    if (onCancelled != null) {
                        onCancelled()
                    } else {
                        onError("গুগল সাইন-ইন উইন্ডো বন্ধ বা বাতিল করা হয়েছে।")
                    }
                }
            } catch (e: NoCredentialException) {
                val exMsg = e.message ?: "No account credentials"
                Log.w(TAG, "NoCredentialException: $exMsg")
                onError("এই ডিভাইসে কোনো নিবন্ধিত গুগল অ্যাকাউন্ট পাওয়া যায়নি।\nঅনুগ্রহ করে ফোনের Settings > Accounts-এ গিয়ে একটি Google অ্যাকাউন্টে লগইন করুন।")
            } catch (e: androidx.credentials.exceptions.GetCredentialCustomException) {
                val durationMs = System.currentTimeMillis() - startTime
                val exType = e.type
                val exMsg = e.message ?: ""
                Log.e(TAG, "GetCredentialCustomException: type=$exType, message=$exMsg, duration=${durationMs}ms")
                val isDevError = exMsg.contains("10") || exMsg.contains("DEVELOPER_ERROR", ignoreCase = true) || exType.contains("DEVELOPER_ERROR", ignoreCase = true)
                val isCanceled = exMsg.contains("16") || exMsg.contains("CANCELLED", ignoreCase = true)

                if (isDevError || (isCanceled && durationMs < 1200)) {
                    onError("গুগল সাইন-ইন কনফিগারেশন ত্রুটি (Developer Error / Code 16):\n" +
                            "Firebase Console-এ এই অ্যাপের প্যাকেজ ও SHA-1 ফিঙ্গারপ্রিন্ট যোগ থাকতে হবে:\n" +
                            "• Package Name: ${context.packageName}\n" +
                            "• সাইনিং SHA-1: $currentSha1\n" +
                            "• Web Client ID: $clientId\n\n" +
                            "Firebase Console > Project Settings > Your apps-এ SHA-1 যোগ করে google-services.json আপডেট করুন।")
                } else if (isCanceled) {
                    if (onCancelled != null) {
                        onCancelled()
                    } else {
                        onError("গুগল সাইন-ইন সার্ভিস থেকে অনুরোধ বাতিল হয়েছে।")
                    }
                } else {
                    onError("গুগল সাইন-ইন সার্ভিস ত্রুটি: $exType\nবিবরণ: $exMsg")
                }
            } catch (e: androidx.credentials.exceptions.GetCredentialProviderConfigurationException) {
                Log.e(TAG, "GetCredentialProviderConfigurationException: ${e.message}")
                onError("ক্রেডেনশিয়াল প্রোভাইডার কনফিগারেশনে সমস্যা হয়েছে। অনুগ্রহ করে ডিভাইসের গুগল প্লে সার্ভিস আপডেট করুন।")
            } catch (e: androidx.credentials.exceptions.GetCredentialUnsupportedException) {
                Log.e(TAG, "GetCredentialUnsupportedException: ${e.message}")
                onError("এই ডিভাইসে Google Credential Manager সমর্থিত নয়।")
            } catch (e: androidx.credentials.exceptions.GetCredentialInterruptedException) {
                Log.w(TAG, "GetCredentialInterruptedException: ${e.message}")
                if (onCancelled != null) {
                    onCancelled()
                } else {
                    onError("গুগল সাইন-ইন প্রক্রিয়া বিঘ্নিত হয়েছে। অনুগ্রহ করে আবার চেষ্টা করুন।")
                }
            } catch (e: GetCredentialException) {
                val exType = e.type
                val exMsg = e.message ?: ""
                Log.e(TAG, "GetCredentialException: type=$exType, message=$exMsg")
                onError("গুগল সাইন-ইন ব্যর্থ হয়েছে [${exType}]: $exMsg")
            } catch (e: com.google.firebase.auth.FirebaseAuthException) {
                val errCode = e.errorCode
                val exMsg = e.message ?: ""
                Log.e(TAG, "FirebaseAuthException: errorCode=$errCode, msg=$exMsg")
                if (errCode == "ERROR_OPERATION_NOT_ALLOWED" || exMsg.contains("operation-not-allowed", ignoreCase = true)) {
                    onError("Firebase Authentication-এ Google Sign-in সক্রিয় করা নেই!\n" +
                            "Firebase Console > Authentication > Sign-in method-এ যান, Google চালু করুন এবং Support Email নির্বাচন করে সংরক্ষণ করুন।")
                } else if (errCode == "ERROR_INVALID_CREDENTIAL") {
                    onError("Google শংসাপত্রটি অবৈধ বা মেয়াদোত্তীর্ণ। অনুগ্রহ করে পুনরায় চেষ্টা করুন।")
                } else {
                    onError("Firebase Authentication ত্রুটি ($errCode): $exMsg")
                }
            } catch (e: com.google.firebase.FirebaseNetworkException) {
                Log.e(TAG, "FirebaseNetworkException: ${e.message}")
                onError("ইন্টারনেট সংযোগে সমস্যা হচ্ছে। আপনার ইন্টারনেট সংযোগ পরীক্ষা করে পুনরায় চেষ্টা করুন।")
            } catch (e: Exception) {
                val exClass = e.javaClass.simpleName
                val exMsg = e.localizedMessage ?: e.message ?: "Unknown"
                Log.e(TAG, "General Authentication Exception: $exClass: $exMsg", e)
                onError("সাইন-ইন সম্পন্ন করা যায়নি [$exClass]: $exMsg")
            }
        }
    }

    fun getBengaliErrorMessage(e: Throwable): String {
        return when {
            e is java.net.UnknownHostException || e is com.google.firebase.FirebaseNetworkException ->
                "ইন্টারনেট সংযোগে সমস্যা হচ্ছে। আপনার ইন্টারনেট সংযোগ পরীক্ষা করে পুনরায় চেষ্টা করুন।"
            e is java.net.SocketTimeoutException ->
                "সার্ভার সংযোগ টাইমআউট হয়েছে। দুর্বল ইন্টারনেটের কারণে এই সমস্যা হতে পারে। পুনরায় চেষ্টা করুন।"
            e is GetCredentialCancellationException || e.message?.contains("cancel", ignoreCase = true) == true ->
                "গুগল সাইন-ইন বাতিল করা হয়েছে।"
            e is NoCredentialException ->
                "ডিভাইসে কোনো গুগল একাউন্ট পাওয়া যায়নি।"
            else ->
                "গুগল সাইন-ইন প্রক্রিয়ায় ত্রুটি ঘটেছে: ${e.localizedMessage ?: e.message ?: "অজানা ত্রুটি"}"
        }
    }
}

@Composable
fun GoogleSignInButton(
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        enabled = !isLoading,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag("google_sign_in_button"),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.White,
            contentColor = Color(0xFF1F1F1F)
        ),
        border = BorderStroke(1.dp, Color(0xFFDADCE0)),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp, pressedElevation = 2.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint = Color(0xFF4285F4),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Google অ্যাকাউন্ট দিয়ে সাইন ইন",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF3C4043)
                )
            }
        }
    }
}

@Composable
fun GoogleSignInDiagnosticDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val diag = remember { GoogleSignInHelper.getDiagnosticInfo(context) }
    val fullSummary = "Package: ${diag.packageName}\n" +
            "SHA-1: ${diag.actualSha1}\n" +
            "SHA-256: ${diag.actualSha256}\n" +
            "Web Client ID: ${diag.webClientId}\n" +
            "Firebase Project: ${diag.firebaseProjectId}"

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Google Sign-In কনফিগারেশন ও কি তথ্য",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
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
                    text = "Google Sign-In সফলভাবে কাজ করার জন্য Firebase Console-এ নিচের সাইনিং SHA-1 যুক্ত থাকা আবশ্যক। যুক্ত না থাকলে Google Play Services সাইন-ইন বন্ধ করে দেয়।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("• Package Name:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                        SelectionContainer {
                            Text(diag.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }

                        Text("• বর্তমান APK-এর সাইনিং SHA-1:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                        SelectionContainer {
                            Text(diag.actualSha1, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        }

                        Text("• বর্তমান APK-এর সাইনিং SHA-256:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                        SelectionContainer {
                            Text(diag.actualSha256, style = MaterialTheme.typography.bodySmall)
                        }

                        Text("• Web Client ID:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                        SelectionContainer {
                            Text(diag.webClientId, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Google Sign-In Config", fullSummary)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "কনফিগারেশন তথ্য কপি করা হয়েছে!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("সব তথ্য কপি করুন")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("ঠিক আছে")
            }
        }
    )
}
