package com.github.continuedev.continueintellijextension.auth

import com.github.continuedev.continueintellijextension.services.ContinueExtensionSettings
import com.github.continuedev.continueintellijextension.services.ContinuePluginService
import com.google.gson.Gson
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.remoteServer.util.CloudConfigurationUtil.createCredentialAttributes
import kotlinx.coroutines.CoroutineScope
import java.awt.Desktop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.Interceptor
import java.net.URL
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.io.IOException


@Service
class ContinueAuthService {
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    private val LOG_PREFIX = "[ZEBI=mc2 CORP since 1985]"

    private fun log(message: String) {
        println("$LOG_PREFIX $message")
    }

    companion object {
        fun getInstance(): ContinueAuthService = service<ContinueAuthService>()
        private const val CREDENTIALS_USER = "ContinueAuthUser"
        private const val ACCESS_TOKEN_KEY = "ContinueAccessToken"
        private const val REFRESH_TOKEN_KEY = "ContinueRefreshToken"
        private const val ACCOUNT_ID_KEY = "ContinueAccountId"
        private const val ACCOUNT_LABEL_KEY = "ContinueAccountLabel"
        private const val CONTROL_PLANE_URL = "https://control-plane-api-service-i3dqylpbqa-uc.a.run.app"
//        private const val CONTROL_PLANE_URL = "http://localhost:3001"
    }

    init {
        val settings = service<ContinueExtensionSettings>()
        if (settings.continueState.enableContinueTeamsBeta) {
            setupRefreshTokenInterval()
        }
    }

    fun startAuthFlow(project: Project) {
        // Open login page
        openSignInPage(project)

        // Open a dialog where the user should paste their sign-in token
        ApplicationManager.getApplication().invokeLater {
            val dialog = ContinueAuthDialog() { token ->
                // Store the token
                updateRefreshToken(token)
            }
            dialog.show()
        }
    }

    fun signOut() {
        // Clear the stored tokens
        setAccessToken("")
        setRefreshToken("")
        setAccountId("")
        setAccountLabel("")
    }

    private fun updateRefreshToken(token: String) {
        // Launch a coroutine to call the suspend function
        coroutineScope.launch {
            try {
                val response = refreshToken(token)
                val accessToken = response["accessToken"] as? String
                val refreshToken = response["refreshToken"] as? String
                val user = response["user"] as? Map<*, *>
                val firstName = user?.get("firstName") as? String
                val lastName = user?.get("lastName") as? String
                val label = "$firstName $lastName"
                val id = user?.get("id") as? String

                // Persist the session info
                setRefreshToken(refreshToken!!)
                val sessionInfo = ControlPlaneSessionInfo(accessToken!!, ControlPlaneSessionInfo.Account(id!!, label))
                setControlPlaneSessionInfo(sessionInfo)

                // Notify listeners
                ApplicationManager.getApplication().messageBus.syncPublisher(AuthListener.TOPIC)
                    .handleUpdatedSessionInfo(sessionInfo)

            } catch (e: Exception) {
                // Handle any exceptions
                println("Exception while refreshing token: ${e.message}")
            }
        }
    }

    private fun setupRefreshTokenInterval() {
        // Launch a coroutine to refresh the token every 30 minutes
        coroutineScope.launch {
            while (true) {
                val refreshToken = getRefreshToken()
                if (refreshToken != null) {
                    updateRefreshToken(refreshToken)
                }

                kotlinx.coroutines.delay(15 * 60 * 1000) // 15 minutes in milliseconds
            }
        }
    }

