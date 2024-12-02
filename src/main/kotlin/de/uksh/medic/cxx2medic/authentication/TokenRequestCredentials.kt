package de.uksh.medic.cxx2medic.authentication

import org.apache.http.auth.Credentials
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.impl.client.BasicCredentialsProvider
import java.security.Principal
import javax.security.auth.Subject

typealias TokenRequestCredentialsProvider = BasicCredentialsProvider

enum class GrantType
{
    CLIENT_CREDENTIALS, PASSWORD, REFRESH_TOKEN
}

abstract class TokenRequestCredentials(
    private val clientId: String,
    private val clientSecret: String
): Credentials
{
    abstract val grantType: GrantType

    override fun getUserPrincipal(): Principal = ClientPrincipal(clientId)

    override fun getPassword(): String = clientSecret
}

class OAuthClientCredentials(
    clientId: String,
    clientSecret: String
): TokenRequestCredentials(clientId, clientSecret)
{
    override val grantType: GrantType = GrantType.CLIENT_CREDENTIALS
}

class OAuthPasswordCredentials(
    username: String,
    password: String,
    clientId: String,
    clientSecret: String
): TokenRequestCredentials(clientId, clientSecret)
{
    val usernameAndPassword: UsernamePasswordCredentials = UsernamePasswordCredentials(username, password)

    override val grantType: GrantType = GrantType.PASSWORD
}

class OAuthRefreshTokenCredentials(
    var refreshToken: String,
    clientId: String,
    clientSecret: String
): TokenRequestCredentials(clientId, clientSecret)
{
    override val grantType: GrantType = GrantType.REFRESH_TOKEN
}

@JvmInline
value class ClientPrincipal(
    private val clientId: String
): Principal
{
    override fun getName(): String = clientId

    override fun implies(subject: Subject?): Boolean =
        if (subject == null) false else this in subject.principals
}