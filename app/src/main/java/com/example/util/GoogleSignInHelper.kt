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
    // Verified using: keytool -list -v -keystore debug.keystore -alias androiddebugkey
    const val APK_SIGNING_SHA1 = "72:FA:3A:98:28:0A:44:8B:35:B9:9D:19:3B:93:8B:39:35:F8:A9:E9"
    const val APK_SIGNING_SHA256 = "52:CC:54:2D:9B:DD:85:CE:41:96:0A:E6:C0:C8:90:83:9E:DE:3D:1F:BB:A0:BB:45:19:02:53:80:19:BF:15:D8"
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
            var resId = context.resources.getIdentifier("default_web_client_id", "string", "com.example")
            if (resId == 0) {
                resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
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
                        Log.i(TAG, "FirebaseAuth sign-in success: uid=${user.uid}")
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
                // User intentionally cancelled, closed the bottom sheet, or tapped outside.
                // This is a standard user interaction and NOT an application crash or Firebase error.
                val exMsg = e.message ?: "User cancelled"
                Log.d(TAG, "Google Sign-In cancelled by user: $exMsg")
                if (onCancelled != null) {
                    onCancelled()
                } else {
                    onError("গুগল সাইন-ইন উইন্ডো বন্ধ বা বাতিল করা হয়েছে।")
                }
            } catch (e: NoCredentialException) {
                val exMsg = e.message ?: "No account credentials"
                Log.w(TAG, "NoCredentialException: $exMsg")
                onError("এই ডিভাইসে কোনো নিবন্ধিত গুগল অ্যাকাউন্ট পাওয়া যায়নি।\nঅনুগ্রহ করে ফোনের Settings > Accounts-এ গিয়ে একটি Google অ্যাকাউন্টে লগইন করুন।")
            } catch (e: androidx.credentials.exceptions.GetCredentialCustomException) {
                val exType = e.type
                val exMsg = e.message ?: ""
                Log.e(TAG, "GetCredentialCustomException: type=$exType, message=$exMsg")
                val isDevError = exMsg.contains("10") || exMsg.contains("DEVELOPER_ERROR", ignoreCase = true) || exType.contains("DEVELOPER_ERROR", ignoreCase = true)
                val isCanceled = exMsg.contains("16") || exMsg.contains("CANCELLED", ignoreCase = true)

                if (isDevError) {
                    onError("গুগল সাইন-ইন কনফিগারেশন ত্রুটি (Developer Error 10):\n" +
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
