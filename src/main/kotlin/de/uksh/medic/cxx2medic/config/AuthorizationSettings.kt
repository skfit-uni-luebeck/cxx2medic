package de.uksh.medic.cxx2medic.config

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import de.uksh.medic.cxx2medic.authentication.*
import org.apache.http.auth.UsernamePasswordCredentials
import kotlin.contracts.contract

class AuthorizationSettings(
    basic: BasicSettings? = null,
    oauth: OAuthSettings? = null
) {
    val basic: Option<UsernamePasswordCredentials> = if (basic == null) None
    else Some(UsernamePasswordCredentials(basic.username, basic.password))
    val oauth: Option<OAuthSettings> = if (oauth == null) None else Some(oauth)

    init
    {
        require(this.oauth.isSome() || this.basic.isSome())
    }
}

class BasicSettings(
    val username: String,
    val password: String,
)

class OAuthSettings(
    grantType: String,
    val accessTokenUrl: String,
    val clientId: String,
    val clientSecret: String,
    val username: String?,
    val password: String?,
) {
    val grantType: GrantType = GrantType.valueOf(grantType.uppercase())

    init
    {
        if (this.grantType == GrantType.PASSWORD) require(username != null && password != null)
    }

    fun getCredentials(): TokenRequestCredentials =
        when (grantType) {
            GrantType.CLIENT_CREDENTIALS -> OAuthClientCredentials(clientId, clientSecret)
            GrantType.PASSWORD -> OAuthPasswordCredentials(username!!, password!!, clientId, clientSecret)
            GrantType.REFRESH_TOKEN -> OAuthRefreshTokenCredentials("", clientId, clientSecret)
        }
}