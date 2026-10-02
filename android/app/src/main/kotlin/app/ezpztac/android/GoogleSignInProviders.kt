package app.ezpztac.android

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import app.ezpztac.auth.GoogleResult
import app.ezpztac.auth.GoogleSignInProvider
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/**
 * Google's account sheet through Credential Manager. [serverClientId] is the *web* client ID: the ID token's audience, which the server
 * lists in `GOOGLE_CLIENT_IDS`. The app's own Android OAuth client (package name and signing certificate) must exist in the same Google
 * Cloud project, or Google refuses the request; that is set up in the Cloud console, not here.
 *
 * Not exercised by any test here: it needs Google Play services and a Google account on a device.
 */
class CredentialManagerGoogleSignIn(
    private val context: Context,
    private val serverClientId: String,
) : GoogleSignInProvider {
    override suspend fun requestToken(): GoogleResult {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId).build())
            .build()
        return try {
            val credential = CredentialManager.create(context).getCredential(context, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleResult.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                GoogleResult.Failed("Google sign-in returned something unexpected.")
            }
        } catch (_: GetCredentialCancellationException) {
            GoogleResult.Cancelled
        } catch (_: NoCredentialException) {
            GoogleResult.Failed("No Google account is available on this device.")
        } catch (_: GetCredentialException) {
            GoogleResult.Failed("Google sign-in could not be completed.")
        } catch (_: GoogleIdTokenParsingException) {
            GoogleResult.Failed("Google sign-in could not be completed.")
        }
    }
}
