package de.uksh.medic.cxx2medic.config

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import org.apache.http.auth.UsernamePasswordCredentials

class AuthenticationSettings(
    basic: BasicAuthSettings? = null
) {
    val basic: Option<BasicAuthSettings> = if (basic == null) None else Some(basic)

    init
    {
        require(this.basic.isSome())
    }

    fun getCredentials() = basic.map { it.getCredentials() }
}

data class BasicAuthSettings(
    val username: String,
    val password: String
) {
    fun getCredentials() = UsernamePasswordCredentials(username, password)
}
