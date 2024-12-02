package de.uksh.medic.cxx2medic.authentication

import org.apache.http.auth.Credentials
import java.security.Principal

object NoCredentials: Credentials
{
    override fun getUserPrincipal(): Principal =
        throw UnsupportedOperationException("Contains no principal by definition")

    override fun getPassword(): String =
        throw UnsupportedOperationException("Contains no password by definition")
}