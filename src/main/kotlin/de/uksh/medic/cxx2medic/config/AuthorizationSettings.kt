package de.uksh.medic.cxx2medic.config

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import de.uksh.medic.cxx2medic.authentication.*
import kotlin.contracts.contract

class AuthorizationSettings(
    oauth: OAuthSettings? = null
) {
    val oauth: Option<OAuthSettings> = if (oauth == null) None else Some(oauth)

    init
    {
        require(this.oauth.isSome())
    }
}

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