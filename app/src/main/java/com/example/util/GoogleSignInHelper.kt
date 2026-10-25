package com.example.util

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    // Verified using: apksigner verify --print-certs app-debug.apk and keytool -list -v -keystore debug.keystore
    const val APK_SIGNING_SHA1 = "EC:9F:34:AD:BE:82:56:47:5C:CC:96:62:B2:19:02:1E:99:A2:00:19"
    const val APK_SIGNING_SHA256 = "15:78:6F:B9:73:B6:5F:42:EC:2B:2D:88:40:10:6A:03:7A:C5:F9:8A:A0:98:B5:B2:E6:EC:F5:92:3C:BB:2F:90"
    const val DEFAULT_WEB_CLIENT_ID = "484040397249-h2tfr5q8pgu65ncl9fgaslcbdsvj9kkj.apps.googleusercontent.com"
    const val APPLICATION_ID = "com.aistudio.smartbiz.mgr"

    @Suppress("DEPRECATION")
    fun getSigningSha1(context: Context): String {
        return try {
            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                )
                packageInfo.signingInfo?.apkContentsSigners
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
                packageInfo.signingInfo?.apkContentsSigners
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
            var resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
            if (resId == 0) {
                resId = context.resources.getIdentifier("default_web_client_id", "string", "com.example")
            }
            if (resId != 0) context.getString(resId) else DEFAULT_WEB_CLIENT_ID
        } catch (_: Exception) {
            DEFAULT_WEB_CLIENT_ID
        }
    }

    fun launchGoogleSignIn(
        context: Context,
        scope: CoroutineScope,
        onAuthSuccess: (FirebaseUser) -> Unit,
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
        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()

        scope.launch {
            try {
                val result = credentialManager.getCredential(activity, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
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
                    onError("অপ্রত্যাশিত ক্রেডেনশিয়াল ফরম্যাট: $unexpectedType (type: ${credential.type})")
                }
            } catch (e: GetCredentialCancellationException) {
                val exMsg = e.message ?: "Operation was canceled"
                Log.w(TAG, "GetCredentialCancellationException: $exMsg", e)
                onError("গুগল সাইন-ইন উইন্ডো বন্ধ বা বাতিল হয়েছে।\n" +
                        "ত্রুটি বিবরণ: [${e.javaClass.simpleName}] $exMsg\n\n" +
                        "লক্ষণীয়: সাইন-ইন ডায়ালগ না এসে সাথে সাথে বন্ধ হলে Firebase Console-এ SHA-1 বা Package Mismatch রয়েছে।\n" +
                        "আসল প্যাকেজ: ${context.packageName}\n" +
                        "আসল SHA-1: $currentSha1")
            } catch (e: NoCredentialException) {
                val exMsg = e.message ?: "No account credentials"
                Log.w(TAG, "NoCredentialException: $exMsg", e)
                onError("এই ডিভাইসে কোনো নিবন্ধিত গুগল অ্যাকাউন্ট পাওয়া যায়নি।\nত্রুটি বিবরণ: $exMsg\nঅনুগ্রহ করে ফোনের Settings > Accounts-এ গুগল অ্যাকাউন্ট যোগ করুন।")
            } catch (e: GetCredentialException) {
                val exType = e.type
                val exMsg = e.message ?: ""
                val exCause = e.cause?.toString() ?: "None"
                Log.e(TAG, "GetCredentialException: type=$exType, message=$exMsg, cause=$exCause", e)
                val isDevError = exMsg.contains("10") || exMsg.contains("DEVELOPER_ERROR", ignoreCase = true) || exType.contains("DEVELOPER_ERROR", ignoreCase = true)
                val isCanceled = exMsg.contains("16") || exMsg.contains("CANCELLED", ignoreCase = true)
                val summary = when {
                    isDevError -> "Developer Error (Code 10: SHA-1 / Package Mismatch)"
                    isCanceled -> "Operation Canceled (Code 16 / OAuth Client Not Found)"
                    else -> "Credential Manager Exception ($exType)"
                }
                onError("গুগল সাইন-ইন ব্যর্থ হয়েছে: $summary\n" +
                        "প্রকৃত বার্তা: $exMsg\n" +
                        "Type: $exType\n" +
                        "Cause: $exCause\n\n" +
                        "Firebase Console-এ প্রয়োজনীয় কনফিগারেশন:\n" +
                        "• Package Name: ${context.packageName}\n" +
                        "• আসল SHA-1: $currentSha1\n" +
                        "• আসল SHA-256: $currentSha256\n" +
                        "• Web Client ID: $clientId")
            } catch (e: Exception) {
                val exClass = e.javaClass.simpleName
                val exMsg = e.localizedMessage ?: e.message ?: "Unknown"
                val exCause = e.cause?.toString() ?: "None"
                Log.e(TAG, "General Authentication Exception: $exClass: $exMsg, cause=$exCause", e)
                if (exMsg.contains("operation-not-allowed", ignoreCase = true) || exMsg.contains("disabled", ignoreCase = true)) {
                    onError("Firebase Authentication-এ Google Sign-in সক্রিয় করা নেই!\n" +
                            "Firebase Console > Authentication > Sign-in method-এ যান, Google চালু করুন এবং Support Email নির্বাচন করে সংরক্ষণ করুন।\n" +
                            "বিস্তারিত: [$exClass] $exMsg")
                } else {
                    onError("সাইন-ইন ব্যর্থ হয়েছে: [$exClass]\n" +
                            "বার্তা: $exMsg\n" +
                            "Cause: $exCause\n\n" +
                            "ডিভাইস তথ্য: Package=${context.packageName}, SHA-1=$currentSha1")
                }
            }
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
