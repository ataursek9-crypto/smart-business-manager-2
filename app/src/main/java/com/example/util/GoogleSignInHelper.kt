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

    fun getWebClientId(context: Context): String? {
        return try {
            val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
            if (resId != 0) context.getString(resId) else null
        } catch (_: Exception) {
            null
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
        if (clientId.isNullOrBlank()) {
            onError("গুগল সাইন-ইন কনফিগারেশন পাওয়া যায়নি (default_web_client_id অনুপস্থিত)।")
            return
        }

        val credentialManager = CredentialManager.create(context)
        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()

        scope.launch {
            try {
                val result = credentialManager.getCredential(activity, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                    val authResult = FirebaseAuth.getInstance().signInWithCredential(authCredential).await()
                    val user = authResult.user
                    if (user != null) {
                        onAuthSuccess(user)
                    } else {
                        onError("ফায়ারবেস ব্যবহারকারী সেশন শুরু করা সম্ভব হয়নি।")
                    }
                } else {
                    onError("অপ্রত্যাশিত ক্রেডেনশিয়াল ফরম্যাট পাওয়া গেছে।")
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w(TAG, "Google Sign-In cancelled: ${e.message}")
                onError("গুগল সাইন-ইন প্রক্রিয়াটি বাতিল করা হয়েছে।")
            } catch (e: NoCredentialException) {
                Log.w(TAG, "No credentials: ${e.message}")
                onError("এই ডিভাইসে কোনো নিবন্ধিত গুগল অ্যাকাউন্ট পাওয়া যায়নি। অনুগ্রহ করে ডিভাইসে গুগল একাউন্ট যোগ করুন।")
            } catch (e: GetCredentialException) {
                Log.e(TAG, "GetCredentialException: ${e.message}", e)
                onError("গুগল ক্রেডেনশিয়াল ত্রুটি: ${e.localizedMessage ?: "সাইন ইন ব্যর্থ"}")
            } catch (e: Exception) {
                Log.e(TAG, "Authentication failed: ${e.message}", e)
                onError("সাইন-ইন ব্যর্থ হয়েছে: ${e.localizedMessage ?: "নেটওয়ার্ক সমস্যা"}")
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