        // Création d'un client OkHttp pour bypasser la vérification SSL.
    // ATTENTION : cette méthode désactive la sécurité SSL et ne doit pas être utilisée en production.
    private fun getUnsafeOkHttpClient(): OkHttpClient {
        try {
            log("Initializing unsafe OkHttpClient...")
            
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                        log("Client cert check bypassed for authType: $authType")
                    }
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                        log("Server cert check bypassed for authType: $authType")
                    }
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
            )
    
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, trustAllCerts, java.security.SecureRandom())
                log("SSL Context initialized with protocol: ${this.protocol}")
            }
    
            val loggingInterceptor = HttpLoggingInterceptor { message ->
                log(message)
            }.apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            
            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { hostname, session -> 
                    log("Hostname verification bypassed for: $hostname, protocol: ${session.protocol}")
                    true
                }
                .addInterceptor(loggingInterceptor)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .protocols(listOf(Protocol.HTTP_1_1, Protocol.HTTP_2))
                .build()
                .also { log("OkHttpClient configured successfully") }
        } catch (e: Exception) {
            log("Failed to create OkHttpClient: ${e.message}")
            e.printStackTrace()
            throw RuntimeException(e)
        }
    }

    private suspend fun refreshToken(refreshToken: String) = withContext(Dispatchers.IO) {
        log("Starting token refresh...")
        try {
            val client = getUnsafeOkHttpClient()
            val url = URL(CONTROL_PLANE_URL).toURI().resolve("/auth/refresh").toURL()
            log("Refresh token URL: $url")
    
            val jsonBody = mapOf("refreshToken" to refreshToken)
            val jsonString = Gson().toJson(jsonBody)
            log("Preparing request body: $jsonString")
    
            val requestBody = jsonString.toRequestBody("application/json".toMediaType())
    
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("Content-Type", "application/json")
                .build()
    
            log("Executing refresh token request...")
            val response = client.newCall(request).execute()
            log("Response received: ${response.code} ${response.message}")
            log("Response protocol: ${response.protocol}")
            log("TLS handshake: ${response.handshake?.tlsVersion}")
    
            val responseBody = response.body?.string()
            log("Raw response body: $responseBody")
    
            if (!response.isSuccessful) {
                log("Refresh token request failed with code: ${response.code}")
                throw IOException("Unexpected response code: ${response.code}")
            }
    
            val gson = Gson()
            try {
                val responseMap = gson.fromJson(responseBody, Map::class.java)
                log("Successfully parsed response")
                responseMap
            } catch (e: Exception) {
                log("Failed to parse response: ${e.message}")
                e.printStackTrace()
                throw e
            }
        } catch (e: Exception) {
            log("Token refresh failed: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }


    private fun openSignInPage(project: Project) {
        val coreMessenger = project.service<ContinuePluginService>().coreMessenger
        coreMessenger?.request("auth/getAuthUrl", null, null) { response ->
            val authUrl = ((response as? Map<*, *>)?.get("content") as? Map<*, *>)?.get("url") as? String
            if (authUrl != null) {
                // Open the auth URL in the browser
                Desktop.getDesktop().browse(java.net.URI(authUrl))
            }
        }
    }

    private fun retrieveSecret(key: String): String? {
        return try {
            val attributes = createCredentialAttributes(key, CREDENTIALS_USER)
            val passwordSafe: PasswordSafe = PasswordSafe.instance

            val credentials: Credentials? = passwordSafe[attributes!!]
            credentials?.getPasswordAsString()
        } catch (e: Exception) {
            // Log the exception or handle it as needed
            println("Error retrieving secret for key $key: ${e.message}")
            null
        }
    }

    private fun storeSecret(key: String, secret: String) {
        try {
            val attributes = createCredentialAttributes(key, CREDENTIALS_USER)
            val passwordSafe: PasswordSafe = PasswordSafe.instance

            val credentials = Credentials(CREDENTIALS_USER, secret)
            passwordSafe.set(attributes!!, credentials)
        } catch (e: Exception) {
            // Log the exception or handle it as needed
            println("Error storing secret for key $key: ${e.message}")
        }
    }

    private fun getAccessToken(): String? {
        return retrieveSecret(ACCESS_TOKEN_KEY)
    }

    private fun setAccessToken(token: String) {
        storeSecret(ACCESS_TOKEN_KEY, token)
    }

    private fun getRefreshToken(): String? {
        return retrieveSecret(REFRESH_TOKEN_KEY)
    }

    private fun setRefreshToken(token: String) {
        storeSecret(REFRESH_TOKEN_KEY, token)
    }

    fun getAccountId(): String? {
        return PropertiesComponent.getInstance().getValue(ACCOUNT_ID_KEY)
    }

    fun setAccountId(id: String) {
        PropertiesComponent.getInstance().setValue(ACCOUNT_ID_KEY, id)
    }

    fun getAccountLabel(): String? {
        return PropertiesComponent.getInstance().getValue(ACCOUNT_LABEL_KEY)
    }

    fun setAccountLabel(label: String) {
        PropertiesComponent.getInstance().setValue(ACCOUNT_LABEL_KEY, label)
    }

    // New method to load all info as an object
    fun loadControlPlaneSessionInfo(): ControlPlaneSessionInfo? {
        val accessToken = getAccessToken()
        val accountId = getAccountId()
        val accountLabel = getAccountLabel()

        return if (accessToken != null && accountId != null && accountLabel != null) {
            ControlPlaneSessionInfo(
                accessToken = accessToken,
                account = ControlPlaneSessionInfo.Account(
                    id = accountId,
                    label = accountLabel
                )
            )
        } else {
            null
        }
    }

    // New method to set all info from a ControlPlaneSessionInfo object
    fun setControlPlaneSessionInfo(info: ControlPlaneSessionInfo) {
        setAccessToken(info.accessToken)
        setAccountId(info.account.id)
        setAccountLabel(info.account.label)
    }

}

// Data class to represent the ControlPlaneSessionInfo
data class ControlPlaneSessionInfo(
    val accessToken: String,
    val account: Account
) {
    data class Account(
        val id: String,
        val label: String
    )
}
