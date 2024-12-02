package de.uksh.medic.cxx2medic.fhir.interception

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import ca.uhn.fhir.interceptor.api.Hook
import ca.uhn.fhir.interceptor.api.Interceptor
import ca.uhn.fhir.interceptor.api.Pointcut
import ca.uhn.fhir.rest.client.api.ClientResponseContext
import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.client.api.IHttpRequest
import ca.uhn.fhir.rest.client.api.IHttpResponse
import ca.uhn.fhir.rest.client.api.IRestfulClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import de.uksh.medic.cxx2medic.authentication.GrantType
import de.uksh.medic.cxx2medic.authentication.OAuthPasswordCredentials
import de.uksh.medic.cxx2medic.authentication.OAuthRefreshTokenCredentials
import de.uksh.medic.cxx2medic.authentication.TokenRequestCredentials
import de.uksh.medic.cxx2medic.exception.OAuthException
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

@Interceptor
class BearerTokenEnrichingInterceptor(
    accessTokenUrl: String,
    credentials: TokenRequestCredentials
): HeaderEnrichingInterceptor<HeadersProvider>()
{
    private val tokenClient: OkHttpClient = OkHttpClient().newBuilder().build()
    private val accessTokenRequest = buildAccessTokenRequest(accessTokenUrl, credentials)
    private var accessToken: Option<String> = None
    override val provider = HeadersProvider { mapOf("Authorization" to listOf("Bearer ${getAccessTokenStr()}")) }

    private fun getAccessTokenStr(): String =
        accessToken.fold({ refreshAccessToken(); getAccessTokenStr() }, { s -> s })

    fun refreshAccessToken()
    {
        accessToken = getFreshAccessToken().fold(
            { s -> Some(s) },
            { e -> throw e }
        )
    }

    private fun getFreshAccessToken(): Result<String> =
        kotlin.runCatching { tokenClient.newCall(accessTokenRequest).execute() }.fold(
            { r -> Result.success(MAPPER.readValue(r.body.byteStream(), HashMap::class.java)["access_token"]!! as String) },
            { e -> Result.failure(OAuthException("Failed to refresh access token", e))}
        )

    companion object
    {
        private val MAPPER: ObjectMapper = ObjectMapper().registerModules(KotlinModule.Builder().build())

        private fun buildAccessTokenRequest(accessTokenUrl: String, credentials: TokenRequestCredentials): Request
        {
            val body = FormBody.Builder()
                .add("grant_type", credentials.grantType.name.lowercase())
                .add("client_id", credentials.userPrincipal.name)
                .add("client_secret", credentials.password)
                .apply {
                    when (credentials) {
                        is OAuthPasswordCredentials -> {
                            add("username", credentials.usernameAndPassword.userName)
                            add("password", credentials.usernameAndPassword.password)
                        }
                        is OAuthRefreshTokenCredentials -> add("refresh_token", credentials.refreshToken)
                        else -> { }
                    }
                }
                .build()

            return  Request.Builder()
                .url(accessTokenUrl)
                .method("POST", body)
                .build()
        }
    }
}

@Interceptor
class OAuthUnauthorizedInterceptor
{
    @Hook(Pointcut.CLIENT_RESPONSE)
    fun handleError(request: IHttpRequest, response: IHttpResponse, client: IRestfulClient, context: ClientResponseContext)
    {
        if (response.status == 401 /*Unauthorized*/) {
            logger.debug("Requesting fresh access token")
            client.interceptorService.allRegisteredInterceptors
                .filterIsInstance<BearerTokenEnrichingInterceptor>()
                .forEach { it.refreshAccessToken() }
            logger.debug("Received fresh access token. Retrying request")
            context.httpResponse = request.execute()
        }
    }

    companion object
    {
        private val logger: Logger = LogManager.getLogger(OAuthUnauthorizedInterceptor::class.java)
    }
}

fun IGenericClient.withOAuth2(
    accessTokenUrl: String,
    credentials: TokenRequestCredentials
): IGenericClient = apply {
    registerInterceptor(BearerTokenEnrichingInterceptor(accessTokenUrl, credentials))
    registerInterceptor(OAuthUnauthorizedInterceptor())
}